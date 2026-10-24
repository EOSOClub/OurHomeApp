package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Blocked apps (parental controls; web appBlockService): a supervising adult
// blocks an app from a child's app usage on People you look after. The
// child's phone learns the list from its check-in (notifications/AppBlocker)
// and covers a blocked app with the blocked screen.

/** An app blocked on this phone, from the check-in's answer. [by]: who blocked it; null once they've left the household. */
@Serializable
data class BlockedAppRule(val app: String, val label: String = app, val by: String? = null)

/**
 * The block for [foreground] (the app just opened), or null. Never for an app
 * this phone always keeps open ([alwaysAllowed]: its phone, messages, home
 * screen, Settings and OurHome), whatever the list says.
 */
fun blockFor(foreground: String?, blocks: List<BlockedAppRule>, alwaysAllowed: Set<String>): BlockedAppRule? {
    if (foreground.isNullOrEmpty() || foreground in alwaysAllowed) return null
    return blocks.firstOrNull { it.app == foreground }
}

/** The blocked screen's line under the app's name. */
fun blockedReason(by: String?): String =
    (by?.let { "$it blocked it" } ?: "An adult who looks after you blocked it") +
        " on your phone. Ask them if you need it."

/** People you look after: an app blocked on a child's phone, who blocked it and when. */
@Serializable
data class BlockedAppEntry(
    val app: String,
    val label: String,
    val icon: Boolean = false,
    val byName: String = "",
    val at: String = "",
)

/**
 * People you look after: the apps blocked on their phone (newest first), the
 * apps their phone always keeps open ([alwaysAllowed], no Block for those),
 * and whether "Keep Our Home running" is on there ([works], which does the
 * blocking; null before a phone that can say checked in). Empty from older servers.
 */
@Serializable
data class LookedAfterBlocking(
    val apps: List<BlockedAppEntry> = emptyList(),
    val alwaysAllowed: List<String> = emptyList(),
    val works: Boolean? = null,
) {
    fun isBlocked(app: String): Boolean = apps.any { it.app == app }
}

/** "Roblox blocked", "Roblox and TikTok blocked", "3 apps blocked" (web look-after-view blockedSummary). */
fun blockedSummary(apps: List<BlockedAppEntry>): String? = when (apps.size) {
    0 -> null
    1 -> "${apps[0].label} blocked"
    2 -> "${apps[0].label} and ${apps[1].label} blocked"
    else -> "${apps.size} apps blocked"
}
