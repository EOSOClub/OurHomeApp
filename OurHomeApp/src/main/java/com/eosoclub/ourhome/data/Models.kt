package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Mirrors the web app's DTOs (src/server/services/*Service.ts `*ToDTO`).
// Only fields the app uses are declared; the JSON parser ignores the rest.

/** The `{ ok, data } | { ok: false, error }` envelope from src/server/api/http.ts. */
@Serializable
data class Envelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ErrorBody? = null,
)

@Serializable
data class ErrorBody(
    val message: String? = null,
    /** Machine-readable extras, e.g. {"code": "household_disabled"}. */
    val details: kotlinx.serialization.json.JsonElement? = null,
)

/** The Android app the server offers (built by the web deploy); src/server/services/appDownloadService.ts. */
@Serializable
data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val appId: String,
    val builtAt: String,
    val sizeBytes: Long,
    val sha256: String,
    val firebase: Boolean = false,
)

/** GET /api/app/info; `release` is null when the server builds no app. */
@Serializable
data class AppInfo(val release: AppRelease? = null)

/** Better Auth endpoints answer errors as a bare `{ message, code }`. */
@Serializable
data class AuthError(val message: String? = null, val code: String? = null)

@Serializable
data class SessionResponse(val user: SessionUser)

@Serializable
data class SessionUser(
    val id: String,
    val name: String? = null,
    /** Lowercased sign-in form; [displayUsername] keeps the user's casing. */
    val username: String? = null,
    val displayUsername: String? = null,
    val role: String? = null,
    val householdId: String? = null,
    val mustChangePassword: Boolean? = null,
)

/**
 * A household member (GET /api/household/members). The about-me fields come
 * from a newer server (HouseholdProfile in profileService.ts); older ones
 * send only id + name.
 */
@Serializable
data class Member(
    val id: String,
    val name: String,
    val role: String? = null,
    val bio: String? = null,
    val avatarEmoji: String? = null,
    val profileColor: String? = null,
    /** "MM-DD", no year. */
    val birthday: String? = null,
)

/** The user's own about-me fields (PublicProfile in the web's lib/profile.ts). */
@Serializable
data class PublicProfile(
    val bio: String? = null,
    val avatarEmoji: String? = null,
    val profileColor: String? = null,
    val birthday: String? = null,
)

/** The signed-in user's own account (ProfileOverview in profileService.ts). */
@Serializable
data class ProfileOverview(
    val id: String,
    val name: String,
    val email: String,
    val username: String? = null,
    val role: String,
    val householdName: String? = null,
    val memberSince: String,
    /** Null from a server without about-me fields (the card is hidden then). */
    val profile: PublicProfile? = null,
    val stats: ProfileStats = ProfileStats(),
)

@Serializable
data class ProfileStats(
    val tasksCompleted: Int = 0,
    val openAssignedTasks: Int = 0,
    val purchasesLogged: Int = 0,
)

@Serializable
data class CategoryRef(val id: String, val name: String, val color: String? = null)

@Serializable
data class UserRef(val id: String, val name: String? = null)

@Serializable
data class Recurrence(
    val kind: String,
    val interval: Int = 1,
    /** Comma-separated, 0 = Sunday; null = any day. */
    val byWeekday: String? = null,
    /** Comma-separated days of the month; null = any. */
    val byMonthday: String? = null,
    val until: String? = null,
    val nextRunAt: String? = null,
    /** Runs in cycles that roll over at local midnight (household time zone). */
    val rollover: Boolean = false,
    /** Weekly cycle start days, "1,5" (0 = Sunday). */
    val cycleWeekdays: String? = null,
    /** Monthly cycle start days, "1,20" (past a month's end → its last day). */
    val cycleMonthdays: String? = null,
)

@Serializable
data class Subtask(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val position: Int = 0,
    /** Auto-uncheck cadence in days; null = resets only with the parent task. */
    val resetIntervalDays: Int? = null,
    /** Who checked it. */
    val doneBy: UserRef? = null,
    /** Whose points are queued on it until the task is completed. */
    val queuedFor: UserRef? = null,
    /** This step's share (see TaskPoints.kt). */
    val minutes: Int = 0,
    val points: Double = 0.0,
    val minutesCustom: Boolean = false,
    val pointsFollowTime: Boolean = true,
)

/** Recurrence as the web's task form sends it (timezone fixed to UTC, like the web). */
data class RecurrenceInput(
    val kind: String,
    val interval: Int,
    val byWeekday: List<Int>,
    val byMonthday: List<Int>,
    val until: java.time.Instant?,
    val rollover: Boolean = false,
    val cycleWeekdays: List<Int> = emptyList(),
    val cycleMonthdays: List<Int> = emptyList(),
)

