package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Location: map places, the member's own sharing choice and presence (web
// lib/location.ts, locationService). The phone watches each map place with a
// geofence (location/Geofences) and reports arriving and leaving; the server
// keeps only the last spot, never a history.

/** A spot on the map (MapPlaceDTO). [kind]: "home" | "store" | "other". */
@Serializable
data class MapPlace(
    val id: String,
    val name: String,
    val kind: String = "other",
    val lat: Double,
    val lng: Double,
    val radiusM: Int = 150,
    val shoppingListIds: List<String> = emptyList(),
)

/** The member's own consent (LocationConsent). [sharing]: "off" | "on" | "required". */
@Serializable
data class LocationConsent(val sharing: String = "off", val liveChecks: Boolean = false) {
    val isSharing: Boolean get() = sharing != "off"
    /** "required" is set by a supervising adult (parental controls), not by the member. */
    val canChange: Boolean get() = sharing != "required"
}

/** What the phone tells the server; [transition] "enter" | "exit". */
data class PresenceReport(
    val placeId: String,
    val transition: String,
    val atMillis: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Double? = null,
)

/** The server's answer (PresenceReportDTO): a store reminder to show, if any. */
@Serializable
data class PresenceResult(val reminder: StoreReminder? = null)

@Serializable
data class StoreReminder(val placeId: String, val placeName: String, val lists: List<ReminderList> = emptyList()) {
    val title: String get() = "At $placeName"
    /** "Groceries: 6 to buy · Hardware: 1 to buy". */
    val text: String get() = lists.joinToString(" · ") { "${it.name}: ${it.openItems} to buy" }
}

@Serializable
data class ReminderList(val id: String, val name: String, val openItems: Int)

/** One member who shares their location (MemberPresenceDTO). [state] "place" | "away". */
@Serializable
data class MemberPresence(
    val userId: String,
    val name: String,
    val state: String,
    val place: PresencePlace? = null,
    val since: String,
    /** Away: where the phone was when they left. Never given for someone at a
     *  place (the place is where they are); missing from servers before the map. */
    val point: PresencePoint? = null,
) {
    val isHome: Boolean get() = place?.kind == "home"

    /** "Home" (the home's own name), "At Costco", or "Away". */
    val label: String
        get() = when {
            place == null -> "Away"
            isHome -> place.name
            else -> "At ${place.name}"
        }
}

@Serializable
data class PresencePlace(val id: String, val name: String, val kind: String = "other")

@Serializable
data class PresencePoint(val lat: Double, val lng: Double, val accuracyM: Double? = null)

/** The names of who is at [placeId], for its label on the map. */
fun peopleAt(placeId: String, presence: List<MemberPresence>): List<String> =
    presence.filter { it.place?.id == placeId }.map { it.name }

/** Members away with a point to show on the map (the rest are only listed). */
fun awayOnMap(presence: List<MemberPresence>): List<MemberPresence> =
    presence.filter { it.place == null && it.point != null }

/** Where the map zooms when a member is tapped: their place, or their point. */
sealed interface MapTarget {
    data class Area(val lat: Double, val lng: Double, val radiusM: Double) : MapTarget
    data class Spot(val lat: Double, val lng: Double) : MapTarget
}

fun mapTarget(member: MemberPresence, places: List<MapPlace>): MapTarget? {
    member.place?.let { at ->
        val place = places.firstOrNull { it.id == at.id } ?: return null
        return MapTarget.Area(place.lat, place.lng, place.radiusM.toDouble())
    }
    return member.point?.let { MapTarget.Spot(it.lat, it.lng) }
}

/**
 * A fingerprint of what the geofences depend on. Unchanged places mean the
 * registered geofences are still right, and re-registering them would make
 * Android report "arrived" again for wherever the phone is (a store reminder
 * every hour while sitting in it). Order and names don't matter.
 */
fun geofenceSignature(places: List<MapPlace>): String =
    places.sortedBy { it.id }.joinToString(";") { "${it.id}@${it.lat},${it.lng},${it.radiusM}" }

/** Where the phone setup stands, for Profile → Location. */
enum class LocationSetup {
    /** The member hasn't turned sharing on. */
    SharingOff,
    /** Sharing on, but the app may not read precise location. */
    NeedsLocation,
    /** Precise location only while the app is open; geofences need "Allow all the time". */
    NeedsBackground,
    /** Everything allowed; the household just has no map places yet. */
    NoPlaces,
    /** Watching the household's map places. */
    Ready,
}

fun locationSetup(sharing: Boolean, fineLocation: Boolean, backgroundLocation: Boolean, places: Int): LocationSetup =
    when {
        !sharing -> LocationSetup.SharingOff
        !fineLocation -> LocationSetup.NeedsLocation
        !backgroundLocation -> LocationSetup.NeedsBackground
        places == 0 -> LocationSetup.NoPlaces
        else -> LocationSetup.Ready
    }
