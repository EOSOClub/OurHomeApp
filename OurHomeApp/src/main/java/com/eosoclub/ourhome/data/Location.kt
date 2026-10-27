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

/** What the phone tells the server; [transition] "enter" | "exit". [battery]: the phone's at that moment. */
data class PresenceReport(
    val placeId: String,
    val transition: String,
    val atMillis: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Double? = null,
    val battery: BatteryLevel? = null,
)

/** This phone's battery, as sent with reports and hourly (location/Battery). */
data class BatteryLevel(val pct: Int, val charging: Boolean)

/** The server's answer (PresenceReportDTO): a store or home reminder to show, if any. */
@Serializable
data class PresenceResult(val reminder: StoreReminder? = null, val home: HomeReminder? = null)

/** Arrived home with chores of theirs due today or overdue. [titles]: the first few. */
@Serializable
data class HomeReminder(val count: Int, val titles: List<String> = emptyList()) {
    /** "You're home: 3 chores due" / "You're home: 1 chore due". */
    val title: String get() = "You're home: $count ${if (count == 1) "chore" else "chores"} due"

    /** "Dishes, Take out the bins and 1 more". */
    val text: String
        get() {
            val more = count - titles.size
            return when {
                titles.isEmpty() -> "Open Tasks to see them."
                more > 0 -> "${titles.joinToString(", ")} and $more more"
                titles.size == 1 -> titles[0]
                else -> titles.dropLast(1).joinToString(", ") + " and " + titles.last()
            }
        }
}

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
    /** Their phone's last battery reading; missing before it sent one, and from older servers. */
    val battery: PresenceBattery? = null,
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

/** [at]: when it was taken; later than `since` when a live check left it. Missing on older servers. */
@Serializable
data class PresencePoint(val lat: Double, val lng: Double, val accuracyM: Double? = null, val at: String? = null)

/** [at]: when the phone read it (ISO). */
@Serializable
data class PresenceBattery(val pct: Int, val charging: Boolean = false, val at: String)

/** At or below this the level is shown as low (web: BATTERY_LOW_PCT). */
const val BATTERY_LOW_PCT = 20

/** A reading older than this shows its age (web: BATTERY_STALE_MS). */
const val BATTERY_STALE_MS = 2 * 60 * 60_000L

/**
 * "Battery 85%", "Battery 85%, charging", "Battery 12%, 3 h ago" (web:
 * batteryLabel). [low]: a flat-ish battery that isn't charging.
 */
data class BatteryLabel(val text: String, val low: Boolean)

fun batteryLabel(battery: PresenceBattery, nowMillis: Long): BatteryLabel {
    val atMillis = runCatching { java.time.Instant.parse(battery.at).toEpochMilli() }.getOrDefault(nowMillis)
    val ageMs = nowMillis - atMillis
    val parts = mutableListOf("Battery ${battery.pct}%")
    if (ageMs >= BATTERY_STALE_MS) {
        val hours = ageMs / 3_600_000
        parts += if (hours < 48) "$hours h ago" else "${hours / 24} days ago"
    } else if (battery.charging) {
        parts += "charging"
    }
    return BatteryLabel(parts.joinToString(", "), battery.pct <= BATTERY_LOW_PCT && !battery.charging)
}

// --- Live checks (web liveCheckService) --------------------------------------------------
// An adult watches where someone is for a few minutes. The checked person's
// phone shows a notification the whole time (location/LiveChecks) and sends a
// fix every interval; only the latest is kept on the server.

/** One check. [state]: "waiting" | "live" | "no_answer" | "ended". */
@Serializable
data class LiveCheck(
    val id: String,
    val requesterId: String,
    val requesterName: String,
    val targetId: String,
    val targetName: String,
    val startedAt: String,
    val endsAt: String,
    val intervalSec: Int,
    val state: String,
    val fix: LiveCheckFix? = null,
    /** For the person checked: may they stop it (not a supervisor's check under
     *  required sharing). Null when someone else asks, and on older servers. */
    val canStopOnMe: Boolean? = null,
) {
    val isOver: Boolean get() = state == "ended"
}

@Serializable
data class LiveCheckFix(val lat: Double, val lng: Double, val accuracyM: Double? = null, val at: String)

/** One check in the checked person's log. [stoppedBy]: "me" | "requester" | null (ran its time). */
@Serializable
data class LiveCheckLogEntry(
    val id: String,
    val requesterName: String,
    val startedAt: String,
    val endedAt: String? = null,
    val stoppedBy: String? = null,
    val answered: Boolean = false,
)

@Serializable
data class LiveCheckChoices(
    val minutes: List<Int> = emptyList(),
    val intervals: List<Int> = emptyList(),
    val defaultMinutes: Int = 5,
    val defaultIntervalSec: Int = 30,
)

@Serializable
data class CheckableMember(val id: String, val name: String)

/** GET /api/location/checks. [available]: false when the server can't push (no Firebase). */
@Serializable
data class LiveChecksOverview(
    val onMe: LiveCheck? = null,
    val started: List<LiveCheck> = emptyList(),
    val log: List<LiveCheckLogEntry> = emptyList(),
    val checkable: List<CheckableMember> = emptyList(),
    val choices: LiveCheckChoices = LiveCheckChoices(),
    val available: Boolean = false,
)

