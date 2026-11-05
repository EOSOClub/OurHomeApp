package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the web's src/lib/taskPoints.test.ts, so both sides agree. */
class TaskPointsTest {
    private val rate = 10.0

    private fun step(minutes: Int, centi: Int, custom: Boolean = false, follow: Boolean = true) =
        StepValues(minutes, centi, custom, follow)

    private fun kitchen() = PointsState(TaskBase(60, 600, true), listOf(step(20, 200), step(20, 200), step(20, 200)))

    @Test
    fun distributeSumsExactlyAndTiesGoLast() {
        assertEquals(listOf(333, 333, 334), distribute(1000, listOf(1.0, 1.0, 1.0)))
        assertEquals(listOf(339, 340), distribute(679, listOf(1.0, 1.0)))
        assertEquals(listOf(15, 30, 45), distribute(90, listOf(10.0, 20.0, 30.0)))
        assertEquals(listOf(5, 5), distribute(10, listOf(0.0, 0.0)))
    }

    @Test
    fun formatting() {
        assertEquals(679, toCenti(6.789))
        assertEquals("3.5", formatPoints(350))
        assertEquals("6.79", formatPoints(679))
        assertEquals("12", formatPoints(1200))
        assertEquals(450, pointsForMinutesCenti(45, rate))
    }

    @Test
    fun ttcDrivesPointsAndBothRedistribute() {
        val r = setTaskMinutes(kitchen(), 90, rate)
        assertFalse(r.needsConfirm)
        assertEquals(900, r.state.task.basePointsCenti)
        assertEquals(listOf(30, 30, 30), r.state.steps.map { it.minutes })
        assertEquals(listOf(300, 300, 300), r.state.steps.map { it.pointsCenti })
    }

    @Test
    fun handSetPointsNeverChangeTime() {
        val s = setTaskPoints(kitchen(), 1000).state
        assertEquals(TaskBase(60, 1000, false), s.task)
        assertEquals(listOf(20, 20, 20), s.steps.map { it.minutes })
        assertEquals(listOf(333, 333, 334), s.steps.map { it.pointsCenti })
        val shorter = setTaskMinutes(s, 30, rate).state
        assertEquals(1000, shorter.task.basePointsCenti)
        assertEquals(listOf(333, 333, 334), shorter.steps.map { it.pointsCenti })
        assertEquals(600, setTaskFollow(s, true, rate).state.task.basePointsCenti)
    }

    @Test
    fun customisedStepsNeedConfirmation() {
        val custom = setStepMinutes(kitchen(), 0, 40, rate)
        val ask = setTaskMinutes(custom, 120, rate)
        assertTrue(ask.needsConfirm)
        assertSame(custom, ask.state)
        val done = setTaskMinutes(custom, 120, rate, confirm = true).state
        assertEquals(listOf(60, 30, 30), done.steps.map { it.minutes })
        assertEquals(0, customisedCount(done.steps))
        assertEquals(Totals(120, 1200), liveTotals(done))
    }

    @Test
    fun stepEditsTouchOnlyThatStep() {
        val hand = setTaskPoints(kitchen(), 1200).state
        val s = setStepMinutes(hand, 1, 30, rate)
        assertEquals(step(30, 600, custom = true), s.steps[1])
        assertEquals(hand.steps[0], s.steps[0])
        assertEquals(hand.task, s.task)
        assertEquals(Totals(70, 1400), liveTotals(s))

        var p = setStepPoints(kitchen(), 0, 500)
        assertEquals(step(20, 500, follow = false), p.steps[0])
        p = setStepMinutes(p, 0, 25, rate)
        assertEquals(500, p.steps[0].pointsCenti)
        assertEquals(200, setStepFollow(setStepPoints(kitchen(), 2, 999), 2, true, rate).steps[2].pointsCenti)
    }

    @Test
    fun addingAndRemovingSteps() {
        val empty = PointsState(TaskBase(30, 300, true), emptyList())
        val one = addStep(empty, rate)
        assertEquals(listOf(step(30, 300)), one.steps)
        val two = addStep(one, rate)
        assertEquals(listOf(150, 150), two.steps.map { it.pointsCenti })

        val custom = setStepMinutes(kitchen(), 0, 40, rate)
        // The average time of the three, worth that time (not the average points).
        assertEquals(step(27, 270), addStep(custom, rate).steps[3])
        assertEquals(Totals(60, 600), liveTotals(removeStep(custom, 2)))
        assertEquals(listOf(30, 30), removeStep(kitchen(), 0).steps.map { it.minutes })
    }

