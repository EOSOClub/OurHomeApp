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
    fun `a looked-after phone names its problems, and nothing when all is well`() {
        assertEquals(null, LookedAfterPhone("2026-10-12T10:00:00Z").problemText)
        assertEquals("Phone hasn't checked in", LookedAfterPhone("x", problems = listOf("quiet")).problemText)
        assertEquals(
            "Phone hasn't checked in · Location turned off on their phone",
            LookedAfterPhone("x", problems = listOf("quiet", "location_off", "unknown")).problemText,
        )
        assertEquals(
            "Battery saving can delay OurHome on their phone",
            LookedAfterPhone("x", problems = listOf("battery_restricted")).problemText,
        )
        assertEquals(
            "OurHome can be closed on their phone",
            LookedAfterPhone("x", problems = listOf("close_guard_off")).problemText,
        )
        assertEquals(
            "OurHome can be uninstalled on their phone",
            LookedAfterPhone("x", problems = listOf("uninstall_guard_off")).problemText,
        )
    }

    @Test
    fun `a looked-after phone from an older server still reads, with no delivery details`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val phone = json.decodeFromString(LookedAfterPhone.serializer(), """{"lastSeenAt":"2026-10-12T10:00:00Z","problems":[]}""")
        assertEquals(null, phone.lastPushAt)
        assertFalse(phone.stayConnected)
        assertFalse(phone.connected)
    }

    @Test
    fun `phone alerts join the household reminder notification`() {
        assertTrue("phone_alert" in HOUSEHOLD_REMINDER_TYPES)
    }

    private val now = java.time.Instant.parse("2026-10-12T18:00:00Z").toEpochMilli()
    private fun minutesAgo(m: Long) = java.time.Instant.ofEpochMilli(now - m * 60_000).toString()

    @Test
    fun `the battery shows its level, and charging while fresh`() {
        assertEquals(BatteryLabel("Battery 85%", false), batteryLabel(PresenceBattery(85, false, minutesAgo(10)), now))
        assertEquals("Battery 85%, charging", batteryLabel(PresenceBattery(85, true, minutesAgo(10)), now).text)
    }

    @Test
    fun `a low battery is flagged unless it is charging`() {
        assertTrue(batteryLabel(PresenceBattery(20, false, minutesAgo(10)), now).low)
        assertFalse(batteryLabel(PresenceBattery(21, false, minutesAgo(10)), now).low)
        assertFalse(batteryLabel(PresenceBattery(5, true, minutesAgo(10)), now).low)
    }

    @Test
    fun `a stale battery reading says how old it is`() {
        assertEquals("Battery 40%, charging", batteryLabel(PresenceBattery(40, true, minutesAgo(119)), now).text)
        assertEquals("Battery 40%, 3 h ago", batteryLabel(PresenceBattery(40, true, minutesAgo(180)), now).text)
        assertEquals("Battery 40%, 3 days ago", batteryLabel(PresenceBattery(40, false, minutesAgo(3 * 24 * 60)), now).text)
    }

    @Test
    fun `someone looked after says whether they share, unless it is required`() {
        assertEquals(null, LookedAfter("s", "Sam", "required").sharingNote)
        assertTrue(LookedAfter("s", "Sam", "required").required)
        assertEquals("Shares their location", LookedAfter("s", "Sam", "on").sharingNote)
        assertEquals("Not sharing their location", LookedAfter("s", "Sam").sharingNote)
    }

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
    fun `the home reminder names the chores due`() {
        assertEquals("You're home: 1 chore due", HomeReminder(1, listOf("Dishes")).title)
        assertEquals("Dishes", HomeReminder(1, listOf("Dishes")).text)
        assertEquals("Dishes and Bins", HomeReminder(2, listOf("Dishes", "Bins")).text)
        val five = HomeReminder(5, listOf("Dishes", "Bins", "Cat"))
        assertEquals("You're home: 5 chores due", five.title)
        assertEquals("Dishes, Bins, Cat and 2 more", five.text)
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
