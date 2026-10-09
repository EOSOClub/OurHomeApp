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
        val one = addStep(empty)
        assertEquals(listOf(step(30, 300)), one.steps)
        val two = addStep(one)
        assertEquals(listOf(150, 150), two.steps.map { it.pointsCenti })

        val custom = setStepMinutes(kitchen(), 0, 40, rate)
        assertEquals(step(27, 267), addStep(custom).steps[3])
        assertEquals(Totals(60, 600), liveTotals(removeStep(custom, 2)))
        assertEquals(listOf(30, 30), removeStep(kitchen(), 0).steps.map { it.minutes })
    }
}
