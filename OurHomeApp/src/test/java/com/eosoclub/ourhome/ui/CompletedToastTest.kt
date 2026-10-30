package com.eosoclub.ourhome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletedToastTest {
    @Test
    fun pointsHaveASpaceAfterTheDot() {
        assertEquals(" · 5 pts", completedPointsSuffix(5.0, showPoints = true))
        assertEquals(" · 1.33 pts", completedPointsSuffix(1.33, showPoints = true))
        assertEquals("Completed “Bring in the mail” · 1 pts", "Completed “Bring in the mail”" + completedPointsSuffix(1.0, showPoints = true))
    }

    @Test
    fun nothingAtZeroOrWithPointsOff() {
        assertEquals("", completedPointsSuffix(0.0, showPoints = true))
        assertEquals("", completedPointsSuffix(5.0, showPoints = false))
    }
}
