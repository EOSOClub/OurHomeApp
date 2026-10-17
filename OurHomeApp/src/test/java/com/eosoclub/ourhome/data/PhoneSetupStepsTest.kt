package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupStepsTest {

    private val nothingOn = PhoneSetupChecks(
        notifications = false,
        locationAlways = false,
        batteryUnrestricted = false,
        usageAccess = false,
        preventUninstall = false,
        keepRunning = false,
    )
    private val everything = PhoneSetupRequired(
        notifications = true,
        location = true,
        usageAccess = true,
        keepRunning = true,
        preventUninstall = true,
        batteryUnrestricted = true,
    )

    @Test
    fun `every step, in the order it has to be done, Keep Our Home running last`() {
        assertEquals(
            listOf(
                SetupStep.Notifications,
                SetupStep.Location,
                SetupStep.Battery,
                SetupStep.UsageAccess,
                SetupStep.PreventUninstall,
                SetupStep.KeepRunning,
            ),
            setupSteps(everything, nothingOn).map { it.step },
        )
    }

    @Test
    fun `only the steps asked for, each done or not from the phone`() {
        val required = PhoneSetupRequired(notifications = true, keepRunning = true)
        val steps = setupSteps(required, nothingOn.copy(notifications = true))
        assertEquals(
            listOf(SetupStepState(SetupStep.Notifications, true), SetupStepState(SetupStep.KeepRunning, false)),
            steps,
        )
        assertEquals(emptyList<SetupStepState>(), setupSteps(PhoneSetupRequired(), nothingOn))
    }

    @Test
    fun `restricted settings hint only where Android restricts it`() {
        // Not installed from a file: never.
        assertFalse(mayBeRestricted(SetupStep.KeepRunning, restrictedInstall = false, sdk = 35))
        // Accessibility from Android 13.
        assertFalse(mayBeRestricted(SetupStep.KeepRunning, restrictedInstall = true, sdk = 32))
        assertTrue(mayBeRestricted(SetupStep.KeepRunning, restrictedInstall = true, sdk = 33))
        // Usage access and Device admin from Android 15.
        assertFalse(mayBeRestricted(SetupStep.UsageAccess, restrictedInstall = true, sdk = 34))
        assertTrue(mayBeRestricted(SetupStep.UsageAccess, restrictedInstall = true, sdk = 35))
        assertTrue(mayBeRestricted(SetupStep.PreventUninstall, restrictedInstall = true, sdk = 35))
        // The rest never.
        assertFalse(mayBeRestricted(SetupStep.Notifications, restrictedInstall = true, sdk = 36))
        assertFalse(mayBeRestricted(SetupStep.Battery, restrictedInstall = true, sdk = 36))
    }

    @Test
    fun `reads the server's setup answer, and an older server's look-after rows have no rules`() {
        val json = Json { ignoreUnknownKeys = true }
        val setup = json.decodeFromString(
            PhoneSetup.serializer(),
            """{"supervised":true,"required":{"notifications":true,"location":false,"usageAccess":true,""" +
                """"keepRunning":true,"preventUninstall":false,"batteryUnrestricted":true}}""",
        )
        assertTrue(setup.supervised)
        assertEquals(PhoneSetupRequired(true, false, true, true, false, true), setup.required)

        val old = json.decodeFromString(LookedAfter.serializer(), """{"id":"mia","name":"Mia"}""")
        assertEquals(null, old.rules)
        val now = json.decodeFromString(
            LookedAfter.serializer(),
            """{"id":"mia","name":"Mia","rules":{"keepRunning":true,"preventUninstall":false,"batteryUnrestricted":true}}""",
        )
        assertEquals(false, now.rules?.let { PhoneRule.PreventUninstall.of(it) })
        assertEquals(true, now.rules?.let { PhoneRule.KeepRunning.of(it) })
    }
}
