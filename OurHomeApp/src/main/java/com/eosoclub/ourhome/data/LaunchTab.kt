package com.eosoclub.ourhome.data

/**
 * Stands in for the tap id of a notification posted before tap ids existed:
 * its tab is handled once, then marked like any other.
 */
const val NO_TAP_ID = -1L

/**
 * What MainActivity does with its launch intent's tab: [open] it (null: leave
 * the screen as it is), and the tap id to keep as handled in its saved state.
 */
data class LaunchTabDecision(val open: String?, val handledTapId: Long?)

/**
 * The tab a tapped notification asks for when MainActivity is created.
 *
 * Every notification that opens a tab carries a tap id, new each time it is
 * built ([tapId]; null from one posted before tap ids). Android hands a
 * re-created activity its original intent again: when it's reopened from
 * Recents ([launchedFromHistory], FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), and
 * after a rotation or after Android closed the app in the background. That
 * replays the old tap id, which matches [lastHandledTapId] (kept in the saved
 * state), so it's skipped. A new tap has a new id, so it always opens its tab,
 * even when it relaunches the app over a saved screen. A tap while the
 * activity exists goes through onNewIntent instead.
 */
fun launchTab(tab: String?, tapId: Long?, launchedFromHistory: Boolean, lastHandledTapId: Long?): LaunchTabDecision {
    if (tab == null) return LaunchTabDecision(null, lastHandledTapId)
    val id = tapId ?: NO_TAP_ID
    return LaunchTabDecision(tab.takeUnless { launchedFromHistory || id == lastHandledTapId }, id)
}
