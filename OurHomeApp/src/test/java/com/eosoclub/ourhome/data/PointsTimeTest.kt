package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PointsTimeTest {
    @Test
    fun `costs points by the rate, rounding up`() {
        assertEquals(300, costCenti(15, 5))
        assertEquals(334, costCenti(10, 3))
        assertEquals("1 point = 5 min", rateLabel(5))
        assertEquals("3 points", pointsLabel(300))
        assertEquals("1 point", pointsLabel(100))
        assertEquals("2.5 points", pointsLabel(250))
    }

    @Test
    fun `offers what fits the cap and the balance, like the web`() {
        assertEquals(listOf(15, 30, 60), buyOptions(2000, 5, 60, 0).map { it.minutes })
        assertEquals(listOf(15, 30, 45), buyOptions(2000, 5, 60, 15).map { it.minutes })
        assertEquals(listOf(BuyOption(10, 200)), buyOptions(250, 5, 60, 0))
        assertEquals(emptyList<BuyOption>(), buyOptions(50, 5, 60, 0))
        assertEquals(emptyList<BuyOption>(), buyOptions(2000, 5, 30, 30))
        assertEquals(emptyList<BuyOption>(), buyOptions(-300, 5, 30, 0))
    }

    @Test
    fun `adds the day's extra minutes to every limit`() {
        val days = listOf(ExtraTimeDay("2026-10-24", 15), ExtraTimeDay("2026-10-25", 30), ExtraTimeDay("2026-10-24", 10))
        assertEquals(25, extraOn(days, "2026-10-24"))
        assertEquals(0, extraOn(days, "2026-10-23"))
        val limits = listOf(AppLimitRule("yt", "YouTube", 60), AppLimitRule(ALL_APPS, ALL_APPS_LABEL, 180))
        assertEquals(listOf(85, 205), withExtra(limits, 25).map { it.minutes })
        assertEquals(limits, withExtra(limits, 0))
        // Extra time is what turns a used-up limit back into time left.
        val used = mapOf("yt" to 70 * 60_000L)
        assertEquals(15 * 60_000L, limitFor("yt", used, withExtra(limits, 25), emptySet())?.leftMs)
    }

    @Test
    fun `the blocked screen says when there was extra time`() {
        val yt = AppLimitRule("yt", "YouTube", 60, "Alex")
        assertEquals("Alex set 1 h a day for YouTube. It opens again tomorrow.", limitReason(yt))
        assertEquals(
            "Alex set 1 h a day for YouTube, and you had 15 min more today. It opens again tomorrow.",
            limitReason(yt, extra = 15),
        )
    }
}
