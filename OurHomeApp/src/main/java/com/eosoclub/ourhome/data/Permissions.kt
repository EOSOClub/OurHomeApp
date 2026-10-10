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
    // The Settings page: categories, rooms, Home Assistant tokens, NFC tags,
    // Paperless status. Its head-only parts (points rate, Paperless connection,
    // export) check the role instead, like the web's household:manage.
    SettingsManage,
    // Edit/delete your own requests, accept/finish ones assigned to you.
    // *Submitting* a request is [AccessMatrix.requests] `create`; marking media
    // requests added is its `approve`.
    RequestsWrite,
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
    "manager" to EVERYDAY + Permission.MembersManage + Permission.SettingsManage,
    "member" to EVERYDAY,
    "teen" to EVERYDAY,
    "child" to EVERYDAY,
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
    // Requests only: mark media requests added.
    val approve: Boolean = false,
) {
    fun canEdit(ownerId: String?, userId: String): Boolean =
        if (ownerId != null && ownerId == userId) editOwn else editOthers

    fun canDelete(ownerId: String?, userId: String): Boolean =
        if (ownerId != null && ownerId == userId) deleteOwn else deleteOthers

    /** Any switch on — enough for everyday actions (tick bought, ± stock, scans). */
    val any: Boolean get() = create || editOwn || deleteOwn || editOthers || deleteOthers || approve

    /** Clearing bought items removes only those you may delete. */
    val canDeleteAny: Boolean get() = deleteOwn || deleteOthers

    companion object {
        val ALL = PageAccess(true, true, true, true, true, true)
        val NONE = PageAccess()
        val SUBMIT_ONLY = PageAccess(create = true)
        val SUBMIT_AND_APPROVE = PageAccess(create = true, approve = true)
        val OWN_ONLY = PageAccess(create = true, editOwn = true, deleteOwn = true)
        val ADD_ONLY = PageAccess(create = true)
    }
}

/**
 * Requests uses `create` (submit) and `approve` (mark media added); edit/delete
 * stay requester-only. [shopping] is the items; [shoppingLists] the lists
 * themselves (new / rename / delete).
 */
@Serializable
data class AccessMatrix(
    val tasks: PageAccess = PageAccess.NONE,
    val calendar: PageAccess = PageAccess.NONE,
    val shopping: PageAccess = PageAccess.NONE,
    // A server from before the split has no row; it guarded lists with shopping.
    val shoppingLists: PageAccess? = null,
    val inventory: PageAccess = PageAccess.NONE,
    val bills: PageAccess = PageAccess.NONE,
    val requests: PageAccess = PageAccess.NONE,
) {
    /** List access, falling back to [shopping] where an older server has no row. */
    val lists: PageAccess get() = shoppingLists ?: shopping
}

/**
 * `GET /api/permissions/me` (MyAccessDTO). [features]: the household's
 * turned-on features (null from a server that predates them); [access] is
 * already empty on the pages of turned-off ones.
 */
@Serializable
data class MyAccess(
    val userId: String,
    val role: String,
    val access: AccessMatrix,
    val features: List<String>? = null,
)

/**
 * The web's built-in role defaults (BUILTIN_ROLE_ACCESS). Shown until the real
 * grid loads, and used as-is against a server too old to have
 * /api/permissions/me — there they match what that server enforces.
 */
fun defaultAccess(role: String?): AccessMatrix = when (role) {
    "head" -> AccessMatrix(
        PageAccess.ALL, PageAccess.ALL, PageAccess.ALL, PageAccess.ALL, PageAccess.ALL, PageAccess.ALL,
        PageAccess.ALL,
    )
    "manager", "member" -> AccessMatrix(
        tasks = PageAccess.NONE,
        calendar = PageAccess.ALL,
        shopping = PageAccess.ALL,
        shoppingLists = PageAccess.ALL,
        inventory = PageAccess.ALL,
        bills = PageAccess.ALL,
        requests = if (role == "manager") PageAccess.SUBMIT_AND_APPROVE else PageAccess.SUBMIT_ONLY,
    )
    "teen" -> AccessMatrix(
        calendar = PageAccess.OWN_ONLY,
        shopping = PageAccess.OWN_ONLY,
        shoppingLists = PageAccess.NONE,
        inventory = PageAccess.OWN_ONLY,
        requests = PageAccess.SUBMIT_ONLY,
    )
    "child" -> AccessMatrix(
        shopping = PageAccess.ADD_ONLY,
        shoppingLists = PageAccess.NONE,
        requests = PageAccess.SUBMIT_ONLY,
    )
    "guest" -> AccessMatrix(shoppingLists = PageAccess.NONE, requests = PageAccess.SUBMIT_ONLY)
    else -> AccessMatrix(shoppingLists = PageAccess.NONE)
}