/** Every field of the web's create/edit task form. */
data class TaskInput(
    val title: String,
    val notes: String?,
    val type: String,
    val priority: String,
    val dueDate: java.time.Instant?,
    /** The task-level (base) TTC and points; points null while they follow time. */
    val estimatedMinutes: Int?,
    val points: Double? = null,
    val pointsFollowTime: Boolean = true,
    val categoryId: String?,
    /**
     * "room:<id>", "floor:<id>" or "" (whole house); null = leave the place
     * as it is (rooms couldn't be loaded, or an older server).
     */
    val place: String? = null,
    val assigneeId: String?,
    /** People taking turns, in order; empty clears the rotation. */
    val rotationUserIds: List<String> = emptyList(),
    /** Null = not recurring (clears the rule on edit). */
    val recurrence: RecurrenceInput?,
)

/** One checklist step as the editor saves it (the whole list replaces the checklist). */
data class StepInput(
    val id: String?,
    val title: String,
    val resetIntervalDays: Int?,
    val values: StepValues,
)

/** One row of a task's history (GET /api/tasks/completions). */
@Serializable
data class TaskCompletion(
    val id: String,
    val note: String? = null,
    val completedAt: String,
    val user: UserRef? = null,
    /** "completed" | "missed" (a cycle ran out unfinished). */
    val outcome: String = "completed",
    val undoneAt: String? = null,
    val points: Double = 0.0,
    val canUndo: Boolean = false,
)

/** GET /api/points/summary (pointsService.pointsSummary). */
@Serializable
data class PointsSummary(
    val period: String,
    val startDate: String,
    val days: Int,
    val elapsedDays: Int,
    val members: List<PointsMember> = emptyList(),
    val householdTotal: Double = 0.0,
    val averagePerPerson: Double = 0.0,
    val averagePerPersonPerDay: Double = 0.0,
)

@Serializable
data class PointsMember(
    val userId: String,
    val name: String,
    val points: Double = 0.0,
    val perDay: Double = 0.0,
    val awards: Int = 0,
    /** Checked steps waiting for their task to be completed. */
    val queued: Double = 0.0,
)

/** One ledger entry (GET /api/points/awards). */
@Serializable
data class PointAward(
    val id: String,
    val userId: String,
    val userName: String? = null,
    /** "task" | "step" | "step_repeat" */
    val kind: String,
    val points: Double,
    val taskTitle: String,
    val stepTitle: String? = null,
    val awardedAt: String,
    val voided: Boolean = false,
    val voidReason: String? = null,
)

@Serializable
data class Task(
    val id: String,
    /** Who created it; decides own vs others' for [PageAccess]. Null = nobody recorded. */
    val createdById: String? = null,
    val title: String,
    val notes: String? = null,
    val type: String,
    val priority: String,
    val status: String,
    val dueDate: String? = null,
    val completedAt: String? = null,
    /** Live TTC: the sum of the steps (or the base without steps). */
    val estimatedMinutes: Int? = null,
    /** Live points, same rule; paid when the task is completed. */
    val points: Double = 0.0,
    /** Task-level values the user set (they define the task's rate). */
    val baseMinutes: Int? = null,
    val basePoints: Double? = null,
    val pointsFollowTime: Boolean = true,
    /** Household rate, for the editor's live points. */
    val minutesPerPoint: Double = DEFAULT_MINUTES_PER_POINT,
    /** Current cycle window; completed inside it = done for this cycle. */
    val cycleStartedAt: String? = null,
    val cycleEndsAt: String? = null,
    val category: CategoryRef? = null,
    /** Where it's done (Places.kt); both null = the whole house. */
    val room: PlaceRef? = null,
    /** The whole floor it covers, or its room's floor. */
    val floor: PlaceRef? = null,
    /** Hand-set order within its place; null = by due date, after the ordered ones. */
    val position: Int? = null,
    val assignee: UserRef? = null,
    /** Rotating assignees in turn order (empty = no rotation; web lib/taskRotation.ts). */
    val rotation: List<UserRef> = emptyList(),
    /** Whose turn comes after [assignee]. */
    val nextAssignee: UserRef? = null,
    val recurrence: Recurrence? = null,
    val subtasks: List<Subtask> = emptyList(),
    /** Only on POST /api/tasks/complete: the completion, for Undo. */
    val completionId: String? = null,
) {
    /** Completed inside its cycle: waits for the next cycle start. */
    val doneThisCycle: Boolean get() = status == "completed" && cycleEndsAt != null

    /** The editor's starting points state. */
    fun pointsState(): PointsState = PointsState(
        TaskBase(baseMinutes, basePoints?.centi(), pointsFollowTime),
        subtasks.sortedBy { it.position }.map {
            StepValues(it.minutes, it.points.centi(), it.minutesCustom, it.pointsFollowTime)
        },
    )
}

