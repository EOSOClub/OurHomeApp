package com.eosoclub.ourhome.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.eosoclub.ourhome.data.UsageTopApp
import com.eosoclub.ourhome.data.formatMinutes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.LookAfter
import com.eosoclub.ourhome.data.LookedAfter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// "People you look after" (web: /look-after): the children and teens this
// adult supervises (the Head's supervision list). Ring their phone, make their
// location sharing required, share their app usage. Only offered in the menu
// to someone who supervises somebody; the server checks it again.

/** Refetched this often while showing, so phone check-ins and usage stay current. */
private const val LOOK_AFTER_REFRESH_MS = 60_000L

/** After asking the phones for fresh usage, look again this long after (they answer in seconds). */
private val AFTER_USAGE_REFRESH_MS = listOf(10_000L, 15_000L)

class LookAfterViewModel(private val api: ApiClient) : ViewModel() {
    /** App icons by package name, loaded once each; null = the server has none. */
    val icons = MutableStateFlow<Map<String, ImageBitmap?>>(emptyMap())
    private val iconsAsked = mutableSetOf<String>()
    private var usageAsked = false

    /** Loads [app]'s icon unless it was already asked for. */
    fun loadIcon(app: String) {
        if (!iconsAsked.add(app)) return
        viewModelScope.launch {
            val bitmap = runCatching {
                api.usageIcon(app)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
            icons.update { it + (app to bitmap) }
            // A failed load may try again on the next visit.
            if (bitmap == null) iconsAsked.remove(app)
        }
    }

    /**
     * Once per visit: asks each child's phone whose usage is shared to send it
     * now (the server skips it when it can't or just did), then looks again
     * shortly after. Best effort; the stored numbers show either way.
     */
    fun askForFreshUsage() {
        if (usageAsked) return
        val shared = state.value.lookAfter?.people?.filter { it.appUsage.shared } ?: return
        usageAsked = true
        if (shared.isEmpty()) return
        viewModelScope.launch {
            val asked = shared.map { person -> runCatching { api.refreshUsage(person.id) }.getOrDefault(false) }
            if (asked.none { it }) return@launch
            for (wait in AFTER_USAGE_REFRESH_MS) {
                delay(wait)
                load().join()
            }
        }
    }

    data class UiState(
        val lookAfter: LookAfter? = null,
        /** Whose "Location required" is being saved, and to what. */
        val savingRequired: Pair<String, Boolean>? = null,
        /** Whose "Share app usage" is being saved, and to what. */
        val savingUsage: Pair<String, Boolean>? = null,
        val loaded: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
    )

    val state = MutableStateFlow(UiState())

    /** Counts changes made here, so a refresh that started before one can't
     *  put the old values back (a switch flicking back). */
    private var edits = 0

    fun load() = viewModelScope.launch {
        val editsAtStart = edits
        state.update { it.copy(loading = true) }
        state.load(
            fetch = { api.lookAfter() },
            onOk = { s, data ->
                s.copy(
                    lookAfter = if (edits == editsAtStart) data else s.lookAfter ?: data,
                    loaded = true,
                    loading = false,
                    error = null,
                )
            },
            onError = { s, message -> s.copy(loading = false, error = message ?: "Couldn't load the people you look after") },
        )
    }

    /** Rings [person]'s phone; [onDone] gets the message to show. */
    fun ring(person: LookedAfter, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            api.ringPhone(person.id)
            onDone("Ringing ${person.name}'s phone")
        } catch (e: Exception) {
            onDone(e.message ?: "Couldn't ring their phone")
        }
    }

    /** Makes [person]'s location sharing required, or stops requiring it. */
    fun setRequired(person: LookedAfter, required: Boolean, onDone: (String) -> Unit) = viewModelScope.launch {
        edits++
        state.update { it.copy(savingRequired = person.id to required) }
        try {
            val next = api.setRequiredSharing(person.id, required)
            // Set before the saving state ends, so the switch never flicks back.
            state.update { it.copy(lookAfter = next, savingRequired = null) }
            onDone(if (required) "${person.name}'s location sharing is now required" else "${person.name} can turn sharing off again")
        } catch (e: Exception) {
            state.update { it.copy(savingRequired = null) }
            onDone(e.message ?: "Couldn't change it")
        }
    }

    /** Turns sharing [person]'s app usage on or off. */
    fun setUsage(person: LookedAfter, share: Boolean, onDone: (String) -> Unit) = viewModelScope.launch {
        edits++
        state.update { it.copy(savingUsage = person.id to share) }
        try {
            val next = api.setUsageSharing(person.id, share)
            // Set before the saving state ends, so the switch never flicks back.
            state.update { it.copy(lookAfter = next, savingUsage = null) }
            onDone(if (share) "${person.name}'s app usage will be shared" else "${person.name}'s app usage is no longer shared")
        } catch (e: Exception) {
            state.update { it.copy(savingUsage = null) }
            onDone(e.message ?: "Couldn't change it")
        }
    }
}

