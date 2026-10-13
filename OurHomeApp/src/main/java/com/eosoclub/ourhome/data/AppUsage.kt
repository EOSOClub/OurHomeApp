package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// App usage (parental controls; web lib/appUsage.ts, appUsageService). While
// an adult who looks after this member has turned sharing it on, the phone
// measures how long each app was on screen (notifications/UsageReport) and
// sends today and yesterday with the hourly check. Pure parts live here.

/** GET /api/phone/usage: whether this member's app usage is shared. */
@Serializable
data class UsageSharing(val shared: Boolean = false)

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
    /** "Today: 2 h 5 min · YouTube 1 h 10 min, Roblox 30 min". */
    fun line(today: String): String {
        val head = "${usageDayLabel(day, today)}: ${formatMinutes(totalMinutes)}"
        return if (top.isEmpty()) head else head + " · " + top.joinToString(", ") { "${it.label} ${formatMinutes(it.minutes)}" }
    }
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

@Serializable
data class UsageTopApp(val label: String, val minutes: Int)
