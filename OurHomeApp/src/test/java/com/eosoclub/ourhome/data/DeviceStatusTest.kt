package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusTest {

    @Test
    fun `sends only what Android said, as the server's fields`() {
        val full = DeviceStatus(battery = 42, charging = false, network = "cellular", signal = 1)
        assertEquals("""{"battery":42,"charging":false,"network":"cellular","signal":1}""", full.toJson().toString())
        assertEquals("&battery=42&charging=false&network=cellular&signal=1", full.toQuery())
        assertEquals("{}", DeviceStatus().toJson().toString())
        assertEquals("", DeviceStatus().toQuery())
        assertEquals("&network=none", DeviceStatus(network = "none").toQuery())
    }

    @Test
    fun `names the network the way the server does`() {
        assertEquals("none", DeviceStatus.networkName(connected = false, wifi = true, cellular = true))
        assertEquals("wifi", DeviceStatus.networkName(connected = true, wifi = true, cellular = true))
        assertEquals("cellular", DeviceStatus.networkName(connected = true, wifi = false, cellular = true))
        assertEquals("other", DeviceStatus.networkName(connected = true, wifi = false, cellular = false))
    }

    @Test
    fun `tells the server once when the battery goes nearly flat`() {
        assertTrue(DeviceStatus.isNewlyLow(5, charging = false, alreadySent = false))
        assertFalse(DeviceStatus.isNewlyLow(5, charging = false, alreadySent = true))
        assertFalse(DeviceStatus.isNewlyLow(3, charging = true, alreadySent = false))
        assertFalse(DeviceStatus.isNewlyLow(6, charging = false, alreadySent = false))
    }

    @Test
    fun `names why a looked-after phone went quiet`() {
        assertEquals(
            "OurHome may have been removed from their phone",
            LookedAfterPhone("x", problems = listOf("app_removed")).problemText,
        )
        assertEquals("Their phone probably ran out of battery", LookedAfterPhone("x", problems = listOf("battery_flat")).problemText)
    }
}
