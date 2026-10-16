package com.eosoclub.ourhome.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseGuardTest {
    private val own = "com.eosoclub.ourhome"
    private val cls = "com.eosoclub.ourhome.notifications.CloseGuardService"

    @Test
    fun `finds our service in Android's list, written in full or short`() {
        assertTrue(CloseGuard.listsService("$own/$cls", own, cls))
        assertTrue(CloseGuard.listsService("com.other/.Svc:$own/.notifications.CloseGuardService", own, cls))
        assertFalse(CloseGuard.listsService("com.other/.Svc", own, cls))
        assertFalse(CloseGuard.listsService(null, own, cls))
        assertFalse(CloseGuard.listsService("", own, cls))
        assertFalse(CloseGuard.listsService("garbage", own, cls))
    }

    private fun close(pkg: String?, vararg texts: String) = CloseGuard.shouldClose(
        windowPackage = pkg,
        ownPackage = own,
        appName = "Our Home",
        guardName = "Keep Our Home running",
        forceStopLabels = setOf("Force stop", "Forcer l'arrêt"),
        adminName = "Keep Our Home on this phone",
        deactivateLabels = setOf("Deactivate this device admin app", "Désactiver"),
    ) { text -> texts.any { it.equals(text, ignoreCase = true) } }

    @Test
    fun `closes Our Home's App info, in any language Settings uses`() {
        assertTrue(close("com.android.settings", "App info", "Our Home", "Uninstall", "Force stop"))
        assertTrue(close("com.android.settings", "Our Home", "FORCE STOP"))
        assertTrue(close("com.android.settings", "Our Home", "Forcer l'arrêt"))
    }

    @Test
    fun `closes its own Accessibility page, from any app`() {
        assertTrue(close("com.android.settings", "Accessibility", "Keep Our Home running"))
        assertTrue(close("com.miui.securitycenter", "Keep Our Home running"))
    }

    @Test
    fun `closes Our Home's Device admin deactivation page`() {
        assertTrue(close("com.android.settings", "Device admin app", "Keep Our Home on this phone"))
        assertTrue(close("com.android.settings", "Our Home", "Deactivate this device admin app"))
        assertTrue(close("com.android.settings", "Our Home", "Désactiver"))
    }

    @Test
    fun `never closes the Activate device admin app prompt, so a parent can turn protection on`() {
        // The activate prompt shows the same admin label as the deactivate page,
        // plus an Activate button/header. It must stay open.
        assertFalse(close("com.android.settings", "Activate device admin app", "Keep Our Home on this phone"))
        assertFalse(close("com.android.settings", "Activate this device admin app", "Keep Our Home on this phone", "Our Home"))
    }

    @Test
    fun `closes Android's package installer uninstall prompt for Our Home, but never an app update`() {
        assertTrue(close("com.google.android.packageinstaller", "Our Home", "Do you want to uninstall this app?"))
        assertTrue(close("com.android.packageinstaller", "Our Home", "Uninstall", "Cancel"))
        // Self-hosted app updates (AppUpdate.install) and other apps are left alone.
        assertFalse(close("com.google.android.packageinstaller", "Our Home", "Do you want to update this app?", "Update"))
        assertFalse(close("com.google.android.packageinstaller", "Chrome", "Do you want to uninstall this app?"))
    }

    @Test
    fun `leaves other pages alone`() {
        // Another app's App info, the apps list, Our Home itself, a missing window.
        assertFalse(close("com.android.settings", "Chrome", "Force stop"))
        assertFalse(close("com.android.settings", "Our Home", "Chrome", "Maps"))
        assertFalse(close(own, "Our Home", "Force stop", "Keep Our Home running", "Keep Our Home on this phone"))
        assertFalse(close(null, "Our Home", "Force stop"))
    }

    @Test
    fun `validates and hashes parent PINs in constant time`() {
        assertTrue(CloseGuard.isValidPin("1234"))
        assertTrue(CloseGuard.isValidPin("12345678"))
        assertFalse(CloseGuard.isValidPin("123"))
        assertFalse(CloseGuard.isValidPin("123456789"))
        assertFalse(CloseGuard.isValidPin("12a4"))

        val h1 = CloseGuard.hashPin("1234", "salt-a")
        val h2 = CloseGuard.hashPin("1234", "salt-a")
        val hDifferentPin = CloseGuard.hashPin("9999", "salt-a")
        val hDifferentSalt = CloseGuard.hashPin("1234", "salt-b")
        assertTrue(CloseGuard.constantTimeEquals(h1, h2))
        assertFalse(CloseGuard.constantTimeEquals(h1, hDifferentPin))
        assertFalse(CloseGuard.constantTimeEquals(h1, hDifferentSalt))
    }

    @Test
    fun `5-hour emergency unlock notifies parents at start, 2 hours, 1 hour left, and when unlocked`() {
        val hour = 60 * 60_000L
        val d5h = CloseGuard.EmergencyDuration.Hours5
        org.junit.Assert.assertEquals(5 * hour, d5h.durationMs)
        org.junit.Assert.assertEquals(4, d5h.stages.size)

        val start = 1_000_000L
        // At start (0h): Started
        org.junit.Assert.assertEquals(
            listOf("started"),
            CloseGuard.dueEmergencyStages(start, start, emptySet(), d5h).map { it.wire },
        )
        // Before 2h: nothing new once Started was sent
        org.junit.Assert.assertEquals(
            emptyList<String>(),
            CloseGuard.dueEmergencyStages(start, start + 2 * hour - 1, setOf("started"), d5h).map { it.wire },
        )
        // At 2h (3h left): 2h_elapsed
        org.junit.Assert.assertEquals(
            listOf("2h_elapsed"),
            CloseGuard.dueEmergencyStages(start, start + 2 * hour, setOf("started"), d5h).map { it.wire },
        )
        // At 4h (1h before unlock): 1h_left
        org.junit.Assert.assertEquals(
            listOf("1h_left"),
            CloseGuard.dueEmergencyStages(start, start + 4 * hour, setOf("started", "2h_elapsed"), d5h).map { it.wire },
        )
        // At 5h (unlocked): unlocked
        org.junit.Assert.assertEquals(
            listOf("unlocked"),
            CloseGuard.dueEmergencyStages(start, start + 5 * hour, setOf("started", "2h_elapsed", "1h_left"), d5h).map { it.wire },
        )
    }

    @Test
    fun `configurable emergency unlock durations adjust notification count and frequency`() {
        val sec = 1_000L
        val min = 60 * sec
        val hour = 60 * min
        val start = 1_000_000L

        // 30 seconds: 2 notifications (start at 0s, unlocked at 30s)
        val d30s = CloseGuard.EmergencyDuration.Sec30
        org.junit.Assert.assertEquals(30 * sec, d30s.durationMs)
        org.junit.Assert.assertEquals(listOf("started", "unlocked"), d30s.stages.map { it.wire })
        org.junit.Assert.assertEquals(listOf(0L, 30 * sec), d30s.stages.map { it.offsetMs })
        org.junit.Assert.assertEquals(
            listOf("unlocked"),
            CloseGuard.dueEmergencyStages(start, start + 30 * sec, setOf("started"), d30s).map { it.wire },
        )

        // 60 seconds: 3 notifications (start at 0s, 30s left at 30s, unlocked at 60s)
        val d60s = CloseGuard.EmergencyDuration.Sec60
        org.junit.Assert.assertEquals(60 * sec, d60s.durationMs)
        org.junit.Assert.assertEquals(listOf("started", "30s_left", "unlocked"), d60s.stages.map { it.wire })
        org.junit.Assert.assertEquals(listOf(0L, 30 * sec, 60 * sec), d60s.stages.map { it.offsetMs })

        // 1 hour: 3 notifications (start at 0m, 30m left at 30m, unlocked at 60m)
        val d1h = CloseGuard.EmergencyDuration.Hours1
        org.junit.Assert.assertEquals(1 * hour, d1h.durationMs)
        org.junit.Assert.assertEquals(listOf("started", "30m_left", "unlocked"), d1h.stages.map { it.wire })
        org.junit.Assert.assertEquals(listOf(0L, 30 * min, 60 * min), d1h.stages.map { it.offsetMs })

        // 10 hours: 5 notifications (start at 0h, 3h elapsed, 6h elapsed, 1h left at 9h, unlocked at 10h)
        val d10h = CloseGuard.EmergencyDuration.Hours10
        org.junit.Assert.assertEquals(10 * hour, d10h.durationMs)
        org.junit.Assert.assertEquals(
            listOf("started", "3h_elapsed", "6h_elapsed", "1h_left", "unlocked"),
            d10h.stages.map { it.wire },
        )
        org.junit.Assert.assertEquals(
            listOf(0L, 3 * hour, 6 * hour, 9 * hour, 10 * hour),
            d10h.stages.map { it.offsetMs },
        )
    }
}