@Composable
internal fun LookAfterScreen(api: ApiClient, showMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = viewModel { LookAfterViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val icons by vm.icons.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.load().join()
                vm.askForFreshUsage()
                delay(LOOK_AFTER_REFRESH_MS)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (LoadState(state.loading, state.error, !state.loaded, "", { vm.load() })) return@Box
        val lookAfter = state.lookAfter
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            item {
                LookAfterNote("The children and teens the Head of House asked you to supervise. Everything you do here is logged in Activity.")
            }
            if (lookAfter == null || lookAfter.people.isEmpty()) {
                item {
                    LookAfterNote("You don't look after anyone right now. The Head of House chooses who supervises whom on the Members page.")
                }
                return@LazyColumn
            }
            items(lookAfter.people, key = { it.id }) { person ->
                val saving = state.savingRequired?.takeIf { it.first == person.id }
                val savingUsage = state.savingUsage?.takeIf { it.first == person.id }
                LookedAfterRow(
                    person,
                    canRing = lookAfter.canRing,
                    required = saving?.second ?: person.required,
                    saving = saving != null,
                    sharesUsage = savingUsage?.second ?: person.appUsage.shared,
                    savingUsage = savingUsage != null,
                    onRing = { vm.ring(person, showMessage) },
                    onRequired = { vm.setRequired(person, it, showMessage) },
                    onUsage = { vm.setUsage(person, it, showMessage) },
                    icons = icons,
                    loadIcon = vm::loadIcon,
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                LookAfterNote(
                    "Ring rings their phone loudly for up to 30 seconds, even on silent. Location required keeps " +
                        "their location sharing on, and lets you check where they are on the Map without them allowing it. " +
                        "They always see when you check. Share app usage shows how long they spend in each app; " +
                        "their phone needs Usage access for it. Their phone sends it every 15 minutes, and opening " +
                        "this page asks it for the latest numbers." +
                        if (lookAfter.canRing) {
                            ""
                        } else {
                            " Ringing needs instant phone alerts: Firebase on this server, or Stay connected turned on " +
                                "in the app on their phone."
                        },
                )
            }
        }
    }
}

@Composable
private fun LookAfterNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One child or teen: their phone, Ring, and the Location required and Share app usage switches. */
@Composable
private fun LookedAfterRow(
    person: LookedAfter,
    canRing: Boolean,
    required: Boolean,
    saving: Boolean,
    sharesUsage: Boolean,
    savingUsage: Boolean,
    onRing: () -> Unit,
    onRequired: (Boolean) -> Unit,
    onUsage: (Boolean) -> Unit,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                person.sharingNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Their phone: what's wrong with it (red), or when it last checked in.
                val phone = person.phone
                val problem = phone?.problemText
                Text(
                    when {
                        phone == null -> "Their phone hasn't checked in yet"
                        problem != null -> "$problem (last seen ${relativeTime(phone.lastSeenAt)})"
                        else -> "Phone checked in ${relativeTime(phone.lastSeenAt)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // How instant alerts reach it; nothing for an app too old to say.
                val alerts = when {
                    phone == null -> null
                    phone.stayConnected && phone.connected -> "Stay connected: connected now"
                    phone.stayConnected -> "Stay connected is on, but it isn't connected right now"
                    phone.lastPushAt != null -> "Last instant alert reached it ${relativeTime(phone.lastPushAt)}"
                    else -> null
                }
                alerts?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canRing) TextButton(onClick = onRing) { Text("Ring") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Location required", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = required, enabled = !saving, onCheckedChange = onRequired)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Share app usage", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = sharesUsage, enabled = !savingUsage, onCheckedChange = onUsage)
        }
        if (person.appUsage.shared) {
            val latest = person.appUsage.latest
            Text(
                latest?.let { "${it.header(java.time.LocalDate.now().toString())} · updated ${relativeTime(it.updatedAt)}" }
                    ?: "No app usage from their phone yet. It needs the latest OurHome app, and Usage access allowed.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (latest != null) FontWeight.Medium else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            latest?.top?.forEach { app -> UsageAppRow(app, icons, loadIcon) }
        }
    }
}

/** One of their top apps: its icon (or first letter until their phone sent it), name and time. */
@Composable
private fun UsageAppRow(app: UsageTopApp, icons: Map<String, ImageBitmap?>, loadIcon: (String) -> Unit) {
    if (app.icon && app.app.isNotEmpty()) LaunchedEffect(app.app) { loadIcon(app.app) }
    val icon = icons[app.app]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)))
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            app.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Text(
            formatMinutes(app.minutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
