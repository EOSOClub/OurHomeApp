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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.HouseholdRequest
import com.eosoclub.ourhome.data.Permission
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.data.can
import com.eosoclub.ourhome.data.awaitingAcceptanceBy
import com.eosoclub.ourhome.data.summary
import java.util.concurrent.TimeUnit

/**
 * Periodic "requests are waiting for you" reminders.
 *
 * There is no push service yet, so a WorkManager job polls the server every
 * [INTERVAL_HOURS] while the user is signed in. Each run re-posts the
 * notification (and alerts again) for as long as something still awaits the
 * user's acceptance, and clears it once nothing does. Android may defer runs
 * in battery saver / Doze, so timing is approximate.
 */
object RequestReminders {
    const val INTERVAL_HOURS = 1L
    private const val WORK_NAME = "request-reminders"
    private const val CHANNEL_ID = "requests"
    private const val NOTIFICATION_ID = 1001
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

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        clear(context)
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

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = (applicationContext as OurHomeApp).session.api
            return try {
                val user = api.getSession()
                if (user == null) {
                    Log.i(TAG, "not signed in; nothing to check")
                    clear(applicationContext)
                    return Result.success()
                }
                val requests = api.requests()
                val waiting = awaitingAcceptanceBy(requests, user.id, user.role)
                Log.i(TAG, "${waiting.size} request(s) awaiting acceptance")
                if (waiting.isEmpty()) clear(applicationContext) else show(applicationContext, waiting)
                DeadlineReminders.check(applicationContext, requests, user.id)
                // Same poll, so bug reports share the cadence; a failure here
                // must not undo the request check above.
                if (can(user.role, Permission.BugsManage)) {
                    runCatching { BugReportAlerts.check(applicationContext, api) }
                        .onFailure { Log.w(TAG, "bug report check failed", it) }
                }
                Result.success()
            } catch (e: UnauthorizedException) {
                clear(applicationContext)
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "check failed; will retry", e)
                Result.retry()
            }
        }
    }
}
