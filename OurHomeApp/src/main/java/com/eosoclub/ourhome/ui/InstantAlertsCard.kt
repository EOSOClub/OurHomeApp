package com.eosoclub.ourhome.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eosoclub.ourhome.data.AuthState
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
 * battery Unrestricted (asked for here), Stay connected, Keep Our Home running
 * + Prevent uninstall (Device admin & parent PIN on supervised/kid phones),
 * and a test notification to check it all works.
 */
@Composable
internal fun InstantAlertsCard(session: SessionManager, showMessage: (String) -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val authState by session.state.collectAsStateWithLifecycle()
    val role = (authState as? AuthState.SignedIn)?.user?.role
    var unrestricted by remember { mutableStateOf(BatteryAccess.isUnrestricted(context)) }
    var lastPush by remember { mutableStateOf(Push.lastReceived(context)) }
    var stayOn by remember { mutableStateOf(StayConnected.isOn(context)) }
    var required by remember { mutableStateOf(StayConnected.isRequired(context)) }
    var guardOn by remember { mutableStateOf(CloseGuard.isEnabled(context)) }
    var adminOn by remember { mutableStateOf(CloseGuard.isAdminActive(context)) }
    var hasPin by remember { mutableStateOf(CloseGuard.hasPin(context)) }
    var emergencyDuration by remember { mutableStateOf(CloseGuard.emergencyDuration(context)) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val connected by StayConnected.connected.collectAsStateWithLifecycle()

    fun refreshLocalState() {
        unrestricted = BatteryAccess.isUnrestricted(context)
        lastPush = Push.lastReceived(context)
        required = StayConnected.isRequired(context)
        stayOn = StayConnected.isOn(context)
        guardOn = CloseGuard.isEnabled(context)
        adminOn = CloseGuard.isAdminActive(context)
        hasPin = CloseGuard.hasPin(context)
        emergencyDuration = CloseGuard.emergencyDuration(context)
    }

    // Re-read on return from Android's battery question, Device admin prompt, or settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshLocalState()
    }
    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showMessage("This phone has no such settings page")
        }
    }

    val showKidProtection = required || adminOn || hasPin || role in setOf("child", "teen")

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
        if (showKidProtection) {
            if (required) {
                Text(
                    "An adult who looks after you keeps this on, so they can always reach your phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Keep Our Home running", style = MaterialTheme.typography.bodyLarge)
            if (guardOn) {
                Text(
                    "On. Android shows no Stop button for Our Home, and its App info, Device admin and Accessibility pages can't be opened on this phone.",
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

            Text("Prevent uninstall", style = MaterialTheme.typography.bodyLarge)
            if (adminOn && hasPin) {
                Text(
                    "On. Android blocks uninstalling Our Home until a parent unlocks it here with the parent PIN. " +
                        "Forgot-PIN emergency unlock: ${emergencyDuration.label} (${emergencyDuration.scheduleNote}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showUnlockDialog = true }) {
                        Text("Unlock or uninstall…")
                    }
                    TextButton(onClick = { showSetPinDialog = true }) {
                        Text("Change PIN or timer…")
                    }
                }
            } else if (adminOn) {
                Text(
                    "Device admin is on, but no parent PIN is set yet. Choose a parent PIN and forgot-PIN timer to lock removal.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showSetPinDialog = true }) { Text("Set parent PIN") }
                    OutlinedButton(
                        onClick = {
                            CloseGuard.unlockAndRemoveAdmin(appContext)
                            refreshLocalState()
                            showMessage("Uninstall lock turned off")
                        },
                    ) { Text("Turn off") }
                }
            } else {
                Text(
                    "Off, so Our Home can still be removed from the home screen. Set a parent PIN and activate " +
                        "Device admin so Android blocks uninstalling Our Home without the PIN.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (hasPin) {
                                open(CloseGuard.activateAdminIntent(context))
                            } else {
                                showSetPinDialog = true
                            }
                        },
                    ) { Text("Prevent uninstall") }
                    if (hasPin) {
                        OutlinedButton(onClick = { showUnlockDialog = true }) {
                            Text("Unlock with PIN…")
                        }
                    }
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

    if (showSetPinDialog) {
        SetParentPinDialog(
            requireCurrentPin = hasPin,
            initialDuration = emergencyDuration,
            onVerifyCurrentPin = { current -> CloseGuard.verifyPin(appContext, current) },
            onDismiss = { showSetPinDialog = false },
            onSave = { pin, chosenDuration ->
                CloseGuard.setPin(appContext, pin, chosenDuration)
                showSetPinDialog = false
                refreshLocalState()
                if (!CloseGuard.isAdminActive(context)) {
                    open(CloseGuard.activateAdminIntent(context))
                } else {
                    showMessage("Parent PIN and ${chosenDuration.label} emergency timer saved")
                }
            },
        )
    }

    if (showUnlockDialog) {
        UnlockOurHomeDialog(
            onDismiss = { showUnlockDialog = false },
            onUnlocked = { uninstall ->
                showUnlockDialog = false
                refreshLocalState()
                if (uninstall) {
                    open(CloseGuard.uninstallIntent(context))
                } else {
                    showMessage("Unlocked for 2 minutes and Device admin removed")
                }
            },
        )
    }
}

/**
 * Shown on LoginScreen and on ProfileScreen's offline error state whenever
 * Device admin, Accessibility close guard, or a parent PIN is active, so a
 * parent can always unlock or uninstall Our Home without an internet connection.
 */
@Composable
internal fun OfflineUnlockSection(showMessage: (String) -> Unit = {}) {
    val context = LocalContext.current
    var active by remember {
        mutableStateOf(
            CloseGuard.isAdminActive(context) || CloseGuard.isEnabled(context) || CloseGuard.hasPin(context),
        )
    }
    var showDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        active = CloseGuard.isAdminActive(context) || CloseGuard.isEnabled(context) || CloseGuard.hasPin(context)
    }

    if (!active) return

    OutlinedButton(onClick = { showDialog = true }) {
        Text("Unlock or uninstall Our Home…")
    }

    if (showDialog) {
        UnlockOurHomeDialog(
            onDismiss = { showDialog = false },
            onUnlocked = { uninstall ->
                showDialog = false
                active = CloseGuard.isAdminActive(context) || CloseGuard.isEnabled(context) || CloseGuard.hasPin(context)
                if (uninstall) {
                    runCatching { context.startActivity(CloseGuard.uninstallIntent(context)) }
                } else {
                    showMessage("Unlocked for 2 minutes and Device admin removed")
                }
            },
        )
    }
}

