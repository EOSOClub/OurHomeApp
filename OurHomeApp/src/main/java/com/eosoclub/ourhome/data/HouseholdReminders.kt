package com.eosoclub.ourhome.data

/**
 * The bell notifications the web's reminder sweep generates (its
 * `MANAGED_TYPES`): overdue tasks, low or soon-depleted stock, and bills
 * coming due — plus "task_ready" (a task became this user's to do: assigned,
 * their turn in a rotation, or a new cycle). The server decides who sees which (the people tied to the
 * task/bill/item plus that page's managers) and tracks read state per person,
 * so the app alerts on exactly what the user's bell returns.
 */
val HOUSEHOLD_REMINDER_TYPES = setOf("overdue", "low_inventory", "reminder", "bill_due", "task_ready")

/**
 * What the phone should do with the current unread reminders.
 * [showing] is everything still unread (fresh ones first), [alert] is the part
 * not alerted before, and [remember] is the id set to store for next time.
 */
data class ReminderAlertPlan(
    val showing: List<Notification>,
    val alert: List<Notification>,
    val remember: Set<String>,
)

/**
 * Alerts once per notification id. The server deletes a reminder when its
 * condition clears and creates a new row (new id) if it comes back, so a
 * recurrence alerts again while a lingering one stays quiet.
 *
 * [alerted] is null on the first run after install/upgrade: everything already
 * unread is remembered without alerting, so the feature doesn't open with a
 * burst of old reminders. [complete] is false when the server's page was
 * truncated; then ids missing from it may still exist, so they're kept.
 */
fun planReminderAlert(
    unread: List<Notification>,
    alerted: Set<String>?,
    complete: Boolean = true,
): ReminderAlertPlan {
    val reminders = unread.filter { it.type in HOUSEHOLD_REMINDER_TYPES && !it.read }
    val ids = reminders.map { it.id }.toSet()
    if (alerted == null) return ReminderAlertPlan(reminders, emptyList(), ids)
    val fresh = reminders.filter { it.id !in alerted }
    val remember = if (complete) ids else alerted + ids
    return ReminderAlertPlan(fresh + reminders.filter { it.id in alerted }, fresh, remember)
}

/** Short prefix for a reminder line, e.g. "Overdue: Mow the lawn". */
fun Notification.reminderLine(): String {
    val label = when (type) {
        "overdue" -> "Overdue"
        "low_inventory" -> "Low"
        "reminder" -> "Running out"
        "bill_due" -> "Bill"
        "task_ready" -> "Your turn"
        else -> null
    }
    return if (label == null) title else "$label: $title"
}
