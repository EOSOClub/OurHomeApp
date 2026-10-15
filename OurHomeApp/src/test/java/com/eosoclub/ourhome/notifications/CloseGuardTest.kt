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
    fun `leaves other pages alone`() {
        // Another app's App info, the apps list, Our Home itself, a missing window.
        assertFalse(close("com.android.settings", "Chrome", "Force stop"))
        assertFalse(close("com.android.settings", "Our Home", "Chrome", "Maps"))
        assertFalse(close(own, "Our Home", "Force stop", "Keep Our Home running"))
        assertFalse(close(null, "Our Home", "Force stop"))
    }
}