@Composable
private fun SetParentPinDialog(
    requireCurrentPin: Boolean,
    initialDuration: CloseGuard.EmergencyDuration,
    onVerifyCurrentPin: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (pin: String, duration: CloseGuard.EmergencyDuration) -> Unit,
) {
    var currentPin by remember { mutableStateOf("") }
    var currentError by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(initialDuration) }
    val valid = (!requireCurrentPin || CloseGuard.isValidPin(currentPin.trim())) &&
        CloseGuard.isValidPin(pin.trim()) &&
        pin.trim() == confirm.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (requireCurrentPin) "Change parent PIN or timer" else "Set parent PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose a 4 to 8 digit PIN and how long the forgot-PIN emergency unlock takes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (requireCurrentPin) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = {
                            if (it.length <= 8 && it.all(Char::isDigit)) {
                                currentPin = it
                                currentError = null
                            }
                        },
                        label = { Text("Current parent PIN") },
                        singleLine = true,
                        isError = currentError != null,
                        supportingText = currentError?.let { msg -> { Text(msg) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                    label = { Text(if (requireCurrentPin) "New parent PIN (4 to 8 digits)" else "Parent PIN (4 to 8 digits)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) confirm = it },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownField(
                    label = "Forgot-PIN unlock timer",
                    options = CloseGuard.EmergencyDuration.entries.map { it.key to it.label },
                    value = duration.key,
                    onSelect = { key -> duration = CloseGuard.EmergencyDuration.fromKey(key) },
                )
                Text(
                    duration.scheduleNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    if (requireCurrentPin && !onVerifyCurrentPin(currentPin.trim())) {
                        currentError = "Incorrect current PIN"
                        return@Button
                    }
                    onSave(pin.trim(), duration)
                },
            ) {
                Text("Save & continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * "Sign out?" Signing out turns Prevent uninstall off and forgets the parent
 * PIN ([CloseGuard.signedOut]), so while it's on with a PIN, signing out
 * needs that PIN: otherwise a child could sign out and then uninstall.
 */
@Composable
internal fun SignOutDialog(who: String, onDismiss: () -> Unit, onSignOut: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val needsPin = remember { CloseGuard.isAdminActive(appContext) && CloseGuard.hasPin(appContext) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Signed in as $who.")
                if (needsPin) {
                    Text(
                        "Prevent uninstall is on, and signing out turns it off. Enter the parent PIN to sign out.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 8 && it.all(Char::isDigit)) {
                                pin = it
                                error = null
                            }
                        },
                        label = { Text("Parent PIN") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { msg -> { Text(msg) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !needsPin || CloseGuard.isValidPin(pin),
                onClick = {
                    if (needsPin && !CloseGuard.verifyPin(appContext, pin)) {
                        val wait = CloseGuard.pinLockoutRemainingSec(appContext)
                        error = if (wait > 0) "Too many attempts. Try again in ${wait}s." else "Incorrect PIN"
                        return@TextButton
                    }
                    onSignOut()
                },
            ) { Text("Sign out") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun UnlockOurHomeDialog(
    onDismiss: () -> Unit,
    onUnlocked: (uninstall: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val hasPin = remember { CloseGuard.hasPin(appContext) }
    val duration = remember { CloseGuard.emergencyDuration(appContext) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var emergencyReadyAt by remember { mutableStateOf(CloseGuard.emergencyReadyAtMs(appContext)) }
    var showEmergency by remember { mutableStateOf(!hasPin || emergencyReadyAt > 0L) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            nowMs = System.currentTimeMillis()
            emergencyReadyAt = CloseGuard.emergencyReadyAtMs(appContext)
            if (emergencyReadyAt > 0L) showEmergency = true
        }
    }

    val lockoutSec = CloseGuard.pinLockoutRemainingSec(appContext, nowMs)
    val emergencyRemainingSec = if (emergencyReadyAt > nowMs) (((emergencyReadyAt - nowMs) + 999L) / 1000L).toInt() else 0
    val emergencyReady = emergencyReadyAt > 0L && nowMs >= emergencyReadyAt

    fun tryPinUnlock(uninstall: Boolean) {
        val wait = CloseGuard.pinLockoutRemainingSec(appContext)
        if (wait > 0) {
            error = "Too many attempts. Try again in ${wait}s."
            return
        }
        if (CloseGuard.unlockWithPin(appContext, pin)) {
            onUnlocked(uninstall)
        } else {
            val nextWait = CloseGuard.pinLockoutRemainingSec(appContext)
            error = if (nextWait > 0) "Too many attempts. Try again in ${nextWait}s." else "Incorrect PIN"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock Our Home") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPin) {
                    Text(
                        "Enter the parent PIN to remove Device admin and pause Keep Our Home running for 2 minutes, or uninstall Our Home.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 8 && it.all(Char::isDigit)) {
                                pin = it
                                error = null
                            }
                        },
                        label = { Text("Parent PIN") },
                        singleLine = true,
                        enabled = lockoutSec == 0,
                        isError = error != null || lockoutSec > 0,
                        supportingText = (if (lockoutSec > 0) "Too many attempts. Try again in ${lockoutSec}s." else error)
                            ?.let { msg -> { Text(msg) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (!showEmergency) {
                    TextButton(onClick = { showEmergency = true }) {
                        Text("Forgot PIN or no internet?")
                    }
                } else {
                    Text(
                        "Uninstall without PIN or internet:",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "1. ${duration.label} emergency unlock: once started, this timer cannot be canceled (${duration.scheduleNote}). Once it finishes, tap Complete emergency uninstall.\n" +
                            "2. Or use Android Safe Mode: hold the Power button, touch & hold Power off to reboot into Safe mode, then deactivate Our Home in Settings → Security → Device admin apps and uninstall.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        emergencyReady -> {
                            Button(
                                onClick = {
                                    if (CloseGuard.completeEmergencyUnlock(appContext)) {
                                        onUnlocked(true)
                                    }
                                },
                            ) {
                                Text("Complete emergency uninstall")
                            }
                        }
                        emergencyReadyAt > 0L -> {
                            val hours = emergencyRemainingSec / 3600
                            val mins = (emergencyRemainingSec % 3600) / 60
                            val secs = emergencyRemainingSec % 60
                            val formatted = if (hours > 0) {
                                "%d:%02d:%02d".format(hours, mins, secs)
                            } else {
                                "%d:%02d".format(mins, secs)
                            }
                            Text(
                                "Emergency unlock ready in $formatted. It can't be cancelled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        else -> {
                            OutlinedButton(
                                onClick = {
                                    emergencyReadyAt = CloseGuard.startEmergencyUnlock(appContext)
                                    nowMs = System.currentTimeMillis()
                                },
                            ) {
                                Text("Start ${duration.label} emergency unlock")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (hasPin) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = lockoutSec == 0 && CloseGuard.isValidPin(pin),
                        onClick = { tryPinUnlock(uninstall = false) },
                    ) {
                        Text("Unlock only")
                    }
                    Button(
                        enabled = lockoutSec == 0 && CloseGuard.isValidPin(pin),
                        onClick = { tryPinUnlock(uninstall = true) },
                    ) {
                        Text("Uninstall")
                    }
                }
            } else {
                Button(
                    onClick = {
                        CloseGuard.unlockAndRemoveAdmin(appContext)
                        onUnlocked(true)
                    },
                ) {
                    Text("Unlock & uninstall")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