/**
 * GET /api/supervision/mine: the children and teens this member supervises.
 * [canRing]: false when the server can't push.
 */
@Serializable
data class LookAfter(val canRing: Boolean = false, val people: List<LookedAfter> = emptyList())

/** [sharing]: their location sharing, "off" | "on" | "required". */
@Serializable
data class LookedAfter(
    val id: String,
    val name: String,
    val sharing: String = "off",
    /** Their freshest phone's last check-in; null before any did, and from older servers. */
    val phone: LookedAfterPhone? = null,
    /** App usage sharing and the newest day reported; off on older servers. */
    val appUsage: LookedAfterUsage = LookedAfterUsage(),
    /** The protections their phone must have on; null from older servers (no switches then). */
    val rules: PhoneRules? = null,
    /** The web filter on their phone; null from older servers (no card then). */
    val webFilter: LookedAfterWebFilter? = null,
    /** Apps blocked on their phone; none from older servers. */
    val blocking: LookedAfterBlocking = LookedAfterBlocking(),
    /** The apps in their stored usage, apart from the ones their phone always keeps open (for limits and schedules). */
    val usageApps: List<LimitChoice> = emptyList(),
    /** Daily limits on their phone; none from older servers. */
    val limits: LookedAfterLimits = LookedAfterLimits(),
    /** Schedules on their phone, earliest start first; none from older servers. */
    val schedules: List<ScheduleEntry> = emptyList(),
    /** Points for screen time; off from older servers. */
    val pointsTime: LookedAfterPointsTime = LookedAfterPointsTime(),
    /** How far their phone got with Set up this phone; null from older servers. */
    val setup: LookedAfterSetup? = null,
    /** My last ring of their phone and what it said back; null when I haven't rung it lately. */
    val ring: LookedAfterRing? = null,
) {
    val required: Boolean get() = sharing == "required"

    /** Under their name: null when required (the switch says it). */
    val sharingNote: String?
        get() = when (sharing) {
            "required" -> null
            "on" -> "Shares their location"
            else -> "Not sharing their location"
        }
}

/**
 * A ring of a supervised child's phone and what their phone said back (web
 * lib/ring.ts). [phase]: "sending" (no word yet) | "ringing" | "stopped" (Stop
 * tapped) | "rang_out" (30 seconds, no Stop) | "rang" (no word on how it
 * ended) | "no_answer" (it never said it rang). [stoppedAfterSec]: only for
 * "stopped", when the phone also said it started.
 */
@Serializable
data class LookedAfterRing(
    val sentAt: String,
    val phase: String,
    val stoppedAfterSec: Int? = null,
) {
    /** Still going: the screen looks again every few seconds. */
    val active: Boolean get() = phase == "sending" || phase == "ringing"

    /** Ended, as their phone said: they may ring again at once. */
    val ended: Boolean get() = phase == "stopped" || phase == "rang_out"

    /** After "You rang 2 minutes ago: ". */
    val outcome: String
        get() = when (phase) {
            "sending" -> "waiting for their phone to say it's ringing"
            "ringing" -> "ringing on their phone now"
            "stopped" -> stoppedAfterSec?.let { "Stop was tapped on their phone after $it ${if (it == 1) "second" else "seconds"}" }
                ?: "Stop was tapped on their phone"
            "rang_out" -> "it rang for 30 seconds and nobody tapped Stop"
            "rang" -> "it rang on their phone"
            else -> "their phone didn't say it rang. It may be off or out of signal, or need the latest OurHome app."
        }
}

/**
 * [problems]: "quiet" | "app_removed" | "switched_off" | "battery_flat" |
 * "no_signal" (why it went quiet, at most one) | "notifications_off" |
 * "location_off" | "usage_off" | "web_filter_off" | "battery_low"
 * | "battery_restricted" | "close_guard_off" | "uninstall_guard_off" |
 * "emergency_unlock_*" (web lib/phoneHealth.ts PHONE_PROBLEMS). [lastPushAt]:
 * when an instant alert last reached it (null: none yet, or an older app).
 * [stayConnected]: turned on in the app; [connected]: holding its connection
 * to the server right now.
 */
