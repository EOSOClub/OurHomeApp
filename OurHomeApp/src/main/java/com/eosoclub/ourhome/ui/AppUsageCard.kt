package com.eosoclub.ourhome.ui

import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.UsageHistory
import com.eosoclub.ourhome.notifications.CloseGuard
import com.eosoclub.ourhome.notifications.UsageReport

/**
 * Profile: tells a child or teen that an adult who looks after them shares
 * their app usage (web appUsageService), walks them through allowing Usage
 * access, which the phone needs to measure it, and shows them the same
 * numbers the adult sees. Nothing shows otherwise.
 */
@Composable
internal fun AppUsageCard(session: SessionManager) {
    val context = LocalContext.current
    var shared by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(UsageReport.hasAccess(context)) }
    var history by remember { mutableStateOf<UsageHistory?>(null) }
    var historyFailed by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val iconCache = remember { UsageIcons(session.api, scope) }
    val icons by iconCache.icons.collectAsState()
    LaunchedEffect(Unit) { shared = runCatching { session.api.myUsageSharing().shared }.getOrDefault(false) }
    // Re-read on return from Android's settings, where Usage access is allowed,
    // and from elsewhere, so the numbers are current.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = UsageReport.hasAccess(context)
        resumes++
    }
    LaunchedEffect(shared, resumes) {
        if (!shared) return@LaunchedEffect
        runCatching { session.api.myUsageHistory() }
            .onSuccess {
                history = it
                historyFailed = false
            }
            .onFailure { historyFailed = history == null }
    }
    if (!shared) return

    SectionCard("Your app usage is shared", "An adult who looks after you turned this on, and only they can turn it off.") {
        Text(
            "This phone sends how long you spend in each app, about every 15 minutes. You and the adults who look " +
                "after you can see it, and only the last two weeks are kept. They're also told when you install a new " +
                "app. Nothing is blocked or limited.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (access) {
            Text("Usage access is allowed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "It needs Usage access. On the next screen, choose Our Home and turn it on. " + CloseGuard.RESTRICTED_HINT,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { context.startActivity(UsageReport.settingsIntent()) }) { Text("Allow usage access") }
        }
        HorizontalDivider()
        val loaded = history
        when {
            loaded != null -> UsageHistoryView(loaded, icons, iconCache::load)
            historyFailed -> Text(
                "Couldn't load your app usage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text("Loading...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
