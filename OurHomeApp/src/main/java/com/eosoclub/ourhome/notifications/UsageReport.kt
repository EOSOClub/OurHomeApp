package com.eosoclub.ourhome.notifications

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
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
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.NEW_APPS_PER_REPORT
import com.eosoclub.ourhome.data.USAGE_ICONS_PER_UPLOAD
import com.eosoclub.ourhome.data.USAGE_ICON_MAX_BYTES
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.data.UsageDay
import com.eosoclub.ourhome.data.UsageEvent
import com.eosoclub.ourhome.data.foregroundMillis
import com.eosoclub.ourhome.data.newApps
import com.eosoclub.ourhome.data.usageDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * App usage (parental controls; web appUsageService). Only while an adult who
 * looks after this member has turned sharing it on, and only with Android's
 * Usage access allowed: measures how long each app was on screen today and
 * yesterday, and sends it. Nothing is blocked.
 *
 * When: the hourly check ([RequestReminders.Worker]) asks whether usage is
 * shared and, while it is, keeps a 15-minute job of its own going (the
 * shortest repeat Android allows; it waits while the phone sleeps, when no
 * new usage builds up anyway). A supervisor opening "People you look after"
 * sends a "usage_refresh" push, and [sendNow] reports within seconds.
 *
 * After each report the server lists the apps it has no icon for; those are
 * sent once, as small PNGs, so supervisors see the icon next to the minutes.
 *
 * Each run also compares the apps that can be opened with the last run's,
 * and tells the server about new ones, so supervisors are told (Android
 * doesn't tell a background app about installs). That needs no Usage access.
 *
 * Usage access is a special permission the member allows in Android's
 * settings (Profile shows how). On Android 15 an app installed outside Play
 * first needs "Allow restricted settings" in its App info.
 */
object UsageReport {
    private const val TAG = "UsageReport"
    private const val WORK_NAME = "usage-report"
    private const val NOW_WORK_NAME = "usage-report-now"
    private const val INTERVAL_MINUTES = 15L

    /** The apps seen on the last look, for spotting new ones. */
    private const val PREFS = "new_app_watch"
    private const val KEY_KNOWN = "known_apps"

    /** Icon size sent to the server, in pixels. */
    private const val ICON_PX = 96

    /** Keeps the 15-minute report going (while usage is shared; [send] stops it otherwise). */
    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Worker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Reports right away (a supervisor is looking). Expedited, so a dozing phone runs it within seconds. */
    fun sendNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Signed out, or usage no longer shared: no more reports. The apps seen are
     * forgotten too, so sharing turned on again later starts with a fresh look
     * instead of reporting everything installed in between.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(NOW_WORK_NAME)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = (applicationContext as OurHomeApp).session.api
            return try {
                send(applicationContext, api)
                Result.success()
            } catch (e: UnauthorizedException) {
                cancel(applicationContext)
                Result.success()
            } catch (e: Exception) {
                // The next run (15 minutes, or the hourly check) tries again.
                Log.w(TAG, "app usage report failed", e)
                Result.success()
            }
        }
    }

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Android's page where Usage access is allowed, in Settings' own task (see CloseGuard.accessibilitySettings). */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun send(context: Context, api: ApiClient) {
        if (!api.myUsageSharing().shared) {
            cancel(context)
            return
        }
        schedule(context)
        if (!reportNewApps(context, api)) return
        if (!hasAccess(context)) {
            Log.i(TAG, "usage is shared but Usage access isn't allowed; nothing to send")
            return
        }
        val today = LocalDate.now()
        val days = withContext(Dispatchers.IO) { listOf(today.minusDays(1), today).map { measure(context, it) } }
        val missing = try {
            api.reportUsage(days).missingIcons
        } catch (e: ApiException) {
            // 409: sharing was turned off since we asked.
            if (e.status != 409) throw e
            cancel(context)
            return
        }
        Log.i(TAG, "sent ${days.size} day(s) of app usage; ${missing.size} icon(s) wanted")
        if (missing.isEmpty()) return
        // A failed icon upload only means it's asked for again next time.
        runCatching {
            val icons = withContext(Dispatchers.IO) { icons(context, missing) }
            icons.entries.chunked(USAGE_ICONS_PER_UPLOAD).forEach { batch ->
                api.reportUsageIcons(batch.associate { it.key to it.value })
            }
            Log.i(TAG, "sent ${icons.size} app icon(s)")
        }.onFailure { Log.w(TAG, "app icons not sent", it) }
    }

    /**
     * Tells the server about apps that can be opened now but couldn't on the
     * last look; the first look only remembers them. False when usage is no
     * longer shared (409). Anything else that goes wrong is tried again next
     * time: what was seen is only stored once the server has it.
     */
    private suspend fun reportNewApps(context: Context, api: ApiClient): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet(KEY_KNOWN, null)?.toSet()
        val current = withContext(Dispatchers.IO) { openableApps(context) }
        val fresh = newApps(known, current)
        if (fresh.isNotEmpty()) {
            try {
                fresh.entries.chunked(NEW_APPS_PER_REPORT).forEach { batch ->
                    api.reportNewApps(batch.associate { it.key to it.value })
                }
                Log.i(TAG, "reported ${fresh.size} new app(s)")
            } catch (e: ApiException) {
                if (e.status == 409) {
                    cancel(context)
                    return false
                }
                Log.w(TAG, "new apps not reported", e)
                return true
            } catch (e: java.io.IOException) {
                Log.w(TAG, "new apps not reported", e)
                return true
            }
        }
        // An app removed since drops out, so installing it again is news again.
        prefs.edit { putStringSet(KEY_KNOWN, current.keys) }
        return true
    }

    /** Every app with a launcher icon (package name to its name), OurHome itself left out. */
    private fun openableApps(context: Context): Map<String, String> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .associate { info -> info.packageName to runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName) }
    }

    /** [apps]' launcher icons as small PNGs, base64; apps gone from the phone, or too big, are left out. */
    private fun icons(context: Context, apps: List<String>): Map<String, String> {
        val pm = context.packageManager
        val out = linkedMapOf<String, String>()
        for (app in apps) {
            val drawable = runCatching { pm.getApplicationIcon(app) }.getOrNull() ?: continue
            val png = ByteArrayOutputStream().use { stream ->
                drawable.toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            if (png.size > USAGE_ICON_MAX_BYTES) continue
            out[app] = Base64.encodeToString(png, Base64.NO_WRAP)
        }
        return out
    }

    private fun measure(context: Context, day: LocalDate): UsageDay {
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        val stats = context.getSystemService(UsageStatsManager::class.java)
        val pm = context.packageManager
        val launcher = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val events = mutableListOf<UsageEvent>()
        if (stats != null && end > start) {
            val raw = stats.queryEvents(start, end)
            val e = UsageEvents.Event()
            while (raw.hasNextEvent()) {
                raw.getNextEvent(e)
                val foreground = when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> true
                    UsageEvents.Event.ACTIVITY_PAUSED -> false
                    else -> continue
                }
                // The home screen isn't an app anyone "uses".
                if (e.packageName == launcher) continue
                events += UsageEvent(e.packageName, foreground, e.timeStamp)
            }
        }
        // Only apps someone can open (not system parts that show a screen now and then).
        val millis = foregroundMillis(events, start, end).filterKeys { pm.getLaunchIntentForPackage(it) != null }
        return usageDay(day.toString(), millis) { app ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString() }.getOrDefault(app)
        }
    }
}
