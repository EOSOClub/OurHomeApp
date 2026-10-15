package com.eosoclub.ourhome.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.notifications.BatteryAccess
import com.eosoclub.ourhome.notifications.CloseGuard
import com.eosoclub.ourhome.notifications.Push
import com.eosoclub.ourhome.notifications.StayConnected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Profile: how instant alerts reach this phone. When the last one arrived,
 * battery Unrestricted (asked for here), Stay connected, and a test
 * notification to check it all works. For everyone signed in.
 */
@Composable
internal fun InstantAlertsCard(session: SessionManager, showMessage: (String) -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var unrestricted by remember { mutableStateOf(BatteryAccess.isUnrestricted(context)) }
    var lastPush by remember { mutableStateOf(Push.lastReceived(context)) }
    var stayOn by remember { mutableStateOf(StayConnected.isOn(context)) }
    var required by remember { mutableStateOf(StayConnected.isRequired(context)) }
    var guardOn by remember { mutableStateOf(CloseGuard.isEnabled(context)) }
    var testing by remember { mutableStateOf(false) }
    val connected by StayConnected.connected.collectAsStateWithLifecycle()
    // Re-read on return from Android's battery question or settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestricted = BatteryAccess.isUnrestricted(context)
        lastPush = Push.lastReceived(context)
        required = StayConnected.isRequired(context)
        stayOn = StayConnected.isOn(context)
        guardOn = CloseGuard.isEnabled(context)
    }
    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showMessage("This phone has no such settings page")
        }
    }

    SectionCard("Instant alerts", "How alerts from the household reach this phone.") {
        Text(
            lastPush?.let { (at, via) ->
                "The last one arrived ${relativeTime(Instant.ofEpochMilli(at).toString())}, through ${via.label}."
            } ?: "No instant alert has reached this phone yet.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (unrestricted) {
            Text(
                "Battery is Unrestricted, so Android doesn't hold OurHome back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Battery saving can delay alerts, rings and live checks, or stop OurHome in the background. " +
                    "Allow Unrestricted battery so it runs on time.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { context.startActivity(BatteryAccess.requestIntent(context)) }) {
                Text("Allow unrestricted battery")
            }
        }
        BatteryAccess.makerHelpUrl()?.let { url ->
            Text(
                "Some phones also stop apps with their own battery settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (e: ActivityNotFoundException) {
                        showMessage("No browser to open $url")
                    }
                },
            ) { Text("Steps for this phone (dontkillmyapp.com)") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Stay connected", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Keeps a connection to the household's server, so alerts arrive the moment they happen, even " +
                        "where the server has no Firebase. Android lists OurHome under its active apps while it's " +
                        "on, unless Keep Our Home running is on in Accessibility; its notification can be swiped " +
                        "away. Uses a little more battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = stayOn,
                // Kept on while an adult looks after this member.
                enabled = !required,
                onCheckedChange = {
                    stayOn = it
                    StayConnected.setOn(appContext, it)
                },
            )
        }
        if (required) {
            Text(
                "An adult who looks after you keeps this on, so they can always reach your phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Keep Our Home running", style = MaterialTheme.typography.bodyLarge)
            if (guardOn) {
                Text(
                    "On. Android shows no Stop button for Our Home, and its App info can't be opened on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Off, so Android's Stop button can close Our Home. In Accessibility, open Keep Our Home running " +
                        "and turn it on. If Android says it's a restricted setting, open App info first, tap ⋮ and " +
                        "choose Allow restricted settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open(CloseGuard.accessibilitySettings()) }) { Text("Accessibility") }
                    OutlinedButton(onClick = { open(CloseGuard.appInfo(context)) }) { Text("App info") }
                }
            }
        }
        if (stayOn) {
            Text(
                when {
                    connected -> "Connected now."
                    !unrestricted -> "Not connected right now. With battery saving on, Android may not let it " +
                        "start again in the background until you open OurHome."
                    else -> "Not connected right now. It keeps trying."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            enabled = !testing,
            onClick = {
                testing = true
                scope.launch {
                    try {
                        showMessage(session.api.testPush().message)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        showMessage(e.message ?: "Couldn't send the test")
                    } finally {
                        testing = false
                    }
                }
            },
        ) { Text("Send me a test notification") }
    }
}
