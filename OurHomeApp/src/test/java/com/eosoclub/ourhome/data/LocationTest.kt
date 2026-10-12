package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTest {

    private val home = MapPlace("h", "Home", "home", 41.88, -87.63, 150)
    private val costco = MapPlace("c", "Costco", "store", 41.9, -87.7, 500, listOf("groceries"))

    @Test
    fun `the geofence signature ignores order and names`() {
        assertEquals(geofenceSignature(listOf(home, costco)), geofenceSignature(listOf(costco, home)))
        assertEquals(geofenceSignature(listOf(home)), geofenceSignature(listOf(home.copy(name = "Our house"))))
    }

    @Test
    fun `the geofence signature changes when a fence would`() {
        val base = geofenceSignature(listOf(home, costco))
        assertNotEquals(base, geofenceSignature(listOf(home, costco.copy(radiusM = 1000))))
        assertNotEquals(base, geofenceSignature(listOf(home, costco.copy(lat = 41.95))))
        assertNotEquals(base, geofenceSignature(listOf(home)))
    }

    @Test
    fun `setup goes sharing, precise location, all the time, then ready`() {
        assertEquals(LocationSetup.SharingOff, locationSetup(false, true, true, 3))
        assertEquals(LocationSetup.NeedsLocation, locationSetup(true, false, false, 3))
        assertEquals(LocationSetup.NeedsBackground, locationSetup(true, true, false, 3))
        assertEquals(LocationSetup.NoPlaces, locationSetup(true, true, true, 0))
        assertEquals(LocationSetup.Ready, locationSetup(true, true, true, 3))
    }

    @Test
    fun `consent is off unless the member said yes, and required can't be changed`() {
        assertFalse(LocationConsent().isSharing)
        assertTrue(LocationConsent("on").isSharing)
        assertTrue(LocationConsent("required").isSharing)
        assertFalse(LocationConsent("required").canChange)
        assertTrue(LocationConsent("on").canChange)
    }

    @Test
    fun `a store reminder reads as the lists still to buy`() {
        val reminder = StoreReminder(
            "c",
            "Costco",
            listOf(ReminderList("g", "Groceries", 6), ReminderList("h", "Hardware", 1)),
        )
        assertEquals("At Costco", reminder.title)
        assertEquals("Groceries: 6 to buy · Hardware: 1 to buy", reminder.text)
    }

    @Test
    fun `presence labels`() {
        val since = "2026-10-12T17:00:00Z"
        assertEquals("Our house", MemberPresence("u", "Sam", "place", PresencePlace("h", "Our house", "home"), since).label)
        assertEquals("At Costco", MemberPresence("u", "Sam", "place", PresencePlace("c", "Costco", "store"), since).label)
        assertEquals("Away", MemberPresence("u", "Sam", "away", null, since).label)
    }

    @Test
    fun `location is off on servers from before features`() {
        assertFalse(Features.from(null).location)
        assertTrue(Features.from(listOf("tasks", "location")).location)
    }
}
