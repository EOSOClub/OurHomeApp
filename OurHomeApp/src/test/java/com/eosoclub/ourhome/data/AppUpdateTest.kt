package com.eosoclub.ourhome.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private val id = "com.example.ourhome"

    @Test
    fun newerBuildOfTheSameAppIsAnUpdate() {
        assertTrue(AppUpdate.isNewer(id, 29_860_000, id, 29_850_000))
    }

    @Test
    fun sameOrOlderBuildIsNot() {
        assertFalse(AppUpdate.isNewer(id, 29_850_000, id, 29_850_000))
        assertFalse(AppUpdate.isNewer(id, 29_840_000, id, 29_850_000))
    }

    @Test
    fun anotherAppIdIsNeverAnUpdate() {
        // e.g. a development build (com.eosoclub.ourhome, versionCode 1) and the
        // server's com.example.ourhome: a separate install, not an update.
        assertFalse(AppUpdate.isNewer(id, 29_860_000, "com.eosoclub.ourhome", 1))
    }

    private fun release(code: Long) =
        AppRelease(versionName = "v", versionCode = code, appId = id, builtAt = "2026-11-05T12:00:00Z", sizeBytes = 1, sha256 = "x")

    @Test
    fun whatsNewWaitsWhileThisPhoneIsBehind() {
        val mine = 29_850_000L
        // The new app is still being built, or built but not installed yet.
        assertTrue(AppUpdate.holdsWhatsNew(AppInfo(release(mine), building = true), id, mine))
        assertTrue(AppUpdate.holdsWhatsNew(AppInfo(release(29_860_000)), id, mine))
        // Up to date, no app on offer, or an older server: show it.
        assertFalse(AppUpdate.holdsWhatsNew(AppInfo(release(mine)), id, mine))
        assertFalse(AppUpdate.holdsWhatsNew(AppInfo(null), id, mine))
        assertFalse(AppUpdate.holdsWhatsNew(null, id, mine))
    }
}
