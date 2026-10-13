package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUsageTest {
    private val min = 60_000L
    private val start = 1_000_000L * min
    private val end = start + 24 * 60 * min

    private fun on(app: String, atMin: Long) = UsageEvent(app, true, start + atMin * min)
    private fun off(app: String, atMin: Long) = UsageEvent(app, false, start + atMin * min)

    @Test
    fun `adds up each app's time on screen`() {
        val ms = foregroundMillis(
            listOf(on("yt", 10), off("yt", 40), on("chat", 40), off("chat", 45), on("yt", 60), off("yt", 75)),
            start,
            end,
        )
        assertEquals(45 * min, ms["yt"])
        assertEquals(5 * min, ms["chat"])
    }

    @Test
    fun `an app on screen at midnight counts from the start, one still open counts to the end`() {
        val ms = foregroundMillis(listOf(off("game", 20), on("game", 23 * 60 + 50)), start, end)
        assertEquals(30 * min, ms["game"])
    }

    @Test
    fun `a stray second pause is not counted twice`() {
        val ms = foregroundMillis(listOf(on("yt", 0), off("yt", 10), off("yt", 12)), start, end)
        assertEquals(10 * min, ms["yt"])
    }

    @Test
    fun `a second screen of the same app doesn't restart its clock`() {
        val ms = foregroundMillis(listOf(on("yt", 0), on("yt", 10), off("yt", 20)), start, end)
        assertEquals(20 * min, ms["yt"])
    }

    @Test
    fun `events out of order are sorted first, and later ones ignored`() {
        val ms = foregroundMillis(listOf(off("yt", 30), on("yt", 20), on("x", 24 * 60 + 5)), start, end)
        assertEquals(10 * min, ms["yt"])
        assertEquals(null, ms["x"])
    }

    @Test
    fun `a day lists apps most used first, without the ones under a minute`() {
        val day = usageDay("2026-10-12", mapOf("yt" to 70 * min, "chat" to 5 * min, "blip" to 30_000L)) { it.uppercase() }
        assertEquals(listOf("YT", "CHAT"), day.apps.map { it.label })
        assertEquals(75, day.totalMinutes)
    }

    @Test
    fun `minutes read the way people say them, and the card line marks today`() {
        assertEquals("under a minute", formatMinutes(0))
        assertEquals("45 min", formatMinutes(45))
        assertEquals("1 h", formatMinutes(60))
        assertEquals("2 h 5 min", formatMinutes(125))
        val latest = UsageLatest("2026-10-12", 125, listOf(UsageTopApp("YouTube", 70)), "2026-10-12T17:00:00Z")
        assertEquals("Today: 2 h 5 min · YouTube 1 h 10 min", latest.line("2026-10-12"))
        assertEquals("Yesterday: 2 h 5 min · YouTube 1 h 10 min", latest.line("2026-10-13"))
        assertEquals("Oct 12: 2 h 5 min · YouTube 1 h 10 min", latest.line("2026-10-14"))
        assertEquals("Yesterday", usageDayLabel("2026-09-30", "2026-10-01"))
    }
}
