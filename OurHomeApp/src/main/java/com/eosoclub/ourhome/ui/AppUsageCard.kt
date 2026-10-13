package com.eosoclub.ourhome.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.notifications.UsageReport

/**
 * Profile: tells a child or teen that an adult who looks after them shares
 * their app usage (web appUsageService), and walks them through allowing
 * Usage access, which the phone needs to measure it. Nothing shows otherwise.
 */
@Composable
internal fun AppUsageCard(session: SessionManager) {
    val context = LocalContext.current
    var shared by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(UsageReport.hasAccess(context)) }
    LaunchedEffect(Unit) { shared = runCatching { session.api.myUsageSharing().shared }.getOrDefault(false) }
    // Re-read on return from Android's settings, where Usage access is allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = UsageReport.hasAccess(context) }
    if (!shared) return

    SectionCard("Your app usage is shared", "An adult who looks after you turned this on, and only they can turn it off.") {
        Text(
            "This phone sends how long you spend in each app, about once an hour. Only the adults who look after " +
                "you can see it, and only the last two weeks are kept. Nothing is blocked or limited.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (access) {
            Text("Usage access is allowed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "It needs Usage access. On the next screen, choose Our Home and turn it on. If Android says it's a " +
                    "restricted setting, first open Settings → Apps → Our Home, tap ⋮, and choose Allow restricted settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { context.startActivity(UsageReport.settingsIntent()) }) { Text("Allow usage access") }
        }
    }
}
