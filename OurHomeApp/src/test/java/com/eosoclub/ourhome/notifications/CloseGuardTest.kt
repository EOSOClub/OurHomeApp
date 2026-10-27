package com.eosoclub.ourhome.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        // Choices: 1, 5 and 10 hours. The 30 and 60 second ones (removed 2026-10-16) fall back to 5 hours.
        org.junit.Assert.assertEquals(listOf("1h", "5h", "10h"), CloseGuard.EmergencyDuration.entries.map { it.key })
        org.junit.Assert.assertEquals(CloseGuard.EmergencyDuration.Hours5, CloseGuard.EmergencyDuration.fromKey("30s"))
        org.junit.Assert.assertEquals(CloseGuard.EmergencyDuration.Hours5, CloseGuard.EmergencyDuration.fromKey("60s"))

        // 1 hour: 3 notifications (start at 0m, 30m left at 30m, unlocked at 60m)
        val d1h = CloseGuard.EmergencyDuration.Hours1
        org.junit.Assert.assertEquals(1 * hour, d1h.durationMs)
        org.junit.Assert.assertEquals(listOf("started", "30m_left", "unlocked"), d1h.stages.map { it.wire })
        org.junit.Assert.assertEquals(listOf(0L, 30 * min, 60 * min), d1h.stages.map { it.offsetMs })
        org.junit.Assert.assertEquals(
            listOf("30m_left"),
            CloseGuard.dueEmergencyStages(start, start + 30 * min, setOf("started"), d1h).map { it.wire },
        )

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

    @Test
    fun `every milestone is one the server knows, and the server knows no others`() {
        // web lib/phoneHealth.ts EMERGENCY_UNLOCK_STAGES
        val server = setOf("started", "30m_left", "2h_elapsed", "3h_elapsed", "6h_elapsed", "1h_left", "unlocked")
        val sent = CloseGuard.EmergencyDuration.entries.flatMap { d -> d.stages.map { it.wire } }.toSet()
        org.junit.Assert.assertEquals(server, sent)
    }

    @Test
    fun `sends only the latest due milestone, never an older one after it`() {
        val hour = 60 * 60_000L
        val start = 1_000_000L
        val d5h = CloseGuard.EmergencyDuration.Hours5
        // Offline from the start until 4 hours in: three are due, only the latest goes.
        val due = CloseGuard.dueEmergencyStages(start, start + 4 * hour, emptySet(), d5h)
        org.junit.Assert.assertEquals(listOf("started", "2h_elapsed", "1h_left"), due.map { it.wire })
        org.junit.Assert.assertEquals("1h_left", CloseGuard.stageToSend(due, forced = null, running = true))
        // A late worker for an older milestone while it runs: what's due decides (nothing, once all were sent).
        val sent = due.map { it.wire }.toSet()
        val later = CloseGuard.dueEmergencyStages(start, start + 4 * hour + 1, sent, d5h)
        org.junit.Assert.assertNull(CloseGuard.stageToSend(later, forced = "2h_elapsed", running = true))
        // The "unlocked" notice after completing cleared the countdown.
        org.junit.Assert.assertEquals("unlocked", CloseGuard.stageToSend(emptyList(), forced = "unlocked", running = false))
        org.junit.Assert.assertNull(CloseGuard.stageToSend(emptyList(), forced = null, running = false))
    }

    private fun screen(pkg: String?, vararg texts: String) = CloseGuard.protectedScreen(
        windowPackage = pkg,
        ownPackage = own,
        appName = "Our Home",
        guardName = "Keep Our Home running",
        forceStopLabels = setOf("Force stop"),
        adminName = "Keep Our Home on this phone",
        deactivateLabels = setOf("Deactivate this device admin app"),
    ) { text -> texts.any { it.equals(text, ignoreCase = true) } }

    @Test
    fun `says which protected screen it is, and the PIN opens all but the uninstall prompt`() {
        assertEquals(CloseGuard.Screen.AppInfo, screen("com.android.settings", "Our Home", "Force stop"))
        assertEquals(CloseGuard.Screen.Accessibility, screen("com.android.settings", "Keep Our Home running"))
        assertEquals(CloseGuard.Screen.DeviceAdmin, screen("com.android.settings", "Keep Our Home on this phone"))
        assertEquals(CloseGuard.Screen.DeviceAdmin, screen("com.android.settings", "Our Home", "Deactivate this device admin app"))
        assertEquals(CloseGuard.Screen.Uninstall, screen("com.android.packageinstaller", "Our Home", "Uninstall"))
        assertNull(screen("com.android.settings", "Chrome", "Force stop"))
        assertNull(screen("com.android.settings", "Activate device admin app", "Keep Our Home on this phone"))
        assertEquals(
            listOf(true, true, true, true, false),
            CloseGuard.Screen.entries.map { it.pinOpens },
        )
    }

    @Test
    fun `every screen the PIN opens is one the server can name`() {
        // web lib/parentPin.ts PIN_SCREENS
        val server = setOf("app_info", "device_admin", "accessibility", "private_dns")
        assertEquals(server, CloseGuard.Screen.entries.filter { it.pinOpens }.map { it.wire }.toSet())
        assertEquals(CloseGuard.Screen.PrivateDns, CloseGuard.Screen.of("private_dns"))
        assertNull(CloseGuard.Screen.of("something_else"))
    }

    @Test
    fun `a screen the PIN opened stays open until they go to another app`() {
        val settings = "com.android.settings"
        // Still in Settings, on Our Home's own PIN screen, the notification shade, or nothing to tell.
        assertFalse(CloseGuard.shouldEndPass(settings, settings, own))
        assertFalse(CloseGuard.shouldEndPass(settings, own, own))
        assertFalse(CloseGuard.shouldEndPass(settings, "com.android.systemui", own))
        assertFalse(CloseGuard.shouldEndPass(settings, null, own))
        // The home screen or any other app.
        assertTrue(CloseGuard.shouldEndPass(settings, "com.google.android.apps.nexuslauncher", own))
        assertTrue(CloseGuard.shouldEndPass(settings, "com.android.chrome", own))
        // Opened in another maker's app (MIUI's Accessibility page): Settings is another app then.
        assertTrue(CloseGuard.shouldEndPass("com.miui.securitycenter", settings, own))
        // The keyboard opening over Settings, to type in the Private DNS box, isn't leaving.
        val keyboard = "com.google.android.inputmethod.latin"
        assertFalse(CloseGuard.shouldEndPass(settings, keyboard, own, keyboard))
        assertTrue(CloseGuard.shouldEndPass(settings, keyboard, own, null))
    }

    private class Node(val text: String?, val description: String? = null, val children: List<Node> = emptyList())

    private fun texts(root: Node, max: Int = CloseGuard.MAX_NODES) =
        CloseGuard.collectTexts(root, { it.children }, { listOf(it.text, it.description) }, max)

    @Test
    fun `reads every text and description on the screen, however deep, ignoring case and spaces`() {
        // App info on Android 16 and later: Android's text search finds none of these.
        val appInfo = Node(
            null,
            children = listOf(
                Node(" Our Home "),
                Node(null, children = listOf(Node("Archive"), Node("Uninstall"), Node(null, "FORCE STOP"))),
                Node(""),
            ),
        )
        val found = texts(appInfo)
        assertEquals(setOf("our home", "archive", "uninstall", "force stop"), found)
        // As the guard asks: exactly the label, not part of one.
        assertTrue(CloseGuard.normalize("Force stop") in found)
        assertFalse(CloseGuard.normalize("Force") in found)
        assertEquals(
            CloseGuard.Screen.AppInfo,
            CloseGuard.protectedScreen(
                windowPackage = "com.android.settings",
                ownPackage = own,
                appName = "Our Home",
                guardName = "Keep Our Home running",
                forceStopLabels = setOf("Force stop"),
            ) { CloseGuard.normalize(it) in found },
        )
    }

    @Test
    fun `stops reading a huge screen after so many elements`() {
        val wide = Node(null, children = List(50) { Node("row $it") })
        assertEquals(9, texts(wide, max = 10).size)
    }

    private fun closeDns(pkg: String?, guarded: Boolean, vararg texts: String) = CloseGuard.shouldClosePrivateDns(
        windowPackage = pkg,
        guarded = guarded,
        providerLabels = setOf("Private DNS provider hostname", "Nom d'hôte du fournisseur DNS privé"),
    ) { text -> texts.any { it.equals(text, ignoreCase = true) } }

    @Test
    fun `closes the Private DNS dialog while the web filter is on, in any language Settings uses`() {
        assertTrue(closeDns("com.android.settings", true, "Private DNS", "Off", "Automatic", "Private DNS provider hostname"))
        assertTrue(closeDns("com.android.settings", true, "Nom d'hôte du fournisseur DNS privé"))
        // Samsung's Settings is the same package; other makers' have "settings" in theirs.
        assertTrue(closeDns("com.coloros.settings", true, "Private DNS provider hostname"))
    }

    @Test
    fun `leaves Private DNS alone while the filter isn't on, and the page it opens from`() {
        // Not set yet: they need the dialog to set it.
        assertFalse(closeDns("com.android.settings", false, "Private DNS provider hostname"))
        // Network & internet only names the row.
        assertFalse(closeDns("com.android.settings", true, "Network & internet", "Private DNS", "Automatic"))
        // The words in another app (a web page about it) aren't Settings.
        assertFalse(closeDns("com.android.chrome", true, "Private DNS provider hostname"))
        assertFalse(closeDns(null, true, "Private DNS provider hostname"))
    }
}
