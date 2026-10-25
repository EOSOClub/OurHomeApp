package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class AppSchedulesTest {
    private val yt = "com.google.android.youtube"
    private val maps = "com.google.android.apps.maps"
    private val allowed = setOf("com.google.android.dialer", "com.eosoclub.ourhome")
    private val min = 60_000L

    // 2026-10-24 is a Saturday (6), 10-25 a Sunday (0), 10-26 a Monday (1).
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    /** Friday and Saturday nights, 21:00 to 07:00. */
    private val bedtime = AppScheduleRule("b", "Bedtime", listOf(5, 6), 21 * 60, 7 * 60, allowed = listOf(maps), by = "Alex")

    /** Weekdays 08:00 to 15:00. */
    private val school = AppScheduleRule("s", "School", WEEKDAYS, 8 * 60, 15 * 60)

    @Test
    fun `a window past midnight is on from its start day's evening to the next morning`() {
        assertEquals(at(25, 7), bedtime.endsAt(at(24, 21)))
        assertEquals(at(25, 7), bedtime.endsAt(at(25, 6, 59)))
        assertNull(bedtime.endsAt(at(25, 7)))
        assertNull(bedtime.endsAt(at(24, 20, 59)))
        // Sunday evening isn't one of its days.
        assertNull(bedtime.endsAt(at(25, 22)))
        // Saturday morning: it started Friday evening.
        assertEquals(at(24, 7), bedtime.endsAt(at(24, 3)))
    }

    @Test
    fun `a daytime window is on only on its days, start included and end not`() {
        assertEquals(at(26, 15), school.endsAt(at(26, 8)))
        assertNull(school.endsAt(at(26, 15)))
        assertNull(school.endsAt(at(25, 10)))
    }

    @Test
    fun `closes every app but the allowed and always-open ones, naming the one ending last`() {
        val night = at(24, 23)
        assertEquals(ScheduleState(bedtime, at(25, 7)), scheduleFor(yt, night, listOf(bedtime, school), allowed))
        assertNull(scheduleFor(maps, night, listOf(bedtime), allowed))
        assertNull(scheduleFor("com.google.android.dialer", night, listOf(bedtime), allowed))
        assertNull(scheduleFor(yt, at(24, 12), listOf(bedtime, school), allowed))
        val late = school.copy(id = "l", name = "Late", end = 16 * 60)
        assertEquals(ScheduleState(late, at(26, 16)), scheduleFor(yt, at(26, 10), listOf(school, late), allowed))
        // Maps is allowed in Bedtime but not School: School still closes it.
        val both = school.copy(days = EVERY_DAY, start = 22 * 60, end = 23 * 60 + 30)
        assertEquals(ScheduleState(both, at(24, 23, 30)), scheduleFor(maps, night, listOf(bedtime, both), allowed))
    }

    @Test
    fun `warns 5 minutes before the next start, once, and looks again at the start`() {
        // Saturday 20:00: Bedtime starts at 21:00.
        assertEquals(SchedulePlan(null, 55 * min), schedulePlan(at(24, 20), listOf(bedtime, school), emptySet()))
        val start = ScheduleStart(bedtime, at(24, 21))
        assertEquals(SchedulePlan(start, 3 * min), schedulePlan(at(24, 20, 57), listOf(bedtime), emptySet()))
        assertEquals(SchedulePlan(null, 3 * min), schedulePlan(at(24, 20, 57), listOf(bedtime), setOf(start.warnKey)))
        // Sunday noon: next is School on Monday at 8.
        assertEquals(at(26, 8), school.nextStart(at(25, 12)))
        assertEquals(SchedulePlan(null, null), schedulePlan(at(25, 12), emptyList(), emptySet()))
    }

    @Test
    fun `words times, days and the screens`() {
        assertEquals("9:00 PM", formatClock(21 * 60))
        assertEquals("12:05 AM", formatClock(5))
        assertEquals("21:00", formatClock(21 * 60, is24 = true))
        assertEquals("Every day", daysLabel(listOf(6, 5, 4, 3, 2, 1, 0)))
        assertEquals("Weekdays", daysLabel(WEEKDAYS))
        assertEquals("Weekends", daysLabel(listOf(6, 0)))
        assertEquals("Fri, Sat", daysLabel(listOf(6, 5)))
        assertEquals("9:00 PM to 7:00 AM (next day)", scheduleTimes(21 * 60, 7 * 60))
        assertEquals("Bedtime until 7:00 AM", scheduleTitle(bedtime))
        assertEquals(
            "Alex set Bedtime from 9:00 PM to 7:00 AM. Apps open again at 7:00 AM. Calls and messages still work.",
            scheduleReason(bedtime),
        )
        assertEquals("Bedtime starts at 9:00 PM", scheduleWarning(bedtime).first)
        val entry = ScheduleEntry("b", "Bedtime", EVERY_DAY, 21 * 60, 7 * 60)
        assertNull(schedulesSummary(emptyList()))
        assertEquals("Bedtime 9:00 PM to 7:00 AM", schedulesSummary(listOf(entry)))
        assertEquals("2 schedules", schedulesSummary(listOf(entry, entry.copy(id = "x"))))
    }
}
