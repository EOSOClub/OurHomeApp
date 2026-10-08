package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

/**
 * Fixed role abilities — mirror of the web app's role matrix (the OurHome web
 * repo, src/lib/permissions.ts `PERMISSIONS`). Used only to hide controls; the
 * server enforces the real rules. See that repo's docs/permissions.md.
 *
 * Add/edit/delete on the feature pages is NOT here: it's the per-person
 * [AccessMatrix] the head edits on the website (Members → Permissions), loaded
 * from `GET /api/permissions/me`.
 */
enum class Permission {
    TasksComplete,
    MembersManage,
    // Edit/delete your own requests, accept/finish ones assigned to you.
    // *Submitting* a request is [AccessMatrix.requests] `create`.
    RequestsWrite,
    // Accept movie/TV requests and mark them available (head-only). Holders
    // get the "requests waiting" reminders for media.
    RequestsManageMedia,
    // File a bug report (everyone).
    BugsReport,
    // Receive bug reports — bell + phone notification (head-only).
    BugsManage,
}

private val EVERYDAY = setOf(
    Permission.TasksComplete,
    Permission.RequestsWrite,
    Permission.BugsReport,
)

private val ROLE_PERMISSIONS: Map<String, Set<Permission>> = mapOf(
    "head" to Permission.entries.toSet(),
    "manager" to EVERYDAY + Permission.MembersManage,
    "member" to EVERYDAY,
    "guest" to EVERYDAY,
)

/** True when [role] grants [permission]; unknown roles get nothing. */
fun can(role: String?, permission: Permission): Boolean =
    ROLE_PERMISSIONS[role]?.contains(permission) == true

// --- Page access ------------------------------------------------------------

/**
 * One page's switches. "Own" = records the user created (`createdById`);
 * records with no creator (imported bills, email events) are others'.
 */
@Serializable
data class PageAccess(
    val create: Boolean = false,
    val editOwn: Boolean = false,
    val deleteOwn: Boolean = false,
    val editOthers: Boolean = false,
    val deleteOthers: Boolean = false,
) {
    fun canEdit(ownerId: String?, userId: String): Boolean =
        if (ownerId != null && ownerId == userId) editOwn else editOthers

    fun canDelete(ownerId: String?, userId: String): Boolean =
        if (ownerId != null && ownerId == userId) deleteOwn else deleteOthers

    /** Any switch on — enough for everyday actions (tick bought, ± stock, scans). */
    val any: Boolean get() = create || editOwn || deleteOwn || editOthers || deleteOthers

    /** Clearing bought items removes only those you may delete. */
    val canDeleteAny: Boolean get() = deleteOwn || deleteOthers

    companion object {
        val ALL = PageAccess(true, true, true, true, true)
        val NONE = PageAccess()
        val SUBMIT_ONLY = PageAccess(create = true)
    }
}

/** Requests only uses `create` (submit); edit/delete stay requester-only. */
@Serializable
data class AccessMatrix(
    val tasks: PageAccess = PageAccess.NONE,
    val calendar: PageAccess = PageAccess.NONE,
    val shopping: PageAccess = PageAccess.NONE,
    val inventory: PageAccess = PageAccess.NONE,
    val bills: PageAccess = PageAccess.NONE,
    val requests: PageAccess = PageAccess.NONE,
)

/** `GET /api/permissions/me` (MyAccessDTO). */
@Serializable
data class MyAccess(val userId: String, val role: String, val access: AccessMatrix)

/**
 * The web's built-in role defaults (BUILTIN_ROLE_ACCESS). Shown until the real
 * grid loads, and used as-is against a server too old to have
 * /api/permissions/me — there they match what that server enforces.
 */
fun defaultAccess(role: String?): AccessMatrix = when (role) {
    "head" -> AccessMatrix(
        PageAccess.ALL, PageAccess.ALL, PageAccess.ALL, PageAccess.ALL, PageAccess.ALL, PageAccess.ALL,
    )
    "manager", "member" -> AccessMatrix(
        tasks = PageAccess.NONE,
        calendar = PageAccess.ALL,
        shopping = PageAccess.ALL,
        inventory = PageAccess.ALL,
        bills = PageAccess.ALL,
        requests = PageAccess.SUBMIT_ONLY,
    )
    "guest" -> AccessMatrix(requests = PageAccess.SUBMIT_ONLY)
    else -> AccessMatrix()
}
