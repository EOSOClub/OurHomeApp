package com.eosoclub.ourhome.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.HouseholdRequest
import com.eosoclub.ourhome.data.Permission
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.data.can
import com.eosoclub.ourhome.data.awaitingAcceptanceBy
import com.eosoclub.ourhome.data.canApproveMedia
import com.eosoclub.ourhome.data.defaultAccess
import com.eosoclub.ourhome.data.summary
import java.util.concurrent.TimeUnit

/**
 * "Requests are waiting for you" reminders, plus the shared check that also
 * drives [DeadlineReminders], [BugReportAlerts], [AppUpdateAlerts] and
 * [HouseholdReminderAlerts].
 *
 * A WorkManager job polls the server every [INTERVAL_HOURS] while the user is
 * signed in. Each hourly run re-posts the notification (and alerts again) for
 * as long as something still awaits the user's acceptance, and clears it once
 * nothing does. Android may defer runs in battery saver / Doze, so timing is
 * approximate.
 *
 * With instant alerts set up ([Push]), the server also wakes the phone when
 * something changes and [checkNow] runs the same check at once. A push run
 * alerts only for requests it hasn't alerted about yet, so a push about
 * something else doesn't re-ring for requests already shown; the hourly run
 * stays the repeating reminder.
 */
object RequestReminders {
    const val INTERVAL_HOURS = 1L
    private const val WORK_NAME = "request-reminders"
    private const val NOW_WORK_NAME = "request-reminders-now"
    private const val KEY_FROM_PUSH = "from_push"
    private const val CHANNEL_ID = "requests"
    private const val NOTIFICATION_ID = 1001
    private const val PREFS = "request_reminders"
    private const val KEY_SHOWN = "shown_ids"
    private const val TAG = "RequestReminders"

    /** Intent extra asking MainActivity to open the Requests tab. */
    const val EXTRA_OPEN_TAB = "open_tab"
    const val TAB_REQUESTS = "requests"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Runs the check right away, for a push. Expedited so a dozing phone runs
     * it within seconds; queued behind a check already running (APPEND) so
     * back-to-back pushes never cancel one mid-way or get dropped.
     */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(KEY_FROM_PUSH to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(NOW_WORK_NAME)
        clear(context)
        // Signed out: nothing from that account may linger in the shade, and
        // the next account starts with a fresh "already alerted" memory.
        HouseholdReminderAlerts.clear(context)
        BugReportAlerts.clear(context)
        DeadlineReminders.clear(context)
        AppUpdateAlerts.clear(context)
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Requests", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Reminders while a request is waiting for you to accept"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context, waiting: List<HouseholdRequest>) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val title = if (waiting.size == 1) "A request is waiting for you" else "${waiting.size} requests are waiting for you"
        val lines = waiting.map { it.summary() }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_TAB, TAB_REQUESTS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle().also { s -> lines.take(5).forEach(s::addLine) }
        if (lines.size > 5) style.setSummaryText("+${lines.size - 5} more")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_request)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setNumber(waiting.size)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            // Lock screen shows only the count, not who asked for what.
            .lockScreenSafe(context, CHANNEL_ID, title)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }

    /**
     * Posts or clears the reminder for what's waiting now. [onlyIfNew] (push
     * runs) skips the post when every waiting request was already in the last
     * one, so the user isn't re-alerted; the hourly run passes false.
     */
    private fun update(context: Context, waiting: List<HouseholdRequest>, onlyIfNew: Boolean) {
        if (waiting.isEmpty()) {
            clear(context)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet())!!
        val ids = waiting.map { it.id }.toSet()
        if (onlyIfNew && shown.containsAll(ids)) {
            Log.i(TAG, "nothing new since the last reminder; staying quiet")
            return
        }
        show(context, waiting)
        prefs.edit { putStringSet(KEY_SHOWN, ids) }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_SHOWN) }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = (applicationContext as OurHomeApp).session.api
            return try {
                val user = api.getSession()
                if (user == null) {
                    Log.i(TAG, "not signed in; nothing to check")
                    clear(applicationContext)
                    HouseholdReminderAlerts.clear(applicationContext)
                    return Result.success()
                }
                val fromPush = inputData.getBoolean(KEY_FROM_PUSH, false)
                val requests = api.requests()
                // Media approvers come from the head's grid; an older server
                // without /api/permissions/me gets the role's built-ins.
                val access = runCatching { api.myAccess().access }.getOrElse { defaultAccess(user.role) }
                val waiting = awaitingAcceptanceBy(requests, user.id, canApproveMedia(access, user.role))
                Log.i(TAG, "${waiting.size} request(s) awaiting acceptance (${if (fromPush) "push" else "hourly"})")
                update(applicationContext, waiting, onlyIfNew = fromPush)
                DeadlineReminders.check(applicationContext, requests, user.id)
                // Same poll, so bug reports share the cadence; a failure here
                // must not undo the request check above.
                if (can(user.role, Permission.BugsManage)) {
                    runCatching { BugReportAlerts.check(applicationContext, api) }
                        .onFailure { Log.w(TAG, "bug report check failed", it) }
                }
                runCatching { AppUpdateAlerts.check(applicationContext, api) }
                    .onFailure { Log.w(TAG, "app update check failed", it) }
                runCatching { HouseholdReminderAlerts.check(applicationContext, api) }
                    .onFailure { Log.w(TAG, "household reminder check failed", it) }
                Result.success()
            } catch (e: UnauthorizedException) {
                clear(applicationContext)
                HouseholdReminderAlerts.clear(applicationContext)
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "check failed; will retry", e)
                Result.retry()
            }
        }
    }
}
