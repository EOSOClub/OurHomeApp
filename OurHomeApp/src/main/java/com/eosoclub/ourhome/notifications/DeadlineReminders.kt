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
import com.eosoclub.ourhome.data.DeadlineAlert
import com.eosoclub.ourhome.data.DeadlineState
import com.eosoclub.ourhome.data.HouseholdRequest
import com.eosoclub.ourhome.data.deadlineAlerts
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Maintenance deadline reminders, driven by the done-by date (`dueAt`) the
 * assignee committed to. Runs inside the request-reminder poll.
 *
 * Alerts once per milestone rather than every poll: due tomorrow, due today,
 * then once a day while overdue (assignee); once when overdue (requester).
 * Keys include `dueAt`, so moving the date starts the milestones over. Each
 * post lists everything currently due; the notification is cleared once
 * nothing is.
 */
object DeadlineReminders {
    private const val CHANNEL_ID = "deadlines"
    private const val NOTIFICATION_ID = 1003
    private const val PREFS = "deadline_alerts"
    private const val KEY_ALERTED = "alerted_keys"
    private const val MAX_REMEMBERED = 300
    private const val TAG = "DeadlineReminders"
    private val shortDate = DateTimeFormatter.ofPattern("MMM d")

    fun check(context: Context, requests: List<HouseholdRequest>, userId: String) {
        val today = LocalDate.now()
        val alerts = deadlineAlerts(requests, userId, today)
        if (alerts.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            Log.i(TAG, "no deadlines due")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alerted = prefs.getStringSet(KEY_ALERTED, emptySet())!!
        val keys = alerts.map { it.key(today) }
        val fresh = keys.filterNot { it in alerted }
        Log.i(TAG, "${alerts.size} deadline(s) need attention, ${fresh.size} new")
        // Only a new milestone posts (and alerts); otherwise leave the
        // notification alone so a dismissed one doesn't come back every poll.
        if (fresh.isEmpty()) return
        show(context, alerts)
        val updated = (alerted + fresh).toList().takeLast(MAX_REMEMBERED).toSet()
        prefs.edit { putStringSet(KEY_ALERTED, updated) }
    }

    private fun DeadlineAlert.key(today: LocalDate): String {
        val base = "${request.id}:${request.dueAt}"
        return when {
            forRequester -> "$base:requester-overdue"
            // Nudge the assignee once per day while it stays overdue.
            state == DeadlineState.Overdue -> "$base:overdue:$today"
            else -> "$base:$state"
        }
    }

    private fun DeadlineAlert.line(): String {
        val date = due.format(shortDate)
        val title = request.title
        return when {
            forRequester -> "$title — ${request.assignee?.name ?: "They"} missed the $date done-by date"
            state == DeadlineState.DueTomorrow -> "$title — due tomorrow ($date)"
            state == DeadlineState.DueToday -> "$title — due today"
            else -> "$title — overdue since $date"
        }
    }

    private fun show(context: Context, alerts: List<DeadlineAlert>) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Maintenance deadlines", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders as a maintenance request's done-by date approaches or passes"
            },
        )
        val overdue = alerts.count { it.state == DeadlineState.Overdue }
        val title = when {
            alerts.size == 1 && alerts[0].forRequester -> "A maintenance request is overdue"
            alerts.size == 1 -> when (alerts[0].state) {
                DeadlineState.DueTomorrow -> "Maintenance due tomorrow"
                DeadlineState.DueToday -> "Maintenance due today"
                DeadlineState.Overdue -> "Maintenance overdue"
            }
            overdue > 0 -> "${alerts.size} maintenance deadlines ($overdue overdue)"
            else -> "${alerts.size} maintenance deadlines coming up"
        }
        val lines = alerts.sortedBy { it.due }.map { it.line() }
        val style = NotificationCompat.InboxStyle().also { s -> lines.take(5).forEach(s::addLine) }
        if (lines.size > 5) style.setSummaryText("+${lines.size - 5} more")
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .putExtra(RequestReminders.EXTRA_OPEN_TAB, RequestReminders.TAB_REQUESTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_request)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setNumber(alerts.size)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .lockScreenSafe(context, CHANNEL_ID, "Maintenance deadline")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }
}
