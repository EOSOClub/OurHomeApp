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
data class ErrorBody(val message: String? = null)

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

@Serializable
data class Member(val id: String, val name: String)

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
    val nextRunAt: String? = null,
)

@Serializable
data class Subtask(val id: String, val title: String, val done: Boolean = false, val position: Int = 0)

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
    val estimatedMinutes: Int? = null,
    val category: CategoryRef? = null,
    val assignee: UserRef? = null,
    val recurrence: Recurrence? = null,
    val subtasks: List<Subtask> = emptyList(),
)

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
    val read: Boolean = false,
    val createdAt: String,
)

@Serializable
data class NotificationList(val items: List<Notification> = emptyList(), val unreadCount: Int = 0)

// Dashboard (src/server/services/dashboardService.ts) returns raw Prisma rows,
// so these are narrower than the list DTOs above.
@Serializable
data class Dashboard(
    val counts: DashboardCounts,
    val overdue: List<DashboardTask> = emptyList(),
    val upcoming: List<DashboardTask> = emptyList(),
    val upcomingBills: List<DashboardBill> = emptyList(),
    val upcomingEvents: List<EventOccurrence> = emptyList(),
    val recentActivity: List<ActivityEntry> = emptyList(),
)

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

@Serializable
data class ActivityEntry(
    val id: String,
    val message: String,
    val createdAt: String,
    val actor: UserRef? = null,
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