@Serializable
data class ShoppingItem(
    val id: String,
    /** Who created it; decides own vs others' for [PageAccess]. Null = nobody recorded. */
    val createdById: String? = null,
    val listId: String,
    val name: String,
    val quantity: Int = 1,
    val priority: String = "medium",
    val notes: String? = null,
    val estimatedPrice: Double? = null,
    val recurring: Boolean = false,
    val purchased: Boolean = false,
    val category: CategoryRef? = null,
)

@Serializable
data class InventoryItem(
    val id: String,
    /** Who created it; decides own vs others' for [PageAccess]. Null = nobody recorded. */
    val createdById: String? = null,
    val name: String,
    val unit: String? = null,
    val quantity: Double = 0.0,
    val lowThreshold: Double = 0.0,
    val isLow: Boolean = false,
    val reorderIntervalDays: Int? = null,
    val lastRestockedAt: String? = null,
    val predictedDepletionAt: String? = null,
    val category: CategoryRef? = null,
)

@Serializable
data class Bill(
    val id: String,
    /** Who created it; decides own vs others' for [PageAccess]. Null = nobody recorded. */
    val createdById: String? = null,
    val name: String,
    val amount: Double,
    val currency: String? = null,
    val dueDate: String? = null,
    val status: String,
    val category: String? = null,
    val autoPay: Boolean = false,
    val notes: String? = null,
    val recurrence: Recurrence? = null,
    val assignee: UserRef? = null,
    val paidTotal: Double = 0.0,
    val paymentCount: Int = 0,
) {
    val remaining: Double get() = (amount - paidTotal).coerceAtLeast(0.0)
}

@Serializable
data class Notification(
    val id: String,
    val type: String,
    val title: String,
    val body: String? = null,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val read: Boolean = false,
    val createdAt: String,
)

@Serializable
data class NotificationList(
    val items: List<Notification> = emptyList(),
    val unreadCount: Int = 0,
    val hasMore: Boolean = false,
)

// Dashboard (src/server/services/dashboardService.ts) returns raw Prisma rows,
// so these are narrower than the list DTOs above. `overdue`/`upcoming` are
// household-wide; `me` and `doneToday` are null from a server before the
// 2026-10-09 redesign.
@Serializable
data class Dashboard(
    val counts: DashboardCounts,
    val overdue: List<DashboardTask> = emptyList(),
    val upcoming: List<DashboardTask> = emptyList(),
    val upcomingBills: List<DashboardBill> = emptyList(),
    val upcomingEvents: List<EventOccurrence> = emptyList(),
    val me: DashboardMe? = null,
    val doneToday: DoneToday? = null,
)

/** What is on the signed-in user. Requests come from the Requests tab's own fetch (RequestAttention). */
@Serializable
data class DashboardMe(
    /** Mine, overdue or due today. */
    val today: List<DashboardTask> = emptyList(),
    /** Mine, due later this week. */
    val later: List<DashboardTask> = emptyList(),
    /** Unassigned, overdue or due today. */
    val openToAnyone: List<DashboardTask> = emptyList(),
    val points: DashboardPoints? = null,
)

@Serializable
data class DashboardPoints(
    val week: Double = 0.0,
    val queued: Double = 0.0,
    /** Place in the house this week; null until they have points. */
    val rank: Int? = null,
    val leaders: List<PointsLeader> = emptyList(),
)

@Serializable
data class PointsLeader(val userId: String, val name: String, val points: Double)

@Serializable
data class DoneToday(val total: Int = 0, val mine: Int = 0)

@Serializable
data class DashboardCounts(
    val pending: Int = 0,
    val overdue: Int = 0,
    val recurring: Int = 0,
    val lowInventory: Int = 0,
    val openShopping: Int = 0,
    val billsDue: Int = 0,
)

@Serializable
data class DashboardTask(
    val id: String,
    val title: String,
    val dueDate: String? = null,
    val priority: String = "medium",
    val assignee: UserRef? = null,
    /** "Upstairs · Bedroom" — its room/floor; null = whole house or an older server. */
    val place: String? = null,
)

@Serializable
data class DashboardBill(
    val id: String,
    val name: String,
    val amount: Double,
    val currency: String? = null,
    val dueDate: String? = null,
)

