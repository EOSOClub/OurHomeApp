package com.eosoclub.ourhome.data

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

// App usage (parental controls; web lib/appUsage.ts, appUsageService). While
// an adult who looks after this member has turned sharing it on, the phone
// measures how long each app was on screen (notifications/UsageReport) and
// sends today and yesterday every 15 minutes, and at once when a supervisor
// opens "People you look after". The member sees their own numbers too, on
// Profile. Pure parts live here.

/** GET /api/phone/usage: whether this member's app usage is shared. */
@Serializable
data class UsageSharing(val shared: Boolean = false)

/** POST /api/phone/usage: the reported apps the server has no icon for yet (an older server sends none). */
@Serializable
data class UsageReportResult(val missingIcons: List<String> = emptyList())

/** POST /api/supervision/usage/refresh: whether the child's phone was asked. */
@Serializable
data class UsageRefresh(val asked: Boolean = false)

/** Largest icon the server takes (web USAGE_ICON_MAX_BYTES). */
const val USAGE_ICON_MAX_BYTES = 32 * 1024

/** Icons per upload (web reportUsageIconsSchema). */
const val USAGE_ICONS_PER_UPLOAD = 20

/** Most new apps sent at once (web reportNewAppsSchema). */
const val NEW_APPS_PER_REPORT = 50

/**
 * The apps in [current] (package name to the name it shows) that weren't in
 * [known], the apps seen last time. Nothing when there was no last time: the
 * first look only remembers what's there, so turning sharing on (or a fresh
 * installation of OurHome) doesn't report every app as new.
 */
fun newApps(known: Set<String>?, current: Map<String, String>): Map<String, String> =
    if (known == null) emptyMap() else current.filterKeys { it !in known }

/** One app coming to the screen ([foreground]) or leaving it, from Android's usage events. */
data class UsageEvent(val app: String, val foreground: Boolean, val atMillis: Long)

/** One day as the phone reports it. [day]: "YYYY-MM-DD", the phone's own date. */
data class UsageDay(val day: String, val totalMinutes: Int, val apps: List<AppMinutes>)

data class AppMinutes(val app: String, val label: String, val minutes: Int)

/**
 * How long each app was on screen between [startMillis] and [endMillis], from
 * its events in that window (sorted by time). An app whose first event is
 * leaving the screen was already on it when the window began; one still on
 * screen at the end counts to the end.
 */
fun foregroundMillis(events: List<UsageEvent>, startMillis: Long, endMillis: Long): Map<String, Long> {
    val total = mutableMapOf<String, Long>()
    val openSince = mutableMapOf<String, Long>()
    val seen = mutableSetOf<String>()
    fun add(app: String, from: Long, to: Long) {
        val ms = (minOf(to, endMillis) - maxOf(from, startMillis)).coerceAtLeast(0)
        if (ms > 0) total[app] = (total[app] ?: 0) + ms
    }
    for (e in events.sortedBy { it.atMillis }) {
        if (e.atMillis > endMillis) break
        val first = seen.add(e.app)
        if (e.foreground) {
            if (e.app !in openSince) openSince[e.app] = maxOf(e.atMillis, startMillis)
        } else {
            val from = openSince.remove(e.app) ?: if (first) startMillis else continue
            add(e.app, from, e.atMillis)
        }
    }
    openSince.forEach { (app, from) -> add(app, from, endMillis) }
    return total
}

/** A measured day, apps most used first, with whole minutes (apps under a minute dropped). */
fun usageDay(day: String, millis: Map<String, Long>, label: (String) -> String): UsageDay {
    val apps = millis
        .map { (app, ms) -> AppMinutes(app, label(app), (ms / 60_000).toInt()) }
        .filter { it.minutes > 0 }
        .sortedWith(compareByDescending<AppMinutes> { it.minutes }.thenBy { it.label })
    val total = (millis.values.sum() / 60_000).toInt().coerceAtMost(1440)
    return UsageDay(day, total, apps.take(50))
}

/** "2 h 5 min", "45 min", "under a minute" (web: formatMinutes). */
fun formatMinutes(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    if (m == 0) return "under a minute"
    val h = m / 60
    val rest = m % 60
    return when {
        h == 0 -> "$rest min"
        rest == 0 -> "$h h"
        else -> "$h h $rest min"
    }
}

/** On "People you look after": whether their usage is shared, and the newest day reported. */
@Serializable
data class LookedAfterUsage(val shared: Boolean = false, val latest: UsageLatest? = null)

@Serializable
data class UsageLatest(
    val day: String,
    val totalMinutes: Int,
    val top: List<UsageTopApp> = emptyList(),
    val updatedAt: String,
) {
    /** "Today: 2 h 5 min"; the top apps are listed under it, each with its icon. */
    fun header(today: String): String = "${usageDayLabel(day, today)}: ${formatMinutes(totalMinutes)}"
}

private val usageShortDate = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)

/** "Today", "Yesterday", or a short date like "Oct 10" for [day], given [today] (web: usageDayLabel). */
fun usageDayLabel(day: String, today: String): String {
    if (day == today) return "Today"
    val date = runCatching { java.time.LocalDate.parse(day) }.getOrNull() ?: return day
    val now = runCatching { java.time.LocalDate.parse(today) }.getOrNull()
    if (now != null && date == now.minusDays(1)) return "Yesterday"
    return date.format(usageShortDate)
}

/** [app]: the package name (empty from an older server); [icon]: whether the server has its icon. */
@Serializable
data class UsageTopApp(val label: String, val minutes: Int, val app: String = "", val icon: Boolean = false)

/** Apps the server keeps per day (web USAGE_TOP_APPS). */
const val USAGE_TOP_APPS = 20

/**
 * GET /api/supervision/usage/history (a supervisor) or /api/phone/usage/history
 * (the member themself): every stored day, newest first, with all its apps.
 */
@Serializable
data class UsageHistory(val days: List<UsageHistoryDay> = emptyList())

@Serializable
data class UsageHistoryDay(
    val day: String,
    val totalMinutes: Int,
    val apps: List<UsageTopApp> = emptyList(),
    val updatedAt: String = "",
)

/** Days in the usage chart: a week, ending today (web USAGE_CHART_DAYS). */
const val USAGE_CHART_DAYS = 7

/** One bar of the chart; [minutes] is null when their phone sent nothing that day. */
data class ChartDay(val day: String, val minutes: Int?)

/**
 * The chart's days, oldest first: the week ending at the viewer's [today], or
 * at the newest reported day when that's later (a phone a time zone ahead).
 * Web: chartDays.
 */
fun chartDays(days: List<UsageHistoryDay>, today: String): List<ChartDay> {
    val byDay = days.associate { it.day to it.totalMinutes }
    val newest = days.map { it.day }.plus(today).max()
    val end = runCatching { java.time.LocalDate.parse(newest) }.getOrNull() ?: return emptyList()
    return (USAGE_CHART_DAYS - 1 downTo 0).map { back ->
        val day = end.minusDays(back.toLong()).toString()
        ChartDay(day, byDay[day])
    }
}

/** The average day over the chart days their phone reported, in whole minutes; null when none (web: averageMinutes). */
fun averageMinutes(chart: List<ChartDay>): Int? {
    val reported = chart.mapNotNull { it.minutes }
    if (reported.isEmpty()) return null
    return (reported.sum().toDouble() / reported.size).roundToInt()
}

/** A chart bar's short day name: "Mon" (web: weekdayLabel). */
fun weekdayLabel(day: String): String =
    runCatching {
        java.time.LocalDate.parse(day).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
    }.getOrDefault(day)
