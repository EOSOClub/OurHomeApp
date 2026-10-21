package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** A repeating task done for this occurrence waits for the next one to open (web Task.opensAt). */
class TaskWaitTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = Instant.parse("2026-10-21T15:00:00Z")

    private fun task(status: String = "pending", opensAt: String? = null) =
        Task(id = "t1", title = "Feed the cat", type = "recurring", priority = "medium", status = status, opensAt = opensAt)

    @Test
    fun waitsWhileTheNextOneHasNotOpened() {
        assertEquals(Instant.parse("2026-10-22T05:00:00Z"), task(opensAt = "2026-10-22T05:00:00.000Z").waitsUntil(now))
    }

    @Test
    fun openOnceThatTimeHasPassed() {
        assertNull(task(opensAt = "2026-10-21T05:00:00.000Z").waitsUntil(now))
    }

    @Test
    fun openWithoutOpensAtFromAnOlderServer() {
        assertNull(task().waitsUntil(now))
        val parsed = json.decodeFromString<Task>("""{"id":"t1","title":"x","type":"recurring","priority":"medium","status":"pending"}""")
        assertNull(parsed.waitsUntil(now))
    }

    @Test
    fun onlyOpenTasksWait() {
        assertNull(task(status = "archived", opensAt = "2026-10-22T05:00:00.000Z").waitsUntil(now))
        assertNull(task(status = "completed", opensAt = "2026-10-22T05:00:00.000Z").waitsUntil(now))
    }

    @Test
    fun readsTheServersField() {
        val parsed = json.decodeFromString<Task>(
            """{"id":"t1","title":"x","type":"recurring","priority":"medium","status":"pending","opensAt":"2026-10-22T05:00:00.000Z"}""",
        )
        assertEquals(Instant.parse("2026-10-22T05:00:00Z"), parsed.waitsUntil(now))
    }
}
