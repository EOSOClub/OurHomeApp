package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun olderServerMeansEverythingOn() {
        assertEquals(Features.ALL, Features.from(null))
    }

    @Test
    fun onlyListedFeaturesAreOn() {
        val f = Features.from(listOf("tasks", "points", "calendar"))
        assertTrue(f.tasks)
        assertTrue(f.points)
        assertTrue(f.calendar)
        assertFalse(f.shopping)
        assertFalse(f.inventory)
        assertFalse(f.bills)
        assertFalse(f.requests)
    }

    @Test
    fun pointsFollowTasks() {
        assertFalse(Features.from(listOf("points", "shopping")).points)
    }

    @Test
    fun cachedNamesRoundTrip() {
        val f = Features.from(listOf("tasks", "bills"))
        assertEquals(f, Features.from(f.names()))
    }

    @Test
    fun myAccessReadsFeaturesWhenPresent() {
        val withFeatures = json.decodeFromString<MyAccess>(
            """{"userId":"u","role":"member","access":{},"features":["tasks","calendar"]}""",
        )
        assertEquals(listOf("tasks", "calendar"), withFeatures.features)
        val older = json.decodeFromString<MyAccess>("""{"userId":"u","role":"member","access":{}}""")
        assertNull(older.features)
    }
}
