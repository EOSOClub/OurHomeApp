package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildSetupTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val phone = LookedAfterPhone(lastSeenAt = "2026-10-17T14:00:00Z", deviceName = "Pixel 8")

    private fun person(passwordChosen: Boolean, phone: LookedAfterPhone?, vararg steps: Pair<String, Boolean?>) =
        LookedAfter(
            id = "mia",
            name = "Mia",
            phone = phone,
            setup = LookedAfterSetup(passwordChosen, steps.map { (step, done) -> LookedAfterSetupStep(step, done) }),
        )

    @Test
    fun `the step keys are the web's (lib-phoneHealth PHONE_SETUP_STEPS), in its order`() {
        assertEquals(
            listOf(
                "notifications",
                "location",
                "batteryUnrestricted",
                "usageAccess",
                "webFilter",
                "preventUninstall",
                "keepRunning",
            ),
            SetupStep.entries.map { it.key },
        )
        assertEquals("Allow unrestricted battery", setupStepTitle("batteryUnrestricted"))
        // A step a newer server adds still shows, by its key.
        assertEquals("somethingNew", setupStepTitle("somethingNew"))
    }

    @Test
    fun `set up only when the password, the phone and every step are done`() {
        assertTrue(person(true, phone, "notifications" to true, "keepRunning" to true).setupComplete())
        assertFalse(person(false, phone, "notifications" to true).setupComplete())
        assertFalse(person(true, null).setupComplete())
        assertFalse(person(true, phone, "notifications" to true, "keepRunning" to false).setupComplete())
        // An app too old to say isn't counted as done.
        assertFalse(person(true, phone, "notifications" to null).setupComplete())
        // An older server sends no setup.
        assertFalse(LookedAfter(id = "mia", name = "Mia", phone = phone).setupComplete())
    }

    @Test
    fun `the folded line counts the steps done`() {
        assertEquals(
            "Set up this phone: all 2 done",
            person(true, phone, "notifications" to true, "keepRunning" to true).setupSummary(),
        )
        assertEquals(
            "Set up this phone: 1 of 3 done",
            person(true, phone, "notifications" to true, "preventUninstall" to false, "keepRunning" to null).setupSummary(),
        )
        assertEquals("Set up this phone: not started", person(false, null, "notifications" to false).setupSummary())
        assertNull(LookedAfter(id = "mia", name = "Mia").setupSummary())
    }

    @Test
    fun `notes under a step that isn't done`() {
        assertEquals(
            "Their app is too old to say. Update OurHome on their phone.",
            setupStepNote(LookedAfterSetupStep("notifications", null), hasPhone = true),
        )
        // Before their phone checks in, nothing is known yet: no note.
        assertNull(setupStepNote(LookedAfterSetupStep("notifications", null), hasPhone = false))
        assertEquals(
            "A parent does this one: it needs the parent PIN.",
            setupStepNote(LookedAfterSetupStep("preventUninstall", false), hasPhone = true),
        )
        assertNull(setupStepNote(LookedAfterSetupStep("preventUninstall", true), hasPhone = true))
        assertNull(setupStepNote(LookedAfterSetupStep("keepRunning", false), hasPhone = true))
    }

    @Test
    fun `a refused add goes back to the step with the field the server named`() {
        assertEquals(ChildSetupStep.Supervisors, stepForFieldErrors(setOf("supervisorIds", "username")))
        assertEquals(ChildSetupStep.Phone, stepForFieldErrors(setOf("setup")))
        assertEquals(ChildSetupStep.Account, stepForFieldErrors(setOf("username")))
        assertNull(stepForFieldErrors(emptySet()))
    }

    @Test
    fun `names joined for a sentence`() {
        assertEquals("", joinNames(emptyList(), "or"))
        assertEquals("Alex", joinNames(listOf("Alex"), "or"))
        assertEquals("Alex or Sam", joinNames(listOf("Alex", "Sam"), "or"))
        assertEquals("Alex, Sam and Jo", joinNames(listOf("Alex", "Sam", "Jo"), "and"))
    }

    @Test
    fun `a setup link fills in the username, and the server only when the app has none`() {
        // A fresh install with no server built in: the link's server is used.
        assertEquals(
            SetupPrefill("noah", "https://home.example.com", null),
            setupPrefill(" noah ", "https://home.example.com/", ""),
        )
        // The same server: nothing to change.
        assertEquals(SetupPrefill("noah", null, null), setupPrefill("noah", "https://HOME.example.com", "https://home.example.com/"))
        // Another server: never switched, and said why.
        val other = setupPrefill("noah", "https://elsewhere.example", "https://home.example.com")
        assertEquals(null, other?.server)
        assertTrue(other?.note.orEmpty().contains("https://elsewhere.example"))
        // Not a web address: ignored, the username still filled in.
        assertEquals(SetupPrefill("noah", null, null), setupPrefill("noah", "javascript:alert(1)", ""))
        assertEquals(SetupPrefill("noah", null, null), setupPrefill("noah", null, ""))
    }

    @Test
    fun `a setup link without a real username does nothing`() {
        assertNull(setupPrefill(null, "https://home.example.com", ""))
        assertNull(setupPrefill("  ", "https://home.example.com", ""))
        assertNull(setupPrefill("no spaces allowed", "https://home.example.com", ""))
        assertNull(setupPrefill("ab", "https://home.example.com", ""))
    }

    @Test
    fun `reads a setup link`() {
        val link = json.decodeFromString(
            SetupLink.serializer(),
            """{"url":"https://home.example.com/get-app/abc","expiresAt":"2026-10-18T20:00:00.000Z",""" +
                """"qr":{"size":2,"rows":["10","01"]}}""",
        )
        assertEquals("https://home.example.com/get-app/abc", link.url)
        assertEquals(SetupLinkQr(2, listOf("10", "01")), link.qr)
    }

    @Test
    fun `reads the server's setup progress and the built-in server address`() {
        val row = json.decodeFromString(
            LookedAfter.serializer(),
            """{"id":"mia","name":"Mia","setup":{"passwordChosen":true,"steps":[""" +
                """{"step":"notifications","done":true},{"step":"usageAccess","done":null}]}}""",
        )
        assertEquals(
            LookedAfterSetup(true, listOf(LookedAfterSetupStep("notifications", true), LookedAfterSetupStep("usageAccess", null))),
            row.setup,
        )
        val release = """{"versionName":"0.1","versionCode":3,"appId":"a","builtAt":"x","sizeBytes":1,"sha256":"s"}"""
        assertEquals("", json.decodeFromString(AppRelease.serializer(), release).server)
        assertEquals(
            "https://home.example",
            json.decodeFromString(AppRelease.serializer(), release.dropLast(1) + ""","server":"https://home.example"}""").server,
        )
    }
}
