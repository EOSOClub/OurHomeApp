package com.eosoclub.ourhome.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTest {

    @Test
    fun `acts on a message once when it arrives both ways`() {
        val seen = RecentIds(3)
        assertTrue(seen.add("a"))
        assertFalse(seen.add("a"))
        // Messages from an older server carry no id: never taken for repeats.
        assertTrue(seen.add(null))
        assertTrue(seen.add(null))
        assertTrue(seen.add(""))
    }

    @Test
    fun `remembers only the most recent ids`() {
        val seen = RecentIds(2)
        seen.add("a")
        seen.add("b")
        seen.add("c")
        assertTrue(seen.add("a"))
        assertFalse(seen.add("c"))
    }

    @Test
    fun `links the phone maker's own battery steps, only for makers known to need them`() {
        assertEquals("https://dontkillmyapp.com/samsung", BatteryAccess.makerHelpUrl("samsung"))
        assertEquals("https://dontkillmyapp.com/xiaomi", BatteryAccess.makerHelpUrl("Xiaomi"))
        assertEquals("https://dontkillmyapp.com/oneplus", BatteryAccess.makerHelpUrl(" OnePlus "))
        assertNull(BatteryAccess.makerHelpUrl("Google"))
        assertNull(BatteryAccess.makerHelpUrl("samsungish"))
    }
}
