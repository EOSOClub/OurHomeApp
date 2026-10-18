package com.eosoclub.ourhome.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.PhoneSetup
import com.eosoclub.ourhome.data.PhoneSetupChecks
import com.eosoclub.ourhome.data.SetupStep
import com.eosoclub.ourhome.data.SetupStepState
import com.eosoclub.ourhome.data.mayBeRestricted
import com.eosoclub.ourhome.data.setupSteps
import com.eosoclub.ourhome.location.Geofences
import com.eosoclub.ourhome.notifications.BatteryAccess
import com.eosoclub.ourhome.notifications.CloseGuard
import com.eosoclub.ourhome.notifications.PhoneCheckIn
import com.eosoclub.ourhome.notifications.RequestReminders
import com.eosoclub.ourhome.notifications.UsageReport
import kotlinx.coroutines.CancellationException

// Set up this phone: on a supervised child's phone, one checklist of what the
// adults who look after them asked for (web GET /api/phone/setup), in the
// order it has to be done (data/PhoneSetupSteps). Opens by itself after
// sign-in while something is missing, and from the menu any time. Each step
// re-checks when the phone comes back from Android's settings, and the phone
// checks in at once so the adults see it.

/** Reads what this phone has on, for Set up this phone. */
internal object PhoneSetupDevice {
    fun checks(context: Context): PhoneSetupChecks = PhoneSetupChecks(
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        locationAlways = Geofences.hasFineLocation(context) && Geofences.hasBackgroundLocation(context),
        batteryUnrestricted = BatteryAccess.isUnrestricted(context),
        usageAccess = UsageReport.hasAccess(context),
        preventUninstall = CloseGuard.isAdminActive(context) && CloseGuard.hasPin(context),
        keepRunning = CloseGuard.isEnabled(context),
    )

    fun steps(context: Context, setup: PhoneSetup): List<SetupStepState> =
        if (setup.supervised) setupSteps(setup.required, checks(context)) else emptyList()

    /**
     * Whether Android restricts settings for this install: installed from a
     * file (a downloaded APK), Android 13 or later. Unknown counts as yes, so
     * the hint shows rather than leaving someone stuck.
     */
    fun restrictedInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val source = context.packageManager.getInstallSourceInfo(context.packageName).packageSource
            source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        }.getOrDefault(true)
    }
}

