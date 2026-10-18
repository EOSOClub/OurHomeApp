package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
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
        assertEquals("Today: 2 h 5 min", latest.header("2026-10-12"))
        assertEquals("Yesterday: 2 h 5 min", latest.header("2026-10-13"))
        assertEquals("Oct 12: 2 h 5 min", latest.header("2026-10-14"))
        assertEquals("Yesterday", usageDayLabel("2026-09-30", "2026-10-01"))
    }

    // Same settings as ApiClient's.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `reads icons from a new server, and nothing missing from an old one`() {
        val top = json.decodeFromString(
            UsageTopApp.serializer(),
            """{"app":"com.roblox.client","label":"Roblox","minutes":30,"icon":true}""",
        )
        assertEquals(UsageTopApp("Roblox", 30, app = "com.roblox.client", icon = true), top)
        // A server from before icons: no package name, no icon.
        assertEquals(UsageTopApp("Roblox", 30), json.decodeFromString(UsageTopApp.serializer(), """{"label":"Roblox","minutes":30}"""))

        assertEquals(
            listOf("com.roblox.client"),
            json.decodeFromString(UsageReportResult.serializer(), """{"saved":true,"missingIcons":["com.roblox.client"]}""").missingIcons,
        )
        assertEquals(emptyList<String>(), json.decodeFromString(UsageReportResult.serializer(), """{"saved":true}""").missingIcons)
    }

    @Test
    fun `new apps are the ones not seen last time, and the first look reports none`() {
        val now = mapOf("com.roblox.client" to "Roblox", "com.google.android.youtube" to "YouTube")
        assertEquals(emptyMap<String, String>(), newApps(null, now))
        assertEquals(mapOf("com.roblox.client" to "Roblox"), newApps(setOf("com.google.android.youtube", "com.gone"), now))
        assertEquals(emptyMap<String, String>(), newApps(now.keys, now))
        // The supervisor's phone alerts on them with the household's other alerts.
        assertEquals(true, "new_app" in HOUSEHOLD_REMINDER_TYPES)
    }

    private fun day(day: String, minutes: Int) = UsageHistoryDay(day, minutes)

    @Test
    fun `the chart shows the seven days up to today, with nothing for days the phone didn't send`() {
        val chart = chartDays(listOf(day("2026-10-18", 90), day("2026-10-16", 0), day("2026-10-05", 300)), "2026-10-18")
        assertEquals((12..18).map { "2026-10-$it" }, chart.map { it.day })
        // A reported zero is a day too; a missing day isn't.
        assertEquals(listOf(null, null, null, null, 0, null, 90), chart.map { it.minutes })
        assertEquals(45, averageMinutes(chart))
        assertEquals(null, averageMinutes(chart.take(3)))
    }

    @Test
    fun `the chart ends at the phone's day when it is already tomorrow for the viewer`() {
        val chart = chartDays(listOf(day("2026-11-01", 10)), "2026-10-31")
        assertEquals("2026-10-26", chart.first().day)
        assertEquals(ChartDay("2026-11-01", 10), chart.last())
        assertEquals("Sun", weekdayLabel("2026-10-18"))
        assertEquals("Mon", weekdayLabel("2026-10-12"))
    }

    @Test
    fun `reads a usage history with every app`() {
        val history = json.decodeFromString(
            UsageHistory.serializer(),
            """{"days":[{"day":"2026-10-12","totalMinutes":40,"updatedAt":"2026-10-12T17:00:00Z",
                "apps":[{"app":"com.roblox.client","label":"Roblox","minutes":30,"icon":true}]}]}""",
        )
        assertEquals(
            UsageHistoryDay("2026-10-12", 40, listOf(UsageTopApp("Roblox", 30, "com.roblox.client", true)), "2026-10-12T17:00:00Z"),
            history.days.single(),
        )
    }
}
