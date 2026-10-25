package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLimitsTest {
    private val yt = "com.google.android.youtube"
    private val roblox = "com.roblox.client"
    private val dialer = "com.google.android.dialer"
    private val allowed = setOf(dialer, "com.eosoclub.ourhome")
    private val min = 60_000L

    private val ytLimit = AppLimitRule(yt, "YouTube", 60, "Alex")
    private val total = AppLimitRule(ALL_APPS, ALL_APPS_LABEL, 120, null)

    @Test
    fun `an app's own limit counts only its own time`() {
        val state = limitFor(yt, mapOf(yt to 40 * min, roblox to 50 * min), listOf(ytLimit), allowed)
        assertEquals(LimitState(ytLimit, 20 * min), state)
        assertNull(limitFor(roblox, mapOf(roblox to 500 * min), listOf(ytLimit), allowed))
    }

    @Test
    fun `all apps together leave out what the phone always keeps open, and the nearer limit wins`() {
        val used = mapOf(yt to 30 * min, roblox to 70 * min, dialer to 600 * min)
        // 100 of 120 minutes used together; YouTube has 30 of 60 left.
        assertEquals(LimitState(total, 20 * min), limitFor(yt, used, listOf(ytLimit, total), allowed))
        assertEquals(LimitState(total, 20 * min), limitFor(roblox, used, listOf(ytLimit, total), allowed))
        // Calls never have a limit, however long.
        assertNull(limitFor(dialer, used, listOf(total), allowed))
        assertNull(limitFor(null, used, listOf(total), allowed))
        assertNull(limitFor(yt, used, emptyList(), allowed))
    }

    @Test
    fun `warns once 5 minutes before, then covers when the time is gone`() {
        // Plenty left: look again when the warning is due.
        assertEquals(LimitPlan(false, false, 15 * min), limitPlan(LimitState(ytLimit, 20 * min), warned = false))
        // Inside the last 5 minutes: warn now, look again when it runs out.
        assertEquals(LimitPlan(false, true, 3 * min), limitPlan(LimitState(ytLimit, 3 * min), warned = false))
        assertEquals(LimitPlan(false, false, 3 * min), limitPlan(LimitState(ytLimit, 3 * min), warned = true))
        // Already warned about this limit today: no second warning, look again at the end.
        assertEquals(LimitPlan(false, false, 20 * min), limitPlan(LimitState(ytLimit, 20 * min), warned = true))
        assertEquals(LimitPlan(true, false, null), limitPlan(LimitState(ytLimit, 0), warned = true))
        assertEquals(LimitPlan(true, false, null), limitPlan(LimitState(ytLimit, -5 * min), warned = false))
        assertEquals(LimitPlan(false, false, null), limitPlan(null, warned = false))
    }

    @Test
    fun `the blocked screen and the warning say which limit and who set it`() {
        assertEquals("Time's up for YouTube today", limitTitle(ytLimit))
        assertEquals("Alex set 1 h a day for YouTube. It opens again tomorrow.", limitReason(ytLimit))
        assertEquals("Your app time is up for today", limitTitle(total))
        assertEquals(
            "An adult who looks after you set 2 h a day for your apps. Calls and messages still work, and your apps open again tomorrow.",
            limitReason(total),
        )
        assertEquals("5 minutes left on YouTube today" to "Then it closes until tomorrow.", limitWarning(ytLimit))
        assertEquals("5 minutes of app time left today", limitWarning(total).first)
    }

    @Test
    fun `offers the usual choices and sums the limits up for the card`() {
        assertEquals(LIMIT_CHOICES, limitOptions(null))
        assertEquals(listOf(15, 30, 45, 50, 60, 90, 120, 180, 240, 300, 360), limitOptions(50))
        val entry = { label: String, minutes: Int -> AppLimitEntry("x.$label", label, minutes = minutes) }
        assertNull(limitsSummary(LookedAfterLimits()))
        assertEquals("3 h a day in apps", limitsSummary(LookedAfterLimits(total = entry(ALL_APPS_LABEL, 180))))
        assertEquals("YouTube limited", limitsSummary(LookedAfterLimits(apps = listOf(entry("YouTube", 60)))))
        assertEquals(
            "2 h a day in apps, 2 app limits",
            limitsSummary(LookedAfterLimits(total = entry(ALL_APPS_LABEL, 120), apps = listOf(entry("A", 5), entry("B", 9)))),
        )
    }
}
