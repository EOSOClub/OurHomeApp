package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

// Schedules (parental controls; web lib/appSchedules.ts, appScheduleService):
// a named time window on a child's phone, like Bedtime 21:00 to 07:00 every
// day, when every app is closed apart from the ones the phone always keeps
// open and the ones the schedule still allows. Times are minutes after
// midnight on this phone's clock; an end before the start runs past midnight,
// and the days (0 Sunday to 6 Saturday) are the days it starts on. The phone
// warns 5 minutes before the start (notifications/AppLimiter), then covers
// apps with the blocked screen. Pure parts live here.

/** Most schedules one child can have (web MAX_SCHEDULES). */
const val MAX_SCHEDULES = 10

/** Most apps a schedule still allows (web MAX_ALLOWED). */
const val MAX_ALLOWED = 30

/** The child is warned this long before a schedule starts. */
const val SCHEDULE_WARN_MS = 5 * 60_000L

/** Short day names, Sunday first (web SCHEDULE_DAYS). */
val SCHEDULE_DAYS = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
val WEEKDAYS = listOf(1, 2, 3, 4, 5)
val EVERY_DAY = listOf(0, 1, 2, 3, 4, 5, 6)

/** What a new schedule can start from (web SCHEDULE_PRESETS). */
data class SchedulePreset(val name: String, val days: List<Int>, val start: Int, val end: Int)

val SCHEDULE_PRESETS = listOf(
    SchedulePreset("Bedtime", EVERY_DAY, 21 * 60, 7 * 60),
    SchedulePreset("School", WEEKDAYS, 8 * 60, 15 * 60),
    SchedulePreset("Homework", WEEKDAYS, 16 * 60, 18 * 60),
)

/** A schedule on this phone, from the check-in's answer. [by]: who saved it; null once they've left the household. */
@Serializable
data class AppScheduleRule(
    val id: String,
    val name: String,
    val days: List<Int>,
    val start: Int,
    val end: Int,
    val allowed: List<String> = emptyList(),
    val by: String? = null,
)

/** A date's day number: 0 Sunday to 6 Saturday. */
fun dayNumber(date: LocalDate): Int = date.dayOfWeek.value % 7

/** When this schedule is on at [now], the moment it ends; null when it isn't on. */
fun AppScheduleRule.endsAt(now: LocalDateTime): LocalDateTime? {
    val minute = now.hour * 60 + now.minute
    val today = now.toLocalDate()
    return if (start < end) {
        if (dayNumber(today) in days && minute in start until end) today.atStartOfDay().plusMinutes(end.toLong()) else null
    } else when {
        // Past midnight: it started this evening, or yesterday evening.
        dayNumber(today) in days && minute >= start -> today.plusDays(1).atStartOfDay().plusMinutes(end.toLong())
        dayNumber(today.minusDays(1)) in days && minute < end -> today.atStartOfDay().plusMinutes(end.toLong())
        else -> null
    }
}

/** The next time this schedule starts after [now]; null when it has no days. */
fun AppScheduleRule.nextStart(now: LocalDateTime): LocalDateTime? =
    (0L..7L).asSequence()
        .map { now.toLocalDate().plusDays(it) }
        .filter { dayNumber(it) in days }
        .map { it.atStartOfDay().plusMinutes(start.toLong()) }
        .firstOrNull { it.isAfter(now) }

/** A schedule that's on, and when it ends. */
data class ScheduleState(val rule: AppScheduleRule, val endsAt: LocalDateTime)

/**
 * The schedule closing [foreground] (the app on screen) at [now], the one
 * ending last when several are on; null when none does. Never for an app this
 * phone always keeps open ([alwaysAllowed]) or one the schedule still allows.
 */
fun scheduleFor(
    foreground: String?,
    now: LocalDateTime,
    schedules: List<AppScheduleRule>,
    alwaysAllowed: Set<String>,
): ScheduleState? {
    if (foreground.isNullOrEmpty() || foreground in alwaysAllowed) return null
    return schedules
        .filter { foreground !in it.allowed }
        .mapNotNull { rule -> rule.endsAt(now)?.let { ScheduleState(rule, it) } }
        .maxByOrNull { it.endsAt }
}

