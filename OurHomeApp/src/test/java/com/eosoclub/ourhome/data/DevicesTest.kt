package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A child's devices on People you look after, what a quiet one last said, and
// the server admin's flag on a member (web 2026-11-08). Older servers send
// none of them, so each has a default.
class DevicesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a phone from an older server has no devices and nothing last known`() {
        val phone = json.decodeFromString(LookedAfterPhone.serializer(), """{"lastSeenAt":"2026-11-08T12:00:00Z","problems":["quiet"]}""")
        assertEquals(emptyList<LookedAfterDevice>(), phone.devices)
        assertEquals(emptyList<String>(), phone.lastKnown)
        assertTrue(phone.anyProblem)
    }

    @Test
    fun `each device is read with its own problems, and any of them makes the card red`() {
        val phone = json.decodeFromString(
            LookedAfterPhone.serializer(),
            """
            {"lastSeenAt":"2026-11-08T12:00:00Z","deviceName":"Tablet","problems":[],"lastKnown":[],
             "devices":[
               {"installId":"tab","deviceName":"Tablet","lastSeenAt":"2026-11-08T12:00:00Z","problems":[],"lastKnown":[]},
               {"installId":"ph","deviceName":"Pixel","lastSeenAt":"2026-11-07T20:00:00Z","problems":["quiet"],"lastKnown":["notifications_off"]}
             ]}
            """.trimIndent(),
        )
        assertEquals(listOf("tab", "ph"), phone.devices.map { it.installId })
        // The freshest one is fine, but the quiet phone still counts.
        assertEquals(null, phone.problemText)
        assertTrue(phone.anyProblem)
        assertEquals("Notifications turned off on their phone", phoneProblemText(phone.devices[1].lastKnown))
    }

    @Test
    fun `no problems on any device, nothing red`() {
        val phone = LookedAfterPhone(
            "x",
            devices = listOf(LookedAfterDevice(installId = "a", lastSeenAt = "x")),
        )
        assertFalse(phone.anyProblem)
        assertEquals(null, phoneProblemText(emptyList()))
    }

    @Test
    fun `a member says whether they run the server, false from an older server`() {
        val older = json.decodeFromString(MemberAccount.serializer(), """{"id":"a","name":"Alex","email":"a@x","role":"manager"}""")
        assertFalse(older.runsServer)
        val admin = json.decodeFromString(MemberAccount.serializer(), """{"id":"a","name":"Alex","email":"a@x","role":"manager","runsServer":true}""")
        assertTrue(admin.runsServer)
    }

    @Test
    fun `a task says whether it's all day, null from an older server`() {
        val base = """{"id":"t","title":"Lunch","type":"one_time","priority":"medium","status":"pending","dueDate":"2026-11-08T18:00:00Z""""
        assertEquals(null, json.decodeFromString(Task.serializer(), "$base}").dueAllDay)
        assertEquals(false, json.decodeFromString(Task.serializer(), "$base,\"dueAllDay\":false}").dueAllDay)
    }
}
