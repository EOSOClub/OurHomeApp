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
 * Requests waiting for [userId] to accept: pending media requests if their role
 * handles media (the head), and pending maintenance requests assigned to them.
 */
fun awaitingAcceptanceBy(requests: List<HouseholdRequest>, userId: String, role: String?): List<HouseholdRequest> {
    val handlesMedia = can(role, Permission.RequestsManageMedia)
    return requests.filter { r ->
        r.effectiveStatus == "pending" && when (r.category) {
            "media" -> handlesMedia
            "maintenance" -> r.assignee?.id == userId
            else -> false
        }
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
