package com.eosoclub.ourhome.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.LocationConsent
import com.eosoclub.ourhome.data.LocationSetup
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.locationSetup
import com.eosoclub.ourhome.location.Geofences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Profile → Location (web: LocationCard), plus this phone's permissions. */
class LocationViewModel(private val session: SessionManager) : ViewModel() {
    data class UiState(
        val consent: LocationConsent? = null,
        /** How many map places the household has (null = not loaded). */
        val places: Int? = null,
        val saving: Boolean = false,
        val error: String? = null,
    )

    private val api = session.api
    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        try {
            val consent = api.locationConsent()
            val places = runCatching { api.mapPlaces().size }.getOrNull()
            state.update { it.copy(consent = consent, places = places, error = null) }
        } catch (e: Exception) {
            state.update { it.copy(error = e.message ?: "Couldn't load your location settings") }
        }
    }

    fun setSharing(context: Context, on: Boolean) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            val consent = api.setLocationSharing(on)
            state.update { it.copy(consent = consent) }
            // Off: stop watching now. On: starts once the permissions are in.
            if (consent.isSharing) Geofences.syncNow(context, force = true) else Geofences.removeAll(context)
            _messages.send(if (consent.isSharing) "Location sharing on" else "Location sharing off")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't change location sharing")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }
}

@Composable
internal fun LocationCard(session: SessionManager, showMessage: (String) -> Unit) {
    val vm = viewModel { LocationViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContext = context.applicationContext

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    // Re-read on return from Android's settings, where "all the time" is set.
    var fine by remember { mutableStateOf(Geofences.hasFineLocation(context)) }
    var background by remember { mutableStateOf(Geofences.hasBackgroundLocation(context)) }
    val recheck = {
        val wasReady = fine && background
        fine = Geofences.hasFineLocation(context)
        background = Geofences.hasBackgroundLocation(context)
        if (fine && background && !wasReady) Geofences.syncNow(appContext, force = true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recheck() }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck()
        if (!Geofences.hasFineLocation(context)) {
            showMessage("Choose \"Precise\" so the phone can tell when you're at home or a store.")
        }
    }
    // Android 11+ opens its settings page for this; the choice is "Allow all the time".
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }

    SectionCard("Location", "Off until you turn it on. You can change it any time.") {
        val consent = state.consent
        if (consent == null) {
            Text(
                state.error ?: "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.error != null) TextButton(onClick = { vm.refresh() }) { Text("Try again") }
            return@SectionCard
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Share my location with the household", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Your phone tells the household when you get to or leave a map place (home, a store), " +
                        "and where you were at that moment. Only that last spot is kept, never a history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = consent.isSharing,
                enabled = consent.canChange && !state.saving,
                onCheckedChange = { vm.setSharing(appContext, it) },
            )
        }
        if (!consent.canChange) {
            Text("Sharing is required for you.", style = MaterialTheme.typography.bodySmall)
        }

        when (locationSetup(consent.isSharing, fine, background, state.places ?: 1)) {
            LocationSetup.SharingOff -> Unit
            LocationSetup.NeedsLocation -> SetupStep(
                "Step 1 of 2: let Our Home use this phone's precise location.",
                "Allow location",
            ) {
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            LocationSetup.NeedsBackground -> {
                SetupStep(
                    "Step 2 of 2: Android only tells Our Home about arriving and leaving when location is " +
                        "allowed \"all the time\". On the next screen, choose \"Allow all the time\".",
                    "Allow all the time",
                ) { askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                // If Android stops showing the prompt (asked before), the setting is still there.
                TextButton(onClick = { openAppSettings(context) }) { Text("Open app settings instead") }
            }
            LocationSetup.NoPlaces -> Text(
                "All set on this phone. Nobody has added map places yet: the Head of House or a manager adds " +
                    "them in Settings → Map places on the website.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LocationSetup.Ready -> Text(
                "All set. This phone is watching ${state.places} map place${if (state.places == 1) "" else "s"}.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SetupStep(text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClick) { Text(button) }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