    /** 30 min / 3 pts, three even steps sharing the time. */
    private fun shared() = PointsState(TaskBase(30, 300, true, stepsShareTime = true), listOf(step(10, 100), step(10, 100), step(10, 100)))

    private fun PointsState.minutes() = steps.map { it.minutes }

    @Test
    fun aStepEditMovesTheDifferenceAndTheTotalHolds() {
        val less = setStepMinutes(shared(), 1, 5, rate)
        assertEquals(listOf(12, 5, 13), less.minutes())
        assertTrue(less.steps[1].minutesCustom)
        assertEquals(shared().task, less.task)
        val back = setStepMinutes(less, 1, 10, rate)
        assertEquals(listOf(10, 10, 10), back.minutes())
        val more = setStepMinutes(back, 1, 20, rate)
        assertEquals(listOf(5, 20, 5), more.minutes())
        assertEquals(listOf(50, 200, 50), more.steps.map { it.pointsCenti })
        assertEquals(Totals(30, 300), liveTotals(more))
    }

    @Test
    fun twentyMinuteTaskKeepsTwenty() {
        var s = addStep(PointsState(TaskBase(20, 200, true, stepsShareTime = true), emptyList()), rate)
        s = setStepMinutes(s, 0, 10, rate)
        assertEquals(Totals(10, 100), liveTotals(s))
        s = addStep(s, rate)
        assertEquals(listOf(10, 10), s.minutes())
        s = setStepMinutes(s, 1, 15, rate)
        assertEquals(listOf(5, 15), s.minutes())
        assertEquals(Totals(20, 200), liveTotals(s))
    }

    @Test
    fun handSetStepsKeepTheirTime() {
        var s = setStepMinutes(shared(), 0, 5, rate)
        s = setStepMinutes(s, 1, 20, rate)
        assertEquals(listOf(5, 20, 5), s.minutes())
        s = setStepMinutes(s, 1, 40, rate)
        assertEquals(listOf(5, 25, 0), s.minutes())

        var all = setStepMinutes(setStepMinutes(shared(), 0, 5, rate), 1, 5, rate)
        all = setStepMinutes(all, 2, 10, rate)
        assertEquals(listOf(10, 10, 10), all.minutes())
    }

    @Test
    fun addingAndRemovingReSplit() {
        val added = addStep(setStepMinutes(shared(), 0, 6, rate), rate)
        assertEquals(listOf(6, 8, 8, 8), added.minutes())
        assertEquals(Totals(30, 300), liveTotals(added))
        val removed = removeStep(added, 1)
        assertEquals(listOf(6, 12, 12), removed.minutes())
        assertEquals(Totals(30, 300), liveTotals(removed))
        assertEquals(listOf(500, 200, 50), setStepMinutes(setStepPoints(shared(), 0, 500), 1, 20, rate).steps.map { it.pointsCenti })
    }

    @Test
    fun theSwitchHoldsWhatTheTaskShows() {
        val drifted = PointsState(TaskBase(20, 200, true), listOf(step(10, 100, custom = true), step(15, 150, custom = true)))
        val on = setStepsShareTime(drifted, true, rate)
        assertEquals(TaskBase(25, 250, true, stepsShareTime = true), on.task)
        assertEquals(drifted.steps, on.steps)
        val noTime = PointsState(TaskBase(null, null, true, stepsShareTime = true), listOf(step(0, 0), step(0, 0)))
        assertEquals(listOf(10, 0), setStepMinutes(noTime, 0, 10, rate).minutes())
    }

    @Test
    fun followBackOnGivesEachStepItsOwnTime() {
        // The reported bug: off then on made the 15 min step 1.2 pts.
        val drifted = PointsState(TaskBase(20, 200, true), listOf(step(10, 100, custom = true), step(15, 150, custom = true)))
        val on = setTaskFollow(setTaskFollow(drifted, false, rate).state, true, rate)
        assertFalse(on.needsConfirm)
        assertEquals(listOf(100, 150), on.state.steps.map { it.pointsCenti })
        assertEquals(150, setStepFollow(on.state, 1, true, rate).steps[1].pointsCenti)
        val hand = setStepPoints(drifted, 1, 500)
        assertTrue(setTaskFollow(setTaskFollow(hand, false, rate).state, true, rate).needsConfirm)
    }
}
