package com.eosoclub.ourhome.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.Notification

/**
 * Phone alerts for new bug reports, for whoever receives them (bugs:manage —
 * the head). Runs inside the request-reminder poll, so it shares its hourly
 * cadence. The server addresses each report's bell notification to the head;
 * this alerts once per report (ids are remembered), not on every poll.
 */
object BugReportAlerts {
    private const val CHANNEL_ID = "bug_reports"
    private const val NOTIFICATION_ID = 1002
    private const val PREFS = "bug_report_alerts"
    private const val KEY_ALERTED = "alerted_ids"
    private const val MAX_REMEMBERED = 200
    private const val TAG = "BugReportAlerts"

    suspend fun check(context: Context, api: ApiClient) {
        val unread = api.notifications(unreadOnly = true).items.filter { it.type == "bug_report" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alerted = prefs.getStringSet(KEY_ALERTED, emptySet())!!
        val fresh = unread.filter { it.id !in alerted }
        Log.i(TAG, "${unread.size} unread bug report(s), ${fresh.size} new")
        if (fresh.isEmpty()) return
        show(context, fresh)
        // Remember what we alerted about (bounded) so the next poll stays quiet.
        val updated = (alerted + fresh.map { it.id }).toList().takeLast(MAX_REMEMBERED).toSet()
        prefs.edit { putStringSet(KEY_ALERTED, updated) }
    }

    private fun show(context: Context, reports: List<Notification>) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bug reports", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New bug reports from the household"
            },
        )
        val title = if (reports.size == 1) reports.first().title else "${reports.size} new bug reports"
        val text = if (reports.size == 1) reports.first().body.orEmpty() else reports.joinToString(" · ") { it.title }
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bug)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Report text can be anything — keep it off the lock screen.
            .lockScreenSafe(context, CHANNEL_ID, "New bug report")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }
}