@Composable
internal fun PhoneSetupScreen(
    api: ApiClient,
    showMessage: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var setup by remember { mutableStateOf<PhoneSetup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf(PhoneSetupDevice.checks(context)) }
    var fine by remember { mutableStateOf(Geofences.hasFineLocation(context)) }
    var hasPin by remember { mutableStateOf(CloseGuard.hasPin(context)) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    val restrictedInstall = remember { PhoneSetupDevice.restrictedInstall(context) }

    // Each Try again loads once more.
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        try {
            setup = api.phoneSetup()
            error = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: "Couldn't load what this phone needs"
        }
    }

    /** Re-reads the phone; anything that changed is told to the server at once. */
    fun recheck() {
        val before = checks
        val now = PhoneSetupDevice.checks(context)
        fine = Geofences.hasFineLocation(context)
        hasPin = CloseGuard.hasPin(context)
        checks = now
        if (now != before) {
            if (now.locationAlways && !before.locationAlways) Geofences.syncNow(appContext, force = true)
            PhoneCheckIn.sendNow(appContext)
        }
    }
    // Back from Android's settings, a permission prompt or the Device admin page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recheck() }

    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showMessage("This phone has no such settings page")
        }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        recheck()
        if (granted) {
            RequestReminders.notificationsAllowed(context)
        } else {
            showMessage("Notifications are still off. Use Open notification settings to turn them on.")
        }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck()
        if (!Geofences.hasFineLocation(context)) showMessage("Choose \"Precise\" so the phone can tell where you are.")
    }
    // Android 11+ opens its settings page for this; the choice is "Allow all the time".
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val current = setup
        if (current == null) {
            Text(
                error ?: "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { error = null; attempt++ }) { Text("Try again") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
            return@Column
        }
        if (!current.supervised) {
            Text(
                "Nobody looks after you in the household right now, so this phone has nothing to set up.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onClose) { Text("Done") }
            return@Column
        }

        val steps = setupSteps(current.required, checks)
        val done = steps.count { it.done }
        Text(
            "An adult who looks after you asked for these on this phone. Do them in order: a parent may need to " +
                "help with some of them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (done == steps.size) "All ${steps.size} done." else "$done of ${steps.size} done.",
            style = MaterialTheme.typography.titleMedium,
        )

        steps.forEachIndexed { index, state ->
            StepCard(index + 1, state) {
                when (state.step) {
                    SetupStep.Notifications -> {
                        StepText("So you get the household's alerts, and can stop a ring from its notification.")
                        if (!state.done) {
                            Button(
                                onClick = {
                                    // Android 13+ asks; before that (or once refused) it's a settings switch.
                                    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    if (canAsk) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else open(notificationSettings(context))
                                },
                            ) { Text("Allow notifications") }
                            TextButton(onClick = { open(notificationSettings(context)) }) { Text("Open notification settings") }
                        }
                    }
                    SetupStep.Location -> {
                        StepText(
                            "Your location sharing is required, so the household can see where you are. Android " +
                                "asks twice: first \"While using the app\" with Precise on, then \"Allow all the time\".",
                        )
                        if (!state.done) {
                            if (!fine) {
                                Button(
                                    onClick = {
                                        askLocation.launch(
                                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                        )
                                    },
                                ) { Text("Allow location") }
                            } else {
                                Button(onClick = { askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                                    Text("Allow all the time")
                                }
                                // If Android stops showing the prompt (asked before), the setting is still there.
                                TextButton(onClick = { open(appDetails(context)) }) { Text("Open app settings instead") }
                            }
                        }
                    }
                    SetupStep.Battery -> {
                        StepText("So battery saving doesn't hold back rings and alerts, or stop OurHome in the background.")
                        if (!state.done) {
                            Button(onClick = { open(BatteryAccess.requestIntent(context)) }) { Text("Allow unrestricted battery") }
                        }
                    }
                    SetupStep.UsageAccess -> {
                        StepText(
                            "So the adults who look after you can see how long you spend in each app. On the next " +
                                "screen, find Our Home and turn it on.",
                        )
                        if (!state.done) {
                            RestrictedHint(state.step, restrictedInstall, onAppInfo = { open(CloseGuard.appInfo(context)) })
                            Button(onClick = { open(UsageReport.settingsIntent()) }) { Text("Open Usage access") }
                        }
                    }
                    SetupStep.PreventUninstall -> {
                        StepText(
                            "A parent does this one. They choose a PIN, then activate Device admin, so OurHome can't " +
                                "be uninstalled without the PIN.",
                        )
                        if (!state.done) {
                            RestrictedHint(state.step, restrictedInstall, onAppInfo = { open(CloseGuard.appInfo(context)) })
                            if (!hasPin) {
                                Button(onClick = { showSetPinDialog = true }) { Text("Set parent PIN") }
                            } else {
                                Button(onClick = { open(CloseGuard.activateAdminIntent(context)) }) {
                                    Text("Activate Device admin")
                                }
                            }
                        }
                    }
                    SetupStep.KeepRunning -> {
                        StepText(
                            "So OurHome can't be closed. In Accessibility, open Keep Our Home running (it may be " +
                                "under Downloaded apps or Installed apps) and turn it on. It's last on purpose: once " +
                                "it's on, App info, Device admin and Accessibility can't be opened on this phone.",
                        )
                        if (!state.done) {
                            RestrictedHint(state.step, restrictedInstall, onAppInfo = { open(CloseGuard.appInfo(context)) })
                            Button(onClick = { open(CloseGuard.accessibilitySettings()) }) { Text("Open Accessibility") }
                        }
                    }
                }
            }
        }

        if (done == steps.size) {
            Button(onClick = onClose) { Text("Done") }
        } else {
            TextButton(onClick = onClose) { Text("Finish later") }
            StepText("You can come back to this from the menu: Set up this phone.")
        }
    }

    if (showSetPinDialog) {
        SetParentPinDialog(
            requireCurrentPin = false,
            initialDuration = CloseGuard.emergencyDuration(appContext),
            onVerifyCurrentPin = { current -> CloseGuard.verifyPin(appContext, current) },
            onDismiss = { showSetPinDialog = false },
            onSave = { pin, chosenDuration ->
                CloseGuard.setPin(appContext, pin, chosenDuration)
                showSetPinDialog = false
                hasPin = true
                if (!CloseGuard.isAdminActive(context)) {
                    open(CloseGuard.activateAdminIntent(context))
                } else {
                    recheck()
                    showMessage("Parent PIN and ${chosenDuration.label} emergency timer saved")
                }
            },
        )
    }
}

/** One step: its number and name, a tick when done, then what it's for and its buttons. */
@Composable
private fun StepCard(number: Int, state: SetupStepState, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$number. ${state.step.title}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (state.done) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)
                }
            }
            content()
        }
    }
}

@Composable
private fun StepText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** How to get past Android's "Restricted setting" refusal, on the steps it can refuse. */
@Composable
private fun RestrictedHint(step: SetupStep, restrictedInstall: Boolean, onAppInfo: () -> Unit) {
    if (!mayBeRestricted(step, restrictedInstall, Build.VERSION.SDK_INT)) return
    Text(
        "If Android says it's a restricted setting: tap OK, then open App info, tap ⋮ at the top and choose " +
            "Allow restricted settings. Then try again.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onAppInfo) { Text("App info") }
}

private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
