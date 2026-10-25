package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable
import kotlin.math.ceil

// Points for screen time (parental controls; web lib/pointsTime.ts,
// pointsTimeService): a child spends task points on extra minutes for today,
// at the rate their supervising adult set, up to a daily cap; adults can also
// give minutes. The day's minutes go on top of every daily limit the phone
// has (notifications/AppLimiter); schedules aren't touched. Spending comes off
// their points balance (earned minus spent); what they earned, and the stats,
// stay as they were. Pure parts live here.

/** What one point can buy, in minutes (web RATE_CHOICES). */
val RATE_CHOICES = listOf(1, 2, 5, 10, 15, 30)

/** The most minutes a day they can buy (web CAP_CHOICES). */
val CAP_CHOICES = listOf(15, 30, 45, 60, 90, 120)

/** What a child is offered to buy, and an adult to give (web BUY_STEPS, GIVE_STEPS). */
val BUY_STEPS = listOf(15, 30, 60)
val GIVE_STEPS = listOf(15, 30, 60)

/** One day's extra minutes from the check-in's answer ([day]: the phone's date). */
@Serializable
data class ExtraTimeDay(val day: String, val minutes: Int)

/** What a point buys and the most a day, from the check-in's answer; null there when they can't buy time. */
@Serializable
data class PointsRule(val minutesPerPoint: Int, val dailyCap: Int)

/**
 * GET/POST /api/phone/extra-time: the member's own points for screen time.
 * [available]: they can buy minutes now; [minutesPerPoint] and [dailyCap] are
 * null while it's off; [balanceCenti]: points to spend, in hundredths;
 * [bought] and [given]: minutes on [day] (their phone's date).
 */
@Serializable
data class MyExtraTime(
    val available: Boolean = false,
    val hasLimits: Boolean = false,
    val minutesPerPoint: Int? = null,
    val dailyCap: Int? = null,
    val balanceCenti: Int = 0,
    val day: String = "",
    val bought: Int = 0,
    val given: Int = 0,
)

/** People you look after: who set a child's rate and when. */
@Serializable
data class PointsTimeRuleEntry(val minutesPerPoint: Int, val dailyCap: Int, val byName: String = "", val at: String = "")

/**
 * People you look after: points for screen time. [available]: the household
 * uses Points; [rule]: null when they can't buy time; [balanceCenti]: points
 * to spend; [bought] and [given]: minutes on [day] (the household's today).
 * Off from older servers.
 */
@Serializable
data class LookedAfterPointsTime(
    val available: Boolean = false,
    val rule: PointsTimeRuleEntry? = null,
    val balanceCenti: Int = 0,
    val day: String = "",
    val bought: Int = 0,
    val given: Int = 0,
)

/** The points (hundredths) [minutes] cost at [minutesPerPoint], rounded up (web costCenti). */
fun costCenti(minutes: Int, minutesPerPoint: Int): Int = ceil(minutes * 100.0 / minutesPerPoint).toInt()

/** "1 point = 5 min" (web rateLabel). */
fun rateLabel(minutesPerPoint: Int): String = "1 point = ${formatMinutes(minutesPerPoint)}"

/** "3 points", "1 point", "2.5 points". */
fun pointsLabel(centi: Int): String = "${formatPoints(centi)} ${if (centi == 100) "point" else "points"}"

data class BuyOption(val minutes: Int, val costCenti: Int)

/**
 * What a child can buy now: [BUY_STEPS] that fit what's left of today's cap
 * and their balance, plus the most they can afford when that's less than a
 * step (in 5-minute steps). Web buyOptions.
 */
fun buyOptions(balanceCenti: Int, minutesPerPoint: Int, dailyCap: Int, boughtToday: Int): List<BuyOption> {
    val left = (dailyCap - boughtToday).coerceAtLeast(0)
    val affordable = balanceCenti.coerceAtLeast(0) * minutesPerPoint / 100
    val most = minOf(left, affordable) / 5 * 5
    return (BUY_STEPS.filter { it <= most } + most).distinct().filter { it >= 5 }.sorted()
        .map { BuyOption(it, costCenti(it, minutesPerPoint)) }
}

/** The extra minutes for [day] (the phone's date). */
fun extraOn(days: List<ExtraTimeDay>, day: String): Int = days.filter { it.day == day }.sumOf { it.minutes }

/** Every limit made [extra] minutes longer: what today's time is checked against. */
fun withExtra(limits: List<AppLimitRule>, extra: Int): List<AppLimitRule> =
    if (extra <= 0) limits else limits.map { it.copy(minutes = it.minutes + extra) }
