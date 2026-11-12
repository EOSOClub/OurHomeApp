package com.eosoclub.ourhome.ui

import com.eosoclub.ourhome.data.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/** Home's event day label, and the bell keeping a tapped notification read. */
class HomeAndBellLabelsTest {
    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun anEventToComeIsListedUnderItsDay() {
        assertEquals("Today", eventDayLabel(today, null, today))
        assertEquals("Tomorrow", eventDayLabel(today.plusDays(1), null, today))
        assertEquals("Nov 14", eventDayLabel(today.plusDays(3), today.plusDays(4), today))
    }

    @Test
    fun anEventStillGoingOnSaysUntilWhenNotOverdue() {
        // A trip that began on the 9th: it used to read "Overdue · Nov 9".
        assertEquals("Until Nov 13", eventDayLabel(today.minusDays(2), today.plusDays(2), today))
        assertEquals("Until today", eventDayLabel(today.minusDays(1), today, today))
        assertEquals("Until tomorrow", eventDayLabel(today.minusDays(1), today.plusDays(1), today))
        // No end to go by: the day it began, as the website shows it.
        assertEquals("Nov 10", eventDayLabel(today.minusDays(1), null, today))
    }

    private fun note(id: String, read: Boolean) =
        Notification(id = id, type = "reminder", title = id, read = read, createdAt = "2026-11-11T15:00:00.000Z")

    @Test
    fun aTappedNotificationStaysReadWhenAnOlderListArrives() {
        // The list was fetched while the "read" for b was still on its way.
        val fetched = listOf(note("a", false), note("b", false), note("c", true))
        val (items, unread) = withReadHere(fetched, 2, setOf("b"))
        assertEquals(listOf(false, true, true), items.map { it.read })
        assertEquals(1, unread)
    }

    @Test
    fun aListTheServerAlreadyHasRightIsLeftAlone() {
        val fetched = listOf(note("a", false), note("b", true))
        val (items, unread) = withReadHere(fetched, 1, setOf("b", "gone"))
        assertSame(fetched, items)
        assertEquals(1, unread)
    }
}