@Serializable
data class EventOccurrence(
    val eventId: String,
    val title: String,
    val start: String,
    val allDay: Boolean = false,
    val location: String? = null,
)

/** One row of the household activity log (GET /api/activity, activityService.listActivity). */
@Serializable
data class ActivityEntry(
    val id: String,
    val message: String,
    val createdAt: String,
    val actor: UserRef? = null,
    /** tasks | shopping | inventory | bills | calendar | requests | household (web lib/activityAreas.ts). */
    val area: String = "household",
)

@Serializable
data class ActivityPage(
    val items: List<ActivityEntry> = emptyList(),
    /** Pass back as `before` for the next (older) page; null at the end. */
    val nextBefore: String? = null,
)

/**
 * A household request (RequestDTO) — media or maintenance. Named to avoid
 * clashing with OkHttp's Request.
 */
@Serializable
data class HouseholdRequest(
    val id: String,
    val category: String,
    val title: String,
    // media
    val mediaType: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    // maintenance
    val details: String? = null,
    val assignee: UserRef? = null,
    /** "pending" | "accepted" | "completed" for maintenance; null for media. */
    val status: String? = null,
    /** Done-by date the assignee committed to — the deadline. */
    val dueAt: String? = null,
    val completedAt: String? = null,
    val requester: UserRef,
    val createdAt: String,
)

@Serializable
data class NfcTag(
    val id: String,
    val tagId: String,
    val label: String,
    val represents: String = "consumable",
    val item: UserRef? = null, // { id, name } of the bound item
    /** "open" (scan sheet) | "notify" (quick notification, app stays closed). */
    val scanAction: String = "open",
    val shoppingListId: String? = null,
)

/** Result of "Add to shopping list" from a tag. */
@Serializable
data class NfcShoppingResult(val itemName: String, val listName: String, val added: Boolean)

/** What a scanned tag resolves to (NfcLookupDTO). */
@Serializable
data class NfcLookup(val tagId: String, val tag: NfcTag? = null, val item: InventoryItem? = null)

/** One scan-history entry (NfcScanDTO). */
@Serializable
data class NfcScan(
    val id: String,
    /** "scan" | "register" */
    val kind: String,
    val tagId: String,
    val itemId: String? = null,
    val itemName: String? = null,
    val amount: Double? = null,
    val resultQuantity: Double? = null,
    /** "nfc" (this app) | "home_assistant" */
    val source: String,
    val actorName: String? = null,
    val createdAt: String,
)

@Serializable
data class ShoppingList(
    val id: String,
    /** Who created it; decides own vs others' for [PageAccess]. Null = nobody recorded. */
    val createdById: String? = null,
    val name: String,
    val kind: String,
    val items: List<ShoppingItem> = emptyList(),
    val openCount: Int = 0,
    val purchasedCount: Int = 0,
)

// --- Settings (head + managers) ----------------------------------------------

/** A category as Settings manages it (CategoryAdminDTO). [kind]: task / shopping / inventory / general. */
@Serializable
data class CategoryAdmin(
    val id: String,
    val name: String,
    val kind: String,
    val color: String? = null,
    val icon: String? = null,
)

/** A Home Assistant connection token (IntegrationDTO); the token itself is shown once, on create. */
@Serializable
data class Integration(val id: String, val name: String, val active: Boolean = true, val createdAt: String)

@Serializable
data class CreatedIntegration(val integration: Integration, val token: String)

/** Head only: the points rate and the household calendar (PointsSettingsDTO). */
@Serializable
data class PointsSettings(val timezone: String, val weekStartsOn: Int = 0, val minutesPerPoint: Double)

@Serializable
data class PaperlessConnection(val source: String, val url: String, val publicUrl: String? = null)

@Serializable
data class PaperlessSkipped(val id: Long, val title: String, val reason: String, val url: String? = null)

@Serializable
data class PaperlessSyncResult(
    val checked: Int = 0,
    /** Count per outcome: created / updated / linked / duplicate / skipped. */
    val imported: Map<String, Int> = emptyMap(),
    val skipped: List<PaperlessSkipped> = emptyList(),
)

/** The household's Paperless bill import (PaperlessStatusDTO). [canEdit]: head may change the connection. */
@Serializable
data class PaperlessStatus(
    val configured: Boolean = false,
    val connection: PaperlessConnection? = null,
    val canEdit: Boolean = false,
    val privateNetworkAllowed: Boolean = false,
    val since: String? = null,
    val lastRunAt: String? = null,
    val lastError: String? = null,
    val lastResult: PaperlessSyncResult? = null,
)
