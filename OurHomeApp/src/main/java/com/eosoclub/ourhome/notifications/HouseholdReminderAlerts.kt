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
import com.eosoclub.ourhome.data.planReminderAlert
import com.eosoclub.ourhome.data.reminderLine

/**
 * Phone alerts for the reminders the web's 15-minute sweep generates: overdue
 * tasks, low or soon-depleted stock, bills coming due. Runs inside the
 * request-reminder poll; the server pushes when a sweep creates a new one, so
 * with push set up the alert arrives within seconds of the sweep, otherwise
 * on the next hourly check.
 *
 * One grouped notification lists everything still unread. It alerts only when
 * something new appears ([planReminderAlert]); otherwise it is refreshed
 * silently while showing, and removed once nothing is unread for this user
 * (handled, or marked read by them on any of their devices; read state is
 * per person, so someone else reading it changes nothing here).
 */
object HouseholdReminderAlerts {
    private const val CHANNEL_ID = "household_reminders"
    private const val NOTIFICATION_ID = 1005
    private const val PREFS = "household_reminder_alerts"
    private const val KEY_ALERTED = "alerted_ids"
    private const val TAG = "HouseholdReminders"

    /** Intent extra values asking MainActivity for a tab or the bell. */
    const val TAB_TASKS = "tasks"
    const val TAB_INVENTORY = "inventory"
    const val TAB_BILLS = "bills"
    const val TAB_NOTIFICATIONS = "notifications"

    suspend fun check(context: Context, api: ApiClient) {
        val list = api.notifications(unreadOnly = true)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alerted = if (prefs.contains(KEY_ALERTED)) prefs.getStringSet(KEY_ALERTED, emptySet())!! else null
        val plan = planReminderAlert(list.items, alerted, complete = !list.hasMore)
        Log.i(TAG, "${plan.showing.size} unread reminder(s), ${plan.alert.size} new" +
            if (alerted == null) " (first run: remembered, not alerted)" else "")
        when {
            plan.showing.isEmpty() -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            plan.alert.isNotEmpty() -> show(context, plan.showing, plan.alert, silent = false)
            // Nothing new: keep a visible notification current, but don't
            // bring back one the user swiped away.
            isShowing(context) -> show(context, plan.showing, plan.alert, silent = true)
        }
        prefs.edit { putStringSet(KEY_ALERTED, plan.remember) }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_ALERTED) }
    }

    private fun isShowing(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }

    /** One kind of subject opens its tab; a mix opens the bell. */
    private fun tabFor(reminders: List<Notification>): String =
        when (reminders.map { it.subjectType }.distinct().singleOrNull()) {
            "task" -> TAB_TASKS
            "inventory_item" -> TAB_INVENTORY
            "bill" -> TAB_BILLS
            else -> TAB_NOTIFICATIONS
        }

    private fun show(context: Context, showing: List<Notification>, fresh: List<Notification>, silent: Boolean) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Household reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tasks ready for you, overdue tasks, low stock and bills coming due"
            },
        )
        val lines = showing.map { it.reminderLine() }
        val title = when {
            showing.size == 1 -> showing.first().reminderLine()
            !silent && fresh.size == 1 -> fresh.first().reminderLine()
            else -> "${showing.size} household reminders"
        }
        val text = if (showing.size == 1) showing.first().body.orEmpty() else lines.joinToString(" · ")
        val style = if (showing.size == 1) {
            NotificationCompat.BigTextStyle().bigText(text)
        } else {
            NotificationCompat.InboxStyle().also { s ->
                lines.take(5).forEach(s::addLine)
                if (lines.size > 5) s.setSummaryText("+${lines.size - 5} more")
            }
        }
        val tab = tabFor(showing)
        val icon = when (tab) {
            TAB_INVENTORY -> R.drawable.ic_inventory
            TAB_BILLS -> R.drawable.ic_receipt
            else -> R.drawable.ic_request
        }
        val open = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java)
                .putExtra(RequestReminders.EXTRA_OPEN_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setNumber(showing.size)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent)
            // Item names and bills stay off the lock screen.
            .lockScreenSafe(context, CHANNEL_ID, "Household reminder")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }
}
