package com.eosoclub.ourhome.data

/**
 * The features the server admin left on for this household (web
 * `lib/features.ts`; GET /api/permissions/me `features`). A turned-off one
 * loses its tab, menu entry and dashboard card; the server refuses its API
 * anyway (403 `feature_disabled`). Home, activity, notifications and profile
 * are always on.
 */
data class Features(private val on: Set<String>) {
    val tasks get() = TASKS in on
    val points get() = POINTS in on
    val calendar get() = CALENDAR in on
    val shopping get() = SHOPPING in on
    val inventory get() = INVENTORY in on
    val bills get() = BILLS in on
    val requests get() = REQUESTS in on

    /** For caching between launches. */
    fun names(): Set<String> = on

    companion object {
        const val TASKS = "tasks"
        const val POINTS = "points"
        const val CALENDAR = "calendar"
        const val SHOPPING = "shopping"
        const val INVENTORY = "inventory"
        const val BILLS = "bills"
        const val REQUESTS = "requests"

        val ALL = Features(setOf(TASKS, POINTS, CALENDAR, SHOPPING, INVENTORY, BILLS, REQUESTS))

        /**
         * From the server's list. null = a server from before features
         * existed: everything on. Points follow Tasks, as on the web.
         */
        fun from(names: Collection<String>?): Features {
            if (names == null) return ALL
            val on = names.toMutableSet()
            if (TASKS !in on) on.remove(POINTS)
            return Features(on)
        }
    }
}
