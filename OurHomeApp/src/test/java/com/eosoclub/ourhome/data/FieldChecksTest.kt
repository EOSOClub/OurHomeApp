package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldChecksTest {

    @Test
    fun `a username with a space says why, in the server's words`() {
        assertEquals("Use only letters, numbers and . _ - (no spaces).", usernameProblem("mia m"))
        assertEquals("At least 3 characters.", usernameProblem("mi"))
        assertEquals("At most 30 characters.", usernameProblem("a".repeat(31)))
        assertNull(usernameProblem("mia.m_2-x"))
        // Spaces around it are trimmed, as the server does.
        assertNull(usernameProblem("  mia  "))
        // Empty isn't marked until it's needed.
        assertNull(usernameProblem(""))
    }

    @Test
    fun `email and password say why`() {
        assertEquals("Enter a valid email address, like name@example.com.", emailProblem("nope"))
        assertNull(emailProblem(""))
        assertNull(emailProblem("mia@example.com"))
        assertEquals("At least 8 characters.", passwordProblem("short"))
        assertNull(passwordProblem("long enough"))
        assertNull(passwordProblem(""))
    }

    @Test
    fun `reads the server's per-field errors, first one each`() {
        val details = Json.parseToJsonElement(
            """{"formErrors":[],"fieldErrors":{"username":["Use only letters, numbers and . _ - (no spaces).","x"],"email":[]}}""",
        )
        assertEquals(mapOf("username" to "Use only letters, numbers and . _ - (no spaces)."), fieldErrorsOf(details))
        assertEquals(emptyMap<String, String>(), fieldErrorsOf(null))
        assertEquals(emptyMap<String, String>(), fieldErrorsOf(Json.parseToJsonElement("""{"code":"household_disabled"}""")))
    }

    @Test
    fun `who supervises a child, for their row`() {
        val supervision = Supervision(
            supervisors = listOf(SupervisionPerson("a", "Alex", "head"), SupervisionPerson("s", "Sam", "manager")),
            supervised = listOf(SupervisedPerson("m", "Mia", "child", listOf("a", "s")), SupervisedPerson("r", "Riley", "teen")),
        )
        assertEquals("Supervised by Alex, Sam", supervision.supervisedBy("m"))
        assertEquals("Nobody supervises them yet", supervision.supervisedBy("r"))
        assertNull(supervision.supervisedBy("a"))
    }

    @Test
    fun `only children and teens are supervised, by adults and up`() {
        assertTrue(canBeSupervised("child"))
        assertTrue(canBeSupervised("teen"))
        assertFalse(canBeSupervised("adult"))
        assertTrue(canSupervise("head"))
        assertTrue(canSupervise("adult"))
        assertFalse(canSupervise("teen"))
        assertFalse(canSupervise("guest"))
    }
}
