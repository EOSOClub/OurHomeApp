package com.eosoclub.ourhome.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Which requests need someone's attention. Shared by the Requests tab badge and
// the background reminder notifications so the two always agree.

/** Media requests made before statuses existed have none; treat that as pending. */
val HouseholdRequest.effectiveStatus: String get() = status ?: "pending"

val HouseholdRequest.isOpen: Boolean get() = effectiveStatus != "completed"

/**
 * A media request's status in the one-step flow: "pending" (waiting to be
 * added) or "completed" (added). "accepted" from the older accept → available
 * flow still waits.
 */
val HouseholdRequest.mediaStatus: String get() = if (isOpen) "pending" else "completed"

/**
 * Whether the user marks media requests added: the Requests `approve` switch.
 * The head always may — a server from before the switch existed doesn't send
 * it, but handled media by role (head only).
 */
fun canApproveMedia(access: AccessMatrix, role: String?): Boolean =
    access.requests.approve || role == "head"

/**
 * Requests waiting on [userId]: open media requests if they approve media
 * ([approvesMedia]), and pending maintenance requests assigned to them. Media
 * is one step (waiting → added); "accepted" media is left over from the older
 * accept → available flow and still waits to be added.
 */
fun awaitingAcceptanceBy(
    requests: List<HouseholdRequest>,
    userId: String,
    approvesMedia: Boolean,
): List<HouseholdRequest> = requests.filter { r ->
    when (r.category) {
        "media" -> approvesMedia && r.isOpen
        "maintenance" -> r.effectiveStatus == "pending" && r.assignee?.id == userId
        else -> false
    }
}

enum class DeadlineState { DueTomorrow, DueToday, Overdue }

/**
 * A maintenance deadline worth telling [userId] about. The assignee hears about
 * due tomorrow / due today / overdue; the requester only when it's overdue
 * ([forRequester]).
 */
data class DeadlineAlert(
    val request: HouseholdRequest,
    val due: LocalDate,
    val state: DeadlineState,
    val forRequester: Boolean,
)

/** Accepted maintenance requests with a done-by date that need attention [today]. */
fun deadlineAlerts(
    requests: List<HouseholdRequest>,
    userId: String,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<DeadlineAlert> = requests.mapNotNull { r ->
    val dueAt = r.dueAt
    if (r.category != "maintenance" || r.effectiveStatus != "accepted" || dueAt == null) return@mapNotNull null
    val due = Instant.parse(dueAt).atZone(zone).toLocalDate()
    val state = when {
        due.isBefore(today) -> DeadlineState.Overdue
        due == today -> DeadlineState.DueToday
        due == today.plusDays(1) -> DeadlineState.DueTomorrow
        else -> return@mapNotNull null
    }
    when {
        r.assignee?.id == userId -> DeadlineAlert(r, due, state, forRequester = false)
        r.requester.id == userId && state == DeadlineState.Overdue -> DeadlineAlert(r, due, state, forRequester = true)
        else -> null
    }
}

/** One-line summary, e.g. "TV: Severance (2025) · S2 — from Jamison". */
fun HouseholdRequest.summary(): String {
    val from = requester.name?.let { " — from $it" }.orEmpty()
    return when (category) {
        "media" -> {
            val kind = if (mediaType == "tv") "TV" else "Movie"
            val year = year?.let { " ($it)" }.orEmpty()
            val season = season?.let { " · S$it" }.orEmpty()
            "$kind: $title$year$season$from"
        }
        else -> "Maintenance: $title$from"
    }
}
