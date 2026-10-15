package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantAlertsTest {

    private fun parse(vararg lines: String): List<Pair<String?, String>> {
        val events = mutableListOf<Pair<String?, String>>()
        val parser = SseParser { event, data -> events += event to data }
        lines.forEach(parser::line)
        return events
    }

    @Test
    fun `reads the server's stream as it writes it, skipping keep-alives`() {
        val events = parse(
            "event: hello",
            "data: {}",
            "",
            ": ping",
            "",
            """data: {"type":"ring","ringerId":"alex","id":"a1"}""",
            "",
        )
        assertEquals(listOf<Pair<String?, String>>("hello" to "{}", null to """{"type":"ring","ringerId":"alex","id":"a1"}"""), events)
    }

    @Test
    fun `joins multi-line data, and waits for the blank line that ends an event`() {
        assertEquals(listOf<Pair<String?, String>>(null to "a\nb"), parse("data: a", "data:b", "retry: 5000", ""))
        assertTrue(parse("data: unfinished").isEmpty())
    }

    @Test
    fun `turns a message into the same fields a Firebase message has`() {
        assertEquals(
            mapOf("type" to "sync", "reason" to "request", "id" to "x1"),
            parseStreamMessage("""{"type":"sync","reason":"request","id":"x1"}"""),
        )
        // Anything but a JSON object is ignored, not a crash.
        assertNull(parseStreamMessage("not json"))
        assertNull(parseStreamMessage("[1,2]"))
        // Only text fields are kept.
        assertEquals(mapOf("type" to "test"), parseStreamMessage("""{"type":"test","nested":{"a":1}}"""))
    }

    @Test
    fun `reads whether the phone must stay connected, and an older server's answer as no`() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(CheckInResult.serializer(), """{"checkedIn":true,"stayConnectedRequired":true}""").stayConnectedRequired)
        assertEquals(false, json.decodeFromString(CheckInResult.serializer(), """{"checkedIn":true}""").stayConnectedRequired)
    }

    @Test
    fun `says what happened to a test notification`() {
        val json = Json { ignoreUnknownKeys = true }
        val sent = json.decodeFromString(TestPushResult.serializer(), """{"reached":1,"firebase":true,"connected":false,"tooSoon":false}""")
        assertTrue(sent.message.startsWith("Sent."))
        assertTrue(TestPushResult(tooSoon = true).message.startsWith("A test was just sent"))
        assertTrue(TestPushResult(firebase = false).message.contains("no Firebase"))
        assertTrue(TestPushResult(firebase = true).message.startsWith("Couldn't reach this phone"))
    }
}
