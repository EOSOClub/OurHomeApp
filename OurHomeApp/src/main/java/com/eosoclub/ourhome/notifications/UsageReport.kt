package com.eosoclub.ourhome.notifications

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.UsageDay
import com.eosoclub.ourhome.data.UsageEvent
import com.eosoclub.ourhome.data.foregroundMillis
import com.eosoclub.ourhome.data.usageDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * App usage (parental controls; web appUsageService). Only while an adult who
 * looks after this member has turned sharing it on, and only with Android's
 * Usage access allowed: measures how long each app was on screen today and
 * yesterday, and sends it with the hourly check. Nothing is blocked.
 *
 * Usage access is a special permission the member allows in Android's
 * settings (Profile shows how). On Android 15 an app installed outside Play
 * first needs "Allow restricted settings" in its App info.
 */
object UsageReport {
    private const val TAG = "UsageReport"

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Android's page where Usage access is allowed. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    suspend fun send(context: Context, api: ApiClient) {
        if (!api.myUsageSharing().shared) return
        if (!hasAccess(context)) {
            Log.i(TAG, "usage is shared but Usage access isn't allowed; nothing to send")
            return
        }
        val today = LocalDate.now()
        val days = withContext(Dispatchers.IO) { listOf(today.minusDays(1), today).map { measure(context, it) } }
        try {
            api.reportUsage(days)
            Log.i(TAG, "sent ${days.size} day(s) of app usage")
        } catch (e: ApiException) {
            // 409: sharing was turned off since we asked.
            if (e.status != 409) throw e
        }
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