@Serializable
data class LookedAfterPhone(
    val lastSeenAt: String,
    val deviceName: String? = null,
    val problems: List<String> = emptyList(),
    val lastPushAt: String? = null,
    val stayConnected: Boolean = false,
    val connected: Boolean = false,
) {
    /** "Phone hasn't checked in" / "Location turned off on their phone"; null when all is well. */
    val problemText: String?
        get() = problems.mapNotNull {
            when (it) {
                "quiet" -> "Phone hasn't checked in"
                "app_removed" -> "OurHome may have been removed from their phone"
                "switched_off" -> "Their phone is switched off"
                "battery_flat" -> "Their phone probably ran out of battery"
                "no_signal" -> "Their phone may be out of signal"
                "notifications_off" -> "Notifications turned off on their phone"
                "location_off" -> "Location turned off on their phone"
                "usage_off" -> "Usage access turned off on their phone"
                "web_filter_off" -> "Web filter turned off on their phone"
                "battery_low" -> "Their phone battery is low"
                "battery_restricted" -> "Battery saving can delay OurHome on their phone"
                "close_guard_off" -> "OurHome can be closed on their phone"
                "uninstall_guard_off" -> "OurHome can be uninstalled on their phone"
                "emergency_unlock_started" -> "Emergency unlock started on their phone"
                "emergency_unlock_2h" -> "Emergency unlock on their phone: 3 hours left"
                "emergency_unlock_1h" -> "Emergency unlock on their phone: 1 hour left"
                "emergency_unlock_30m_left" -> "Emergency unlock on their phone: 30 minutes left"
                "emergency_unlock_4h_left" -> "Emergency unlock on their phone: 4 hours left"
                "emergency_unlock_7h_left" -> "Emergency unlock on their phone: 7 hours left"
                "emergency_unlock_unlocked" -> "Emergency unlock finished on their phone"
                else -> null
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * The protections a supervising adult requires on a child's phone (web
 * lib/phoneHealth.ts PHONE_RULES). Each one they require is a step in that
 * phone's Set up this phone, and they're alerted when it's off.
 */
@Serializable
data class PhoneRules(
    val keepRunning: Boolean = true,
    val preventUninstall: Boolean = true,
    val batteryUnrestricted: Boolean = true,
)

/**
 * A supervised child's Set up this phone, from their phone's check-ins (web
 * LookAfterDTO setup): [passwordChosen] (off their temporary one), then each
 * step their supervisors require, in the app's order.
 */
@Serializable
data class LookedAfterSetup(
    val passwordChosen: Boolean = false,
    val steps: List<LookedAfterSetupStep> = emptyList(),
)

/** [step]: a [SetupStep.key]. [done]: null when their app is too old to say. */
@Serializable
data class LookedAfterSetupStep(val step: String, val done: Boolean? = null)

/** The [PhoneRules] the web knows, by its key, with the switch's label. */
enum class PhoneRule(val key: String, val label: String) {
    KeepRunning("keepRunning", "Keep Our Home running"),
    PreventUninstall("preventUninstall", "Prevent uninstall"),
    BatteryUnrestricted("batteryUnrestricted", "Unrestricted battery"),
    ;

    fun of(rules: PhoneRules): Boolean = when (this) {
        KeepRunning -> rules.keepRunning
        PreventUninstall -> rules.preventUninstall
        BatteryUnrestricted -> rules.batteryUnrestricted
    }
}

/**
 * GET /api/phone/setup: what this member's phone must have on, for Set up
 * this phone. Nothing while nobody looks after them; older servers have no
 * such endpoint (the call fails, and the checklist stays away). [webFilter]:
 * the web filter chosen for them, with the hostname Private DNS needs (null:
 * none, or an older server).
 */
@Serializable
data class PhoneSetup(
    val supervised: Boolean = false,
    val required: PhoneSetupRequired = PhoneSetupRequired(),
    val webFilter: PhoneSetupWebFilter? = null,
)

@Serializable
data class PhoneSetupRequired(
    val notifications: Boolean = false,
    val location: Boolean = false,
    val usageAccess: Boolean = false,
    val keepRunning: Boolean = false,
    val preventUninstall: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val webFilter: Boolean = false,
)

/** "5 minutes", "1 hour", "2 hours". */
fun minutesLabel(minutes: Int): String = when {
    minutes % 60 == 0 && minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "$minutes minutes"
}

/** "15 seconds", "1 minute", "2 minutes". */
fun intervalLabel(seconds: Int): String = when {
    seconds % 60 == 0 && seconds == 60 -> "1 minute"
    seconds % 60 == 0 -> "${seconds / 60} minutes"
    else -> "$seconds seconds"
}

/** "updated 20s ago" / "updated 3 min ago" (web: fixAge). */
fun fixAge(atMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - atMillis) / 1000.0).let { kotlin.math.round(it).toLong() }.coerceAtLeast(0)
    return if (seconds < 90) "updated ${seconds}s ago" else "updated ${kotlin.math.round(seconds / 60.0).toLong()} min ago"
}

/** A log entry's detail (web: logDetail): "5 min", "stopped by you after 2 min", ... */
fun liveCheckLogDetail(entry: LiveCheckLogEntry, startedMillis: Long, endedMillis: Long?): String {
    if (endedMillis == null) return "running now"
    if (!entry.answered) return "your phone didn't answer"
    val minutes = kotlin.math.round((endedMillis - startedMillis) / 60_000.0).toLong().coerceAtLeast(1)
    return when (entry.stoppedBy) {
        "me" -> "stopped by you after $minutes min"
        "requester" -> "$minutes min, stopped early"
        else -> "$minutes min"
    }
}

/** How soon the Map screen fetches again: as often as the most frequent running check, else a minute. */
fun mapRefreshMs(started: List<LiveCheck>): Long =
    started.minOfOrNull { it.intervalSec }?.let { it * 1000L } ?: 60_000L

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
