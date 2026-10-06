package com.eosoclub.ourhome.data

/**
 * Mirror of the web app's role matrix (the OurHome web repo,
 * src/lib/permissions.ts). Used only to hide controls a role can't use — the
 * server enforces the real rules. Keep in sync; see that repo's
 * docs/permissions.md.
 */
enum class Permission {
    TasksWrite,
    TasksComplete,
    ShoppingWrite,
    InventoryWrite,
    BillsWrite,
    MembersManage,
    // Make requests; editing/deleting is limited to your own on the server.
    RequestsWrite,
    // Accept movie/TV requests and mark them available (head-only). Holders
    // get the "requests waiting" reminders for media.
    RequestsManageMedia,
    // File a bug report (everyone).
    BugsReport,
    // Receive bug reports — bell + phone notification (head-only).
    BugsManage,
}

private val MODULE_WRITES = setOf(
    Permission.ShoppingWrite,
    Permission.InventoryWrite,
    Permission.BillsWrite,
    Permission.TasksComplete,
    Permission.RequestsWrite,
    Permission.BugsReport,
)

private val ROLE_PERMISSIONS: Map<String, Set<Permission>> = mapOf(
    "head" to Permission.entries.toSet(),
    // tasks:write (create/edit/delete tasks) is head-only.
    "manager" to MODULE_WRITES + Permission.MembersManage,
    "member" to MODULE_WRITES,
    "guest" to setOf(Permission.TasksComplete, Permission.RequestsWrite, Permission.BugsReport),
)

/** True when [role] grants [permission]; unknown roles get nothing. */
fun can(role: String?, permission: Permission): Boolean =
    ROLE_PERMISSIONS[role]?.contains(permission) == true