/** A schedule's next start. */
data class ScheduleStart(val rule: AppScheduleRule, val at: LocalDateTime) {
    /** Remembered once warned, so each start warns once. */
    val warnKey: String get() = "schedule:${rule.id}@$at"
}

/** What to do about the schedules now: warn about the next start, and when to look again. */
data class SchedulePlan(val warn: ScheduleStart?, val checkAgainInMs: Long?)

/**
 * [warned]: the warn keys of starts already warned about. Looks again when the
 * next warning is due, then at the start itself, so an app left open closes on time.
 */
fun schedulePlan(now: LocalDateTime, schedules: List<AppScheduleRule>, warned: Set<String>): SchedulePlan {
    val next = schedules.mapNotNull { rule -> rule.nextStart(now)?.let { ScheduleStart(rule, it) } }.minByOrNull { it.at }
        ?: return SchedulePlan(warn = null, checkAgainInMs = null)
    val until = Duration.between(now, next.at).toMillis()
    val done = next.warnKey in warned
    return when {
        !done && until <= SCHEDULE_WARN_MS -> SchedulePlan(warn = next, checkAgainInMs = until)
        !done -> SchedulePlan(warn = null, checkAgainInMs = until - SCHEDULE_WARN_MS)
        else -> SchedulePlan(warn = null, checkAgainInMs = until)
    }
}

/** "9:00 PM", or "21:00" on a phone set to a 24-hour clock. */
fun formatClock(minutes: Int, is24: Boolean = false): String {
    val h = (minutes / 60) % 24
    val m = (minutes % 60).toString().padStart(2, '0')
    return if (is24) "${h.toString().padStart(2, '0')}:$m" else "${if (h % 12 == 0) 12 else h % 12}:$m ${if (h < 12) "AM" else "PM"}"
}

/** "Every day", "Weekdays", "Weekends", or "Mon, Wed, Fri" (web daysLabel). */
fun daysLabel(days: List<Int>): String {
    return when (val set = days.toSortedSet()) {
        EVERY_DAY.toSet() -> "Every day"
        WEEKDAYS.toSet() -> "Weekdays"
        setOf(0, 6) -> "Weekends"
        else -> set.joinToString(", ") { DAY_NAMES[it] }
    }
}

/** "9:00 PM to 7:00 AM (next day)" (web scheduleTimes). */
fun scheduleTimes(start: Int, end: Int, is24: Boolean = false): String =
    "${formatClock(start, is24)} to ${formatClock(end, is24)}${if (end < start) " (next day)" else ""}"

/** The blocked screen's title while [rule] is on: "Bedtime until 7:00 AM". */
fun scheduleTitle(rule: AppScheduleRule, is24: Boolean = false): String = "${rule.name} until ${formatClock(rule.end, is24)}"

/** The blocked screen's line under it: who set the schedule and when apps open again. */
fun scheduleReason(rule: AppScheduleRule, is24: Boolean = false): String {
    val who = rule.by ?: "An adult who looks after you"
    return "$who set ${rule.name} from ${formatClock(rule.start, is24)} to ${formatClock(rule.end, is24)}. " +
        "Apps open again at ${formatClock(rule.end, is24)}. Calls and messages still work."
}

/** The warning notification's title and text before [rule] starts. */
fun scheduleWarning(rule: AppScheduleRule, is24: Boolean = false): Pair<String, String> =
    "${rule.name} starts at ${formatClock(rule.start, is24)}" to
        "Then apps close until ${formatClock(rule.end, is24)}. Calls and messages still work."

/** People you look after: a schedule on a child's phone, who saved it last and when. */
@Serializable
data class ScheduleEntry(
    val id: String,
    val name: String,
    val days: List<Int>,
    val start: Int,
    val end: Int,
    val allowed: List<LimitChoice> = emptyList(),
    val byName: String = "",
    val at: String = "",
)

/** The card's line: "Bedtime 9:00 PM to 7:00 AM", or "3 schedules" (web schedulesSummary). */
fun schedulesSummary(schedules: List<ScheduleEntry>): String? = when (schedules.size) {
    0 -> null
    1 -> schedules[0].let { "${it.name} ${formatClock(it.start)} to ${formatClock(it.end)}" }
    else -> "${schedules.size} schedules"
}
