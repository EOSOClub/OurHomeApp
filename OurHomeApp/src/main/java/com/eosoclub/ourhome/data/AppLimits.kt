package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Daily limits (parental controls; web lib/appLimits.ts, appLimitService): a
// supervising adult gives one app, or all apps together, so many minutes a
// day on a child's phone. The phone learns them from its check-in
// (notifications/AppLimiter), counts today's minutes itself with Usage
// access, warns 5 minutes before, then covers the app with the blocked screen
// until midnight. Pure parts live here.

/** The app value meaning all apps together (web ALL_APPS). */
const val ALL_APPS = "*"

/** What the lists call all apps together (web ALL_APPS_LABEL). */
const val ALL_APPS_LABEL = "All apps"

/** The child is warned this long before a limit runs out. */
const val LIMIT_WARN_MS = 5 * 60_000L

/** The limits offered, in minutes (web LIMIT_CHOICES). */
val LIMIT_CHOICES = listOf(15, 30, 45, 60, 90, 120, 180, 240, 300, 360)

/** A daily limit on this phone, from the check-in's answer. [by]: who set it; null once they've left the household. */
@Serializable
data class AppLimitRule(val app: String, val label: String = app, val minutes: Int, val by: String? = null) {
    val isAllApps: Boolean get() = app == ALL_APPS
}

/** How much of [rule] is left today; none left once [leftMs] reaches 0. */
data class LimitState(val rule: AppLimitRule, val leftMs: Long) {
    val reached: Boolean get() = leftMs <= 0
}

/**
 * The limit closest to running out for [foreground] (the app on screen), or
 * null when none applies. Its own limit, and all apps together, which counts
 * every app in [usedMs] (today's time on screen) except the ones this phone
 * always keeps open. Never for an app it always keeps open ([alwaysAllowed]:
 * its phone, messages, home screen, Settings and OurHome).
 */
fun limitFor(
    foreground: String?,
    usedMs: Map<String, Long>,
    limits: List<AppLimitRule>,
    alwaysAllowed: Set<String>,
): LimitState? {
    if (foreground.isNullOrEmpty() || foreground in alwaysAllowed) return null
    val states = buildList {
        limits.firstOrNull { it.app == foreground }?.let { rule ->
            add(LimitState(rule, rule.minutes * 60_000L - (usedMs[foreground] ?: 0L)))
        }
        limits.firstOrNull { it.isAllApps }?.let { rule ->
            val used = usedMs.filterKeys { it !in alwaysAllowed }.values.sum()
            add(LimitState(rule, rule.minutes * 60_000L - used))
        }
    }
    return states.minByOrNull { it.leftMs }
}

/** What to do about a [LimitState] now: cover the app, warn, and when to look again (null: no need). */
data class LimitPlan(val cover: Boolean, val warn: Boolean, val checkAgainInMs: Long?)

/**
 * [warned]: the child was already warned about this limit today. Looks again
 * when the warning is due, then when the time runs out.
 */
fun limitPlan(state: LimitState?, warned: Boolean): LimitPlan {
    if (state == null) return LimitPlan(cover = false, warn = false, checkAgainInMs = null)
    if (state.reached) return LimitPlan(cover = true, warn = false, checkAgainInMs = null)
    val warnLater = !warned && state.leftMs > LIMIT_WARN_MS
    return LimitPlan(
        cover = false,
        warn = !warned && !warnLater,
        checkAgainInMs = if (warnLater) state.leftMs - LIMIT_WARN_MS else state.leftMs,
    )
}

/** "1 h a day" (web limitLabel). */
fun limitLabel(minutes: Int): String = "${formatMinutes(minutes)} a day"

/** The blocked screen's title once [rule] is used up. */
fun limitTitle(rule: AppLimitRule): String =
    if (rule.isAllApps) "Your app time is up for today" else "Time's up for ${rule.label} today"

/**
 * The blocked screen's line under it: who set the limit, any [extra] minutes
 * today (bought with points or given), and when it opens again.
 */
fun limitReason(rule: AppLimitRule, extra: Int = 0): String {
    val who = rule.by ?: "An adult who looks after you"
    val more = if (extra > 0) ", and you had ${formatMinutes(extra)} more today" else ""
    return if (rule.isAllApps) {
        "$who set ${limitLabel(rule.minutes)} for your apps$more. Calls and messages still work, and your apps open again tomorrow."
    } else {
        "$who set ${limitLabel(rule.minutes)} for ${rule.label}$more. It opens again tomorrow."
    }
}

/** The warning notification's title and text, 5 minutes before [rule] runs out. */
fun limitWarning(rule: AppLimitRule): Pair<String, String> =
    if (rule.isAllApps) {
        "5 minutes of app time left today" to "Then your apps close until tomorrow. Calls and messages still work."
    } else {
        "5 minutes left on ${rule.label} today" to "Then it closes until tomorrow."
    }

/** The check-in's word on the limits the phone covered an app for today ([day]: its own date). */
data class LimitsReached(val day: String, val apps: List<String>)

/** People you look after: a daily limit on a child's phone, who set it last and when. */
@Serializable
data class AppLimitEntry(
    val app: String,
    val label: String,
    val icon: Boolean = false,
    val minutes: Int,
    val byName: String = "",
    val at: String = "",
)

/** An app from a child's usage: what a limit or a schedule can name. */
@Serializable
data class LimitChoice(val app: String, val label: String, val icon: Boolean = false)

/**
 * People you look after: the limits on their phone ([total]: all apps
 * together), and whether Usage access is on there, which counts the minutes
 * ([counts]; null before a phone that can say checked in). Empty from older servers.
 */
@Serializable
data class LookedAfterLimits(
    val total: AppLimitEntry? = null,
    val apps: List<AppLimitEntry> = emptyList(),
    val counts: Boolean? = null,
) {
    val isEmpty: Boolean get() = total == null && apps.isEmpty()
}

/** [LIMIT_CHOICES], plus [current] in its place when it isn't one (web limitOptions). */
fun limitOptions(current: Int?): List<Int> =
    (if (current != null && current !in LIMIT_CHOICES) LIMIT_CHOICES + current else LIMIT_CHOICES).sorted()

/** The card's line: "3 h a day in apps", "YouTube limited", "3 h a day in apps, 2 app limits" (web limitsSummary). */
fun limitsSummary(limits: LookedAfterLimits): String? {
    val parts = buildList {
        limits.total?.let { add("${limitLabel(it.minutes)} in apps") }
        when (limits.apps.size) {
            0 -> Unit
            1 -> add("${limits.apps[0].label} limited")
            else -> add("${limits.apps.size} app limits")
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
