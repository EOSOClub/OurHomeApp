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
    fun `the map labels each place with who is there and shows only the away with a point`() {
        val since = "2026-10-12T17:00:00Z"
        val sam = MemberPresence("u1", "Sam", "place", PresencePlace("c", "Costco", "store"), since)
        val alex = MemberPresence("u2", "Alex", "away", null, since, PresencePoint(41.89, -87.65, 30.0))
        val kim = MemberPresence("u3", "Kim", "away", null, since)
        val everyone = listOf(sam, alex, kim)
        assertEquals(listOf("Sam"), peopleAt("c", everyone))
        assertEquals(emptyList<String>(), peopleAt("h", everyone))
        assertEquals(listOf(alex), awayOnMap(everyone))
    }

    @Test
    fun `tapping a member zooms to their place or their point`() {
        val since = "2026-10-12T17:00:00Z"
        val atCostco = MemberPresence("u1", "Sam", "place", PresencePlace("c", "Costco", "store"), since)
        assertEquals(MapTarget.Area(41.9, -87.7, 500.0), mapTarget(atCostco, listOf(home, costco)))
        val away = MemberPresence("u2", "Alex", "away", null, since, PresencePoint(41.89, -87.65))
        assertEquals(MapTarget.Spot(41.89, -87.65), mapTarget(away, listOf(home, costco)))
        // A place deleted since the last refresh, or no point: nowhere to go.
        assertEquals(null, mapTarget(atCostco, listOf(home)))
        assertEquals(null, mapTarget(MemberPresence("u3", "Kim", "away", null, since), listOf(home)))
    }

    @Test
    fun `live check labels`() {
        assertEquals("5 minutes", minutesLabel(5))
        assertEquals("1 hour", minutesLabel(60))
        assertEquals("2 hours", minutesLabel(120))
        assertEquals("15 seconds", intervalLabel(15))
        assertEquals("1 minute", intervalLabel(60))
        assertEquals("2 minutes", intervalLabel(120))
        assertEquals("updated 20s ago", fixAge(1_000_000, 1_020_000))
        assertEquals("updated 3 min ago", fixAge(1_000_000, 1_180_000))
        // A phone clock slightly ahead never reads as negative.
        assertEquals("updated 0s ago", fixAge(1_005_000, 1_000_000))
    }

    @Test
    fun `the checked person's log says how each check went`() {
        val start = 1_000_000L
        val entry = LiveCheckLogEntry("c", "Alex", "2026-10-12T15:00:00Z", answered = true)
        assertEquals("running now", liveCheckLogDetail(entry, start, null))
        assertEquals("5 min", liveCheckLogDetail(entry, start, start + 5 * 60_000))
        assertEquals("stopped by you after 2 min", liveCheckLogDetail(entry.copy(stoppedBy = "me"), start, start + 2 * 60_000))
        assertEquals("1 min, stopped early", liveCheckLogDetail(entry.copy(stoppedBy = "requester"), start, start + 20_000))
        assertEquals("your phone didn't answer", liveCheckLogDetail(entry.copy(answered = false), start, start + 60_000))
    }

    @Test
    fun `the map fetches as often as the busiest check, else once a minute`() {
        fun check(interval: Int) = LiveCheck("c$interval", "a", "Alex", "s", "Sam", "", "", interval, "live")
        assertEquals(60_000L, mapRefreshMs(emptyList()))
        assertEquals(15_000L, mapRefreshMs(listOf(check(30), check(15))))
    }

    @Test
    fun `location is off on servers from before features`() {
        assertFalse(Features.from(null).location)
        assertTrue(Features.from(listOf("tasks", "location")).location)
    }
}
