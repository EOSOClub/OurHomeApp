package com.eosoclub.ourhome.data

import java.util.Locale
import kotlin.math.floor

// Task time-to-complete (TTC) and points: a mirror of the web's
// src/lib/taskPoints.ts (the server applies the same rules, see the web's
// docs/points.md). Keep the two in step; TaskPointsTest uses the web's cases.
//
// Minutes are whole minutes; points are integer hundredths ("centi"), so
// 3.33 pts is 333. Splits use largest-remainder rounding, so the parts always
// add up to exactly the total.
//
// - A task has a base TTC and base points, set only by task-level edits; its
//   rate is base points ÷ base minutes (household rate without time). Step
//   edits never change the base, so nothing recalculates in a circle.
// - "Points follow time" (per task and per step): a TTC change recalculates
//   points; typing points turns it off. Changing points never changes time.
// - A task with steps shows live totals: the sum of its steps.
// - Editing a task total redistributes that dimension over the steps in
//   proportion; with a customised step that needs confirmation, and confirming
//   clears the customisation it overwrites.
// - Adding/removing a step re-splits the base while nothing is customised;
//   otherwise totals float (a new step gets the average).

const val DEFAULT_MINUTES_PER_POINT = 10.0

data class TaskBase(
    val baseMinutes: Int?,
    val basePointsCenti: Int?,
    val pointsFollowTime: Boolean = true,
)

data class StepValues(
    val minutes: Int,
    val pointsCenti: Int,
    /** The step's time was set by hand. */
    val minutesCustom: Boolean = false,
    /** False = the step's points were set by hand. */
    val pointsFollowTime: Boolean = true,
) {
    val customised: Boolean get() = minutesCustom || !pointsFollowTime
}

data class PointsState(val task: TaskBase, val steps: List<StepValues>)

/** [needsConfirm]: the edit would overwrite customised steps; [state] is unchanged. */
data class EditResult(val state: PointsState, val needsConfirm: Boolean)

data class Totals(val minutes: Int, val pointsCenti: Int)

/** Like JavaScript's Math.round (halves round up), so both sides agree. */
private fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

fun toCenti(points: Double): Int = jsRound(points * 100)

/** "3.5", "6.79", "12". */
fun formatPoints(centi: Int): String =
    if (centi % 100 == 0) (centi / 100).toString()
    else String.format(Locale.US, "%.2f", centi / 100.0).removeSuffix("0")

fun pointsForMinutesCenti(minutes: Int, minutesPerPoint: Double): Int =
    if (minutes <= 0 || minutesPerPoint <= 0) 0 else jsRound(minutes * 100 / minutesPerPoint)

/** Points-per-minute in hundredths: base points ÷ base minutes, else the household rate. */
fun taskRateCenti(task: TaskBase, minutesPerPoint: Double): Double {
    val minutes = task.baseMinutes
    val points = task.basePointsCenti
    if (minutes != null && minutes > 0 && points != null) return points.toDouble() / minutes
    return if (minutesPerPoint > 0) 100 / minutesPerPoint else 0.0
}

/** Splits [total] in proportion to [weights], summing exactly; all-zero weights split evenly. */
fun distribute(total: Int, weights: List<Double>): List<Int> {
    val n = weights.size
    if (n == 0) return emptyList()
    val safeTotal = maxOf(0, total)
    val clean = weights.map { if (it.isFinite() && it > 0) it else 0.0 }
    val sum = clean.sum()
    val shares = if (sum > 0) clean.map { safeTotal * it / sum } else clean.map { safeTotal.toDouble() / n }
    val parts = shares.map { floor(it).toInt() }.toMutableList()
    var left = safeTotal - parts.sum()
    // Leftover units go to the largest fractions; ties to the later step.
    val order = shares.indices.sortedWith(
        compareByDescending<Int> { shares[it] - floor(shares[it]) }.thenByDescending { it },
    )
    for (i in order) {
        if (left <= 0) break
        parts[i] += 1
        left -= 1
    }
    return parts
}

fun liveTotals(state: PointsState): Totals =
    if (state.steps.isEmpty()) {
        Totals(state.task.baseMinutes ?: 0, state.task.basePointsCenti ?: 0)
    } else {
        Totals(state.steps.sumOf { it.minutes }, state.steps.sumOf { it.pointsCenti })
    }

fun customisedCount(steps: List<StepValues>): Int = steps.count { it.customised }

private fun stepPointsFromTime(task: TaskBase, minutes: Int, minutesPerPoint: Double): Int =
    jsRound(taskRateCenti(task, minutesPerPoint) * minutes)

/** Set the task's TTC; with points following time they follow, and both redistribute. */
fun setTaskMinutes(state: PointsState, minutes: Int?, minutesPerPoint: Double, confirm: Boolean = false): EditResult {
    var task = state.task.copy(baseMinutes = minutes)
    if (task.pointsFollowTime) task = task.copy(basePointsCenti = pointsForMinutesCenti(minutes ?: 0, minutesPerPoint))
    return redistribute(state, task, minutesDim = true, pointsDim = task.pointsFollowTime, confirm = confirm)
}

