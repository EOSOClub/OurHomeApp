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
    }
}
