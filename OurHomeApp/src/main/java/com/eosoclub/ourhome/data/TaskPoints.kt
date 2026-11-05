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
//   points; typing points turns it off. Changing points never changes time. A
//   step whose points follow time is always worth its minutes at the task rate.
// - A task with steps shows live totals: the sum of its steps.
// - "Steps share the task's time" (per task, on for new tasks): while the task
//   has a time it is held. The steps not set by hand split what the hand-set
//   ones leave; editing a step's time takes the difference from the other
//   steps or gives it back (the ones not set by hand first), and adding or
//   removing a step re-splits it the same way. Off: a step edit changes only
//   that step, so the live totals float.
// - Editing a task total redistributes that dimension over the steps in
//   proportion to their time; overwriting a hand-set step value needs
//   confirmation, and confirming clears the customisation it overwrites.
// - Steps not sharing time: adding/removing a step re-splits the base while
//   nothing is customised; otherwise totals float (a new step gets the average
//   time).

const val DEFAULT_MINUTES_PER_POINT = 10.0

data class TaskBase(
    val baseMinutes: Int?,
    val basePointsCenti: Int?,
    val pointsFollowTime: Boolean = true,
    /** Steps share the task's time (held total); false = the steps add up. */
    val stepsShareTime: Boolean = false,
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

/** The task's time is held: steps share it (it needs a time above zero). */
fun holdsTime(task: TaskBase): Boolean = task.stepsShareTime && (task.baseMinutes ?: 0) > 0

/** Points per minute (hundredths) of a task that holds its time. */
private fun heldRate(task: TaskBase): Double = (task.basePointsCenti ?: 0).toDouble() / (task.baseMinutes ?: 1)

/** Every step whose points follow time gets its minutes at [rateCenti], split together so they add up exactly. */
private fun followPoints(steps: List<StepValues>, rateCenti: Double): List<StepValues> {
    val following = steps.indices.filter { steps[it].pointsFollowTime }
    if (following.isEmpty()) return steps
    val minutes = following.map { steps[it].minutes }
    val parts = distribute(jsRound(rateCenti * minutes.sum()), minutes.map { it.toDouble() })
    val out = steps.toMutableList()
    following.forEachIndexed { k, i -> out[i] = steps[i].copy(pointsCenti = parts[k]) }
    return out
}

/** Gives [pool] minutes to the steps at [takers], in proportion to their time. */
private fun shareOut(steps: List<StepValues>, takers: List<Int>, pool: Int): List<StepValues> {
    val parts = distribute(pool, takers.map { steps[it].minutes.toDouble() })
    val out = steps.toMutableList()
    takers.forEachIndexed { k, i -> out[i] = steps[i].copy(minutes = parts[k]) }
    return out
}

/** Of the steps at [indices], those that take up time changes: the ones not set by hand, or all when every one was. */
private fun takersOf(steps: List<StepValues>, indices: List<Int>): List<Int> =
    indices.filter { !steps[it].minutesCustom }.ifEmpty { indices }

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

/**
 * Turn the task's "Points follow time" on (household rate) or off (keep points).
 * On gives every step its own time's worth; it only asks first when a step's
 * points were set by hand.
 */
fun setTaskFollow(state: PointsState, on: Boolean, minutesPerPoint: Double, confirm: Boolean = false): EditResult {
    if (!on) return EditResult(state.copy(task = state.task.copy(pointsFollowTime = false)), false)
    val task = state.task.copy(
        pointsFollowTime = true,
        basePointsCenti = pointsForMinutesCenti(state.task.baseMinutes ?: 0, minutesPerPoint),
    )
    val steps = state.steps
    if (steps.isEmpty()) return EditResult(PointsState(task, steps), false)
    if (steps.any { !it.pointsFollowTime } && !confirm) return EditResult(state, true)
    val following = steps.map { it.copy(pointsFollowTime = true) }
    return EditResult(PointsState(task, followPoints(following, taskRateCenti(task, minutesPerPoint))), false)
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
    // Only a hand-set value in a dimension being rescaled needs an OK.
    val overwrites = steps.any { (minutesDim && it.minutesCustom) || (pointsDim && !it.pointsFollowTime) }
    if (overwrites && !confirm) return EditResult(state, true)
    val minutes = if (minutesDim) distribute(task.baseMinutes ?: 0, steps.map { it.minutes.toDouble() }) else steps.map { it.minutes }
    // Points split by time. A task holding its time pays its rate for the time
    // on steps; otherwise exactly the total.
    val pointsTotal = if (holdsTime(task)) jsRound(heldRate(task) * minutes.sum()) else task.basePointsCenti ?: 0
    val points = if (pointsDim) distribute(pointsTotal, minutes.map { it.toDouble() }) else steps.map { it.pointsCenti }
    return EditResult(
        PointsState(
            task,
            steps.mapIndexed { i, s ->
                s.copy(
                    minutes = minutes[i],
                    pointsCenti = points[i],
                    minutesCustom = !minutesDim && s.minutesCustom,
                    pointsFollowTime = pointsDim || s.pointsFollowTime,
                )
            },
        ),
        false,
    )
}

/**
 * Hand-set one step's TTC; its points follow at the task rate unless hand-set.
 * Steps sharing a held time: the other steps make room or take back the
 * difference (the ones not set by hand first, all of them when every one
 * was); the step can't take more than the task's time minus what the hand-set
 * steps that don't move keep. Otherwise the other steps and the base stay.
 */
fun setStepMinutes(state: PointsState, index: Int, minutes: Int, minutesPerPoint: Double): PointsState {
    val (task, steps) = state
    if (!holdsTime(task)) {
        return mapStep(state, index) { s ->
            s.copy(
                minutes = minutes,
                minutesCustom = true,
                pointsCenti = if (s.pointsFollowTime) stepPointsFromTime(task, minutes, minutesPerPoint) else s.pointsCenti,
            )
        }
    }
    val others = steps.indices.filter { it != index }
    val takers = takersOf(steps, others)
    val kept = others.filter { it !in takers }.sumOf { steps[it].minutes }
    val room = maxOf(0, (task.baseMinutes ?: 0) - kept)
    val value = minOf(maxOf(0, minutes), room)
    var next = steps.mapIndexed { i, s -> if (i == index) s.copy(minutes = value, minutesCustom = true) else s }
    if (takers.isNotEmpty()) next = shareOut(next, takers, room - value)
    return PointsState(task, followPoints(next, heldRate(task)))
}

/** Hand-set one step's points; its time is untouched. */
fun setStepPoints(state: PointsState, index: Int, pointsCenti: Int): PointsState =
    mapStep(state, index) { it.copy(pointsCenti = pointsCenti, pointsFollowTime = false) }

/** Turn a step's "Points follow time" on (recalculate from its time) or off. */
fun setStepFollow(state: PointsState, index: Int, on: Boolean, minutesPerPoint: Double): PointsState {
    if (on && holdsTime(state.task)) {
        val steps = state.steps.mapIndexed { i, s -> if (i == index) s.copy(pointsFollowTime = true) else s }
        return state.copy(steps = followPoints(steps, heldRate(state.task)))
    }
    return mapStep(state, index) { s ->
        if (on) s.copy(pointsFollowTime = true, pointsCenti = stepPointsFromTime(state.task, s.minutes, minutesPerPoint))
        else s.copy(pointsFollowTime = false)
    }
}

/**
 * Turn "Steps share the task's time" on or off. On holds what the task shows
 * now: steps that had drifted from the set time make their sum the task's
 * time (and their points its points, when those are hand-set).
 */
fun setStepsShareTime(state: PointsState, on: Boolean, minutesPerPoint: Double): PointsState {
    var task = state.task.copy(stepsShareTime = on)
    val live = liveTotals(state)
    if (!on || state.steps.isEmpty() || live.minutes == 0 || live.minutes == task.baseMinutes) return state.copy(task = task)
    task = task.copy(
        baseMinutes = live.minutes,
        basePointsCenti = if (task.pointsFollowTime) pointsForMinutesCenti(live.minutes, minutesPerPoint) else live.pointsCenti,
    )
    return state.copy(task = task)
}

private fun mapStep(state: PointsState, index: Int, fn: (StepValues) -> StepValues): PointsState =
    state.copy(steps = state.steps.mapIndexed { i, s -> if (i == index) fn(s) else s })

/**
 * Append a step: the first takes the whole base. Steps sharing a held time:
 * it joins the steps not set by hand, weighted like an average one, and they
 * split what the hand-set steps leave. Otherwise, while nothing is customised
 * the base is re-split; once something is, totals float and it gets the
 * average time, with its points following that time.
 */
fun addStep(state: PointsState, minutesPerPoint: Double): PointsState {
    val (task, steps) = state
    if (steps.isEmpty()) return PointsState(task, listOf(StepValues(task.baseMinutes ?: 0, task.basePointsCenti ?: 0)))
    if (holdsTime(task)) {
        val free = steps.indices.filter { !steps[it].minutesCustom }
        val kept = steps.filter { it.minutesCustom }.sumOf { it.minutes }
        val average = if (free.isNotEmpty()) free.sumOf { steps[it].minutes }.toDouble() / free.size else 1.0
        val parts = distribute(maxOf(0, (task.baseMinutes ?: 0) - kept), free.map { steps[it].minutes.toDouble() } + average)
        val next = steps.toMutableList()
        free.forEachIndexed { k, i -> next[i] = steps[i].copy(minutes = parts[k]) }
        next += StepValues(parts.last(), 0)
        return PointsState(task, followPoints(next, heldRate(task)))
    }
    val avgMinutes = steps.sumOf { it.minutes }.toDouble() / steps.size
    val avgPoints = steps.sumOf { it.pointsCenti }.toDouble() / steps.size
    if (customisedCount(steps) > 0) {
        val minutes = jsRound(avgMinutes)
        return PointsState(task, steps + StepValues(minutes, stepPointsFromTime(task, minutes, minutesPerPoint)))
    }
    val minutes = distribute(task.baseMinutes ?: 0, steps.map { it.minutes.toDouble() } + avgMinutes)
    val points = distribute(task.basePointsCenti ?: 0, steps.map { it.pointsCenti.toDouble() } + avgPoints)
    return PointsState(
        task,
        steps.mapIndexed { i, s -> s.copy(minutes = minutes[i], pointsCenti = points[i]) } +
            StepValues(minutes[steps.size], points[steps.size]),
    )
}

/**
 * Remove a step. Steps sharing a held time: its time goes back to the steps
 * not set by hand (all of them when every one was). Otherwise its share goes
 * back to the others while nothing is customised; else totals shrink.
 */
fun removeStep(state: PointsState, index: Int): PointsState {
    val steps = state.steps.filterIndexed { i, _ -> i != index }
    if (steps.isNotEmpty() && holdsTime(state.task)) {
        val takers = takersOf(steps, steps.indices.toList())
        val kept = steps.indices.filter { it !in takers }.sumOf { steps[it].minutes }
        val shared = shareOut(steps, takers, maxOf(0, (state.task.baseMinutes ?: 0) - kept))
        return PointsState(state.task, followPoints(shared, heldRate(state.task)))
    }
    if (steps.isEmpty() || customisedCount(state.steps) > 0) return PointsState(state.task, steps)
    val minutes = distribute(state.task.baseMinutes ?: 0, steps.map { it.minutes.toDouble() })
    val points = distribute(state.task.basePointsCenti ?: 0, steps.map { it.pointsCenti.toDouble() })
    return PointsState(state.task, steps.mapIndexed { i, s -> s.copy(minutes = minutes[i], pointsCenti = points[i]) })
}

/** Points (2 decimals) as hundredths, for DTO values. */
fun Double.centi(): Int = toCenti(this)

/** Hundredths as a decimal for the API. */
fun Int.asPoints(): Double = this / 100.0
