package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchTabTest {
    @Test
    fun aTapThatLaunchesTheAppOpensItsTabAndIsMarked() {
        assertEquals(LaunchTabDecision("tasks", 100L), launchTab("tasks", 100L, launchedFromHistory = false, lastHandledTapId = null))
    }

    @Test
    fun aNewTapAfterATapLaunchedProcessDiedIsHandled() {
        // Opened by tap 100, closed by Android, then tap 200 relaunches it over the saved screen.
        assertEquals(
            LaunchTabDecision("notifications", 200L),
            launchTab("notifications", 200L, launchedFromHistory = false, lastHandledTapId = 100L),
        )
    }

    @Test
    fun theSameTapReplayedIsSkipped() {
        // A rotation, or a restore after Android closed the app: the launch intent comes back.
        assertEquals(LaunchTabDecision(null, 100L), launchTab("tasks", 100L, launchedFromHistory = false, lastHandledTapId = 100L))
    }

    @Test
    fun aLaunchFromRecentsIsSkippedAndMarked() {
        assertEquals(LaunchTabDecision(null, 300L), launchTab("tasks", 300L, launchedFromHistory = true, lastHandledTapId = 100L))
        assertEquals(LaunchTabDecision(null, 300L), launchTab("tasks", 300L, launchedFromHistory = true, lastHandledTapId = null))
    }

    @Test
    fun aNotificationWithoutATapIdIsHandledOnceThenMarked() {
        val first = launchTab("tasks", null, launchedFromHistory = false, lastHandledTapId = null)
        assertEquals(LaunchTabDecision("tasks", NO_TAP_ID), first)
        // Replayed: skipped.
        assertEquals(LaunchTabDecision(null, NO_TAP_ID), launchTab("tasks", null, launchedFromHistory = false, lastHandledTapId = first.handledTapId))
        // A newer notification, with an id, still lands after that.
        assertEquals(LaunchTabDecision("tasks", 400L), launchTab("tasks", 400L, launchedFromHistory = false, lastHandledTapId = NO_TAP_ID))
    }

    @Test
    fun noTabKeepsWhatWasHandled() {
        assertEquals(LaunchTabDecision(null, 100L), launchTab(null, null, launchedFromHistory = false, lastHandledTapId = 100L))
        assertEquals(LaunchTabDecision(null, null), launchTab(null, 5L, launchedFromHistory = false, lastHandledTapId = null))
    }
}
