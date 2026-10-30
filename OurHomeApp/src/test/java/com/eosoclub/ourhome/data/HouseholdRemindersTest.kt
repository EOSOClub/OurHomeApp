package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdRemindersTest {
    private fun n(id: String, type: String = "overdue", read: Boolean = false) =
        Notification(id = id, type = type, title = "T$id", read = read, createdAt = "2026-10-08T12:00:00Z")

    @Test
    fun firstRunRemembersWithoutAlerting() {
        val plan = planReminderAlert(listOf(n("a"), n("b", "bill_due")), alerted = null)
        assertTrue(plan.alert.isEmpty())
        assertEquals(setOf("a", "b"), plan.remember)
        assertEquals(2, plan.showing.size)
    }

    @Test
    fun alertsOnlyForUnseenIdsAndListsThemFirst() {
        val plan = planReminderAlert(listOf(n("old"), n("new", "low_inventory")), alerted = setOf("old"))
        assertEquals(listOf("new"), plan.alert.map { it.id })
        assertEquals(listOf("new", "old"), plan.showing.map { it.id })
    }

    @Test
    fun ignoresOtherTypesAndReadRows() {
        val plan = planReminderAlert(
            listOf(n("bug", "bug_report"), n("sys", "system"), n("r", "reminder", read = true)),
            alerted = emptySet(),
        )
        assertTrue(plan.showing.isEmpty())
        assertTrue(plan.alert.isEmpty())
    }

    @Test
    fun forgetsClearedIdsSoARecurrenceAlertsAgain() {
        val plan = planReminderAlert(listOf(n("b")), alerted = setOf("a", "b"))
        assertEquals(setOf("b"), plan.remember)
    }

    @Test
    fun keepsIdsWhenThePageWasTruncated() {
        val plan = planReminderAlert(listOf(n("b")), alerted = setOf("a", "b"), complete = false)
        assertEquals(setOf("a", "b"), plan.remember)
    }

    @Test
    fun reminderLineLabelsByType() {
        assertEquals("Bill: T1", n("1", "bill_due").reminderLine())
        assertEquals("Low: T2", n("2", "low_inventory").reminderLine())
        assertEquals("Your turn: T3", n("3", "task_ready").reminderLine())
        assertEquals("Coming up: T4", n("4", "event_reminder").reminderLine())
        assertEquals("Trial ending: T5", n("5", "subscription_trial").reminderLine())
    }

    @Test
    fun taskReadyAlertsLikeOtherReminders() {
        val plan = planReminderAlert(listOf(n("1", "task_ready"), n("2", "bug_report")), alerted = emptySet())
        assertEquals(listOf("1"), plan.alert.map { it.id })
    }

    @Test
    fun requestAnswerAlertsWithItsOwnTitle() {
        val answer = Notification(
            id = "1", type = "request_update", title = "Approved: LEGO set", read = false, createdAt = "2026-10-23T12:00:00Z",
        )
        val plan = planReminderAlert(listOf(answer), alerted = emptySet())
        assertEquals(listOf("1"), plan.alert.map { it.id })
        // The outcome leads the title, so no label goes in front.
        assertEquals("Approved: LEGO set", answer.reminderLine())
    }

    @Test
    fun taskChecksAlertWithTheirOwnTitles() {
        val sent = Notification(
            id = "1", type = "task_review", title = "Riley sent “Dishes” for a check", subjectType = "task",
            subjectId = "t1", read = false, createdAt = "2026-10-29T12:00:00Z",
        )
        val answer = Notification(
            id = "2", type = "task_reviewed", title = "Approved: Dishes (+5 pts)", subjectType = "task",
            subjectId = "t1", read = false, createdAt = "2026-10-29T12:05:00Z",
        )
        val plan = planReminderAlert(listOf(sent, answer), alerted = emptySet())
        assertEquals(listOf("1", "2"), plan.alert.map { it.id })
        // Whatever the server's title says (task or steps, any outcome), it shows as it is.
        listOf(
            "Riley sent “Dishes” for a check",
            "Riley’s steps on “Dishes” need a check",
        ).forEach { assertEquals(it, sent.copy(title = it).reminderLine()) }
        listOf(
            "Approved: Dishes (+5 pts)",
            "Approved: steps on Dishes (+3 pts)",
            "Sent back: steps on Dishes",
            "Counted as done: Dishes",
            "Points taken back: Dishes",
        ).forEach { assertEquals(it, answer.copy(title = it).reminderLine()) }
    }
}
