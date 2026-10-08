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
}