/** Hand-set task points: they stop following time; TTC is untouched. */
fun setTaskPoints(state: PointsState, pointsCenti: Int?, confirm: Boolean = false): EditResult =
    redistribute(
        state,
        state.task.copy(basePointsCenti = pointsCenti, pointsFollowTime = false),
        minutesDim = false,
        pointsDim = true,
        confirm = confirm,
    )

/** Turn the task's "Points follow time" on (household rate) or off (keep points). */
fun setTaskFollow(state: PointsState, on: Boolean, minutesPerPoint: Double, confirm: Boolean = false): EditResult {
    if (!on) return EditResult(state.copy(task = state.task.copy(pointsFollowTime = false)), false)
    val task = state.task.copy(
        pointsFollowTime = true,
        basePointsCenti = pointsForMinutesCenti(state.task.baseMinutes ?: 0, minutesPerPoint),
    )
    return redistribute(state, task, minutesDim = false, pointsDim = true, confirm = confirm)
}

private fun redistribute(
    state: PointsState,
    task: TaskBase,
    minutesDim: Boolean,
    pointsDim: Boolean,
    confirm: Boolean,
): EditResult {
    val steps = state.steps
    if (steps.isEmpty()) return EditResult(PointsState(task, steps), false)
    if (customisedCount(steps) > 0 && !confirm) return EditResult(state, true)
    val minutes = if (minutesDim) distribute(task.baseMinutes ?: 0, steps.map { it.minutes.toDouble() }) else steps.map { it.minutes }
    val points = if (pointsDim) distribute(task.basePointsCenti ?: 0, steps.map { it.pointsCenti.toDouble() }) else steps.map { it.pointsCenti }
    return EditResult(
        PointsState(
            task,
            steps.mapIndexed { i, s ->
                s.copy(
                    minutes = minutes[i],
                    pointsCenti = points[i],
                    minutesCustom = if (minutesDim) false else s.minutesCustom,
                    pointsFollowTime = if (pointsDim) true else s.pointsFollowTime,
                )
            },
        ),
        false,
    )
}

/** Hand-set one step's TTC; its points follow at the task rate unless hand-set. */
fun setStepMinutes(state: PointsState, index: Int, minutes: Int, minutesPerPoint: Double): PointsState =
    mapStep(state, index) { s ->
        s.copy(
            minutes = minutes,
            minutesCustom = true,
            pointsCenti = if (s.pointsFollowTime) stepPointsFromTime(state.task, minutes, minutesPerPoint) else s.pointsCenti,
        )
    }

/** Hand-set one step's points; its time is untouched. */
fun setStepPoints(state: PointsState, index: Int, pointsCenti: Int): PointsState =
    mapStep(state, index) { it.copy(pointsCenti = pointsCenti, pointsFollowTime = false) }

/** Turn a step's "Points follow time" on (recalculate from its time) or off. */
fun setStepFollow(state: PointsState, index: Int, on: Boolean, minutesPerPoint: Double): PointsState =
    mapStep(state, index) { s ->
        if (on) s.copy(pointsFollowTime = true, pointsCenti = stepPointsFromTime(state.task, s.minutes, minutesPerPoint))
        else s.copy(pointsFollowTime = false)
    }

private fun mapStep(state: PointsState, index: Int, fn: (StepValues) -> StepValues): PointsState =
    state.copy(steps = state.steps.mapIndexed { i, s -> if (i == index) fn(s) else s })

/** Append a step: the first takes the whole base; while nothing is customised
 *  the base is re-split; otherwise totals float and it gets the average. */
fun addStep(state: PointsState): PointsState {
    val (task, steps) = state
    if (steps.isEmpty()) return PointsState(task, listOf(StepValues(task.baseMinutes ?: 0, task.basePointsCenti ?: 0)))
    val avgMinutes = steps.sumOf { it.minutes }.toDouble() / steps.size
    val avgPoints = steps.sumOf { it.pointsCenti }.toDouble() / steps.size
    if (customisedCount(steps) > 0) {
        return PointsState(task, steps + StepValues(jsRound(avgMinutes), jsRound(avgPoints)))
    }
    val minutes = distribute(task.baseMinutes ?: 0, steps.map { it.minutes.toDouble() } + avgMinutes)
    val points = distribute(task.basePointsCenti ?: 0, steps.map { it.pointsCenti.toDouble() } + avgPoints)
    return PointsState(
        task,
        steps.mapIndexed { i, s -> s.copy(minutes = minutes[i], pointsCenti = points[i]) } +
            StepValues(minutes[steps.size], points[steps.size]),
    )
}

/** Remove a step: its share goes back to the others while nothing is customised; otherwise totals shrink. */
fun removeStep(state: PointsState, index: Int): PointsState {
    val steps = state.steps.filterIndexed { i, _ -> i != index }
    if (steps.isEmpty() || customisedCount(state.steps) > 0) return PointsState(state.task, steps)
    val minutes = distribute(state.task.baseMinutes ?: 0, steps.map { it.minutes.toDouble() })
    val points = distribute(state.task.basePointsCenti ?: 0, steps.map { it.pointsCenti.toDouble() })
    return PointsState(state.task, steps.mapIndexed { i, s -> s.copy(minutes = minutes[i], pointsCenti = points[i]) })
}

/** Points (2 decimals) as hundredths, for DTO values. */
fun Double.centi(): Int = toCenti(this)

/** Hundredths as a decimal for the API. */
fun Int.asPoints(): Double = this / 100.0
