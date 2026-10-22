package com.eosoclub.ourhome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.ImageBitmap
import com.eosoclub.ourhome.data.UsageHistory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.eosoclub.ourhome.data.setupSummary
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
import com.eosoclub.ourhome.data.PhoneRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// "People you look after" (web: /look-after): the children and teens this
// adult supervises (the Head's supervision list). A summary card each; a card
// opens that person's page, where this adult can ring their phone, make their
// location sharing required, share their app usage, choose which protections
// their phone must have on (PhoneRule). Only offered in the menu
// to someone who supervises somebody; the server checks it again.

/** Refetched this often while showing, so phone check-ins and usage stay current. */
private const val LOOK_AFTER_REFRESH_MS = 60_000L

/** After asking the phones for fresh usage, look again this long after (they answer in seconds). */
private val AFTER_USAGE_REFRESH_MS = listOf(10_000L, 15_000L)

/** After ringing, look this often for what their phone says, at most [RING_LOOKS] times (the web page does the same). */
private const val RING_REFRESH_MS = 3_000L
private const val RING_LOOKS = 40

class LookAfterViewModel(private val api: ApiClient) : ViewModel() {
    private val iconCache = UsageIcons(api, viewModelScope)

    /** App icons by package name, loaded once each; null = the server has none. */
    val icons = iconCache.icons
    private var usageAsked = false

    /** Loads [app]'s icon unless it was already asked for. */
    fun loadIcon(app: String) = iconCache.load(app)

    /** Opens or closes the week's chart and every app under [personId]'s latest day. */
    fun toggleUsageHistory(personId: String) {
        val open = personId !in state.value.usageOpen
        state.update { it.copy(usageOpen = if (open) it.usageOpen + personId else it.usageOpen - personId) }
        if (open) loadUsageHistory(personId)
    }

    private fun loadUsageHistory(personId: String) = viewModelScope.launch {
        runCatching { api.usageHistory(personId) }
            .onSuccess { history ->
                state.update {
                    it.copy(usageHistory = it.usageHistory + (personId to history), usageHistoryFailed = it.usageHistoryFailed - personId)
                }
            }
            .onFailure { state.update { it.copy(usageHistoryFailed = it.usageHistoryFailed + personId) } }
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
        /** Which of whose phone rules is being saved, and to what. */
        val savingRule: Triple<String, PhoneRule, Boolean>? = null,
        /** Whose week of app usage is open, their loaded history, and whose failed to load. */
        val usageOpen: Set<String> = emptySet(),
        val usageHistory: Map<String, UsageHistory> = emptyMap(),
        val usageHistoryFailed: Set<String> = emptySet(),
        val loaded: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
    )

    val state = MutableStateFlow(UiState())

    /** Counts changes made here, so a refresh that started before one can't
     *  put the old values back (a switch flicking back). */
    private var edits = 0

    /** [withHistory]: also reload the open weeks of app usage (not while only following a ring). */
    fun load(withHistory: Boolean = true) = viewModelScope.launch {
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
        if (!withHistory) return@launch
        // An open week stays current with the rest of the page, while their usage is still shared.
        val sharing = state.value.lookAfter?.people?.filter { it.appUsage.shared }?.map { it.id }.orEmpty()
        state.value.usageOpen.filter { it in sharing }.forEach { loadUsageHistory(it) }
    }

    /**
     * Rings [person]'s phone; [onDone] gets the message to show. Then follows
     * the ring every few seconds, while their phone hasn't said how it ended.
     */
    fun ring(person: LookedAfter, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            api.ringPhone(person.id)
            onDone("Ringing ${person.name}'s phone")
        } catch (e: Exception) {
            onDone(e.message ?: "Couldn't ring their phone")
            return@launch
        }
        for (look in 1..RING_LOOKS) {
            load(withHistory = false).join()
            val ring = state.value.lookAfter?.people?.firstOrNull { it.id == person.id }?.ring
            if (ring?.active != true) break
            delay(RING_REFRESH_MS)
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

    /** Requires [rule] on [person]'s phone, or stops requiring it. */
    fun setRule(person: LookedAfter, rule: PhoneRule, required: Boolean, onDone: (String) -> Unit) = viewModelScope.launch {
        edits++
        state.update { it.copy(savingRule = Triple(person.id, rule, required)) }
        try {
            val next = api.setPhoneRule(person.id, rule, required)
            // Set before the saving state ends, so the switch never flicks back.
            state.update { it.copy(lookAfter = next, savingRule = null) }
            onDone(
                if (required) "${rule.label} is now required on ${person.name}'s phone"
                else "${rule.label} is no longer required on ${person.name}'s phone",
            )
        } catch (e: Exception) {
            state.update { it.copy(savingRule = null) }
            onDone(e.message ?: "Couldn't change it")
        }
    }
}

/**
 * First a summary card for each person (with Ring); a card opens that person's
 * page with everything. Back returns to the cards. [startPersonId]: open
 * straight on that person's page (a phone alert about them).
 */
@Composable
internal fun LookAfterScreen(
    api: ApiClient,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    startPersonId: String? = null,
) {
    val vm = viewModel { LookAfterViewModel(api) }
    var selectedId by rememberSaveable(startPersonId) { mutableStateOf(startPersonId) }
    BackHandler(enabled = selectedId != null) { selectedId = null }
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
        // Someone no longer looked after: back to the cards.
        val selected = lookAfter?.people?.firstOrNull { it.id == selectedId }
        if (lookAfter == null || selected == null) {
            // One column on a phone held upright, more when there's room (as the web's grid).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                contentPadding = PaddingValues(16.dp, 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                item(span = full) {
                    LookAfterNote(
                        "The children and teens the Head of House asked you to supervise. Tap someone to see and " +
                            "change everything for them. Everything you do here is logged in Activity.",
                        padded = false,
                    )
                }
                if (lookAfter == null || lookAfter.people.isEmpty()) {
                    item(span = full) {
                        LookAfterNote(
                            "You don't look after anyone right now. The Head of House chooses who supervises whom on the Members page.",
                            padded = false,
                        )
                    }
                    return@LazyVerticalGrid
                }
                items(lookAfter.people, key = { it.id }) { person ->
                    PersonCard(
                        person,
                        canRing = lookAfter.canRing,
                        onOpen = { selectedId = person.id },
                        onRing = { vm.ring(person, showMessage) },
                    )
                }
                if (!lookAfter.canRing) {
                    item(span = full) {
                        LookAfterNote(
                            "Ringing needs instant phone alerts: Firebase on this server, or Stay connected turned on " +
                                "in the app on their phone.",
                            padded = false,
                        )
                    }
                }
            }
            return@Box
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            item {
                TextButton(onClick = { selectedId = null }, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    Text("People you look after")
                }
                Text(
                    selected.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item(key = selected.id) {
                val person = selected
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
                    savingRule = state.savingRule?.takeIf { it.first == person.id }?.let { it.second to it.third },
                    onRule = { rule, required -> vm.setRule(person, rule, required, showMessage) },
                    icons = icons,
                    loadIcon = vm::loadIcon,
                    usageOpen = person.id in state.usageOpen,
                    usageHistory = state.usageHistory[person.id],
                    usageHistoryFailed = person.id in state.usageHistoryFailed,
                    onToggleUsage = { vm.toggleUsageHistory(person.id) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                LookAfterNote(
                    "Ring rings their phone loudly for up to 30 seconds, even on silent. You see when it starts " +
                        "ringing, and whether someone tapped Stop. Location required keeps " +
                        "their location sharing on, and lets you check where they are on the Map without them allowing it. " +
                        "They always see when you check. Share app usage shows how long they spend in each app; " +
                        "their phone needs Usage access for it. Their phone sends it every 15 minutes, and opening " +
                        "this page asks it for the latest numbers. They see the same numbers on their Profile. While " +
                        "it's shared, you're also told when a new app turns up on their phone. Require Keep Our Home running stops OurHome " +
                        "being closed on their phone, Require Prevent uninstall stops it being removed without " +
                        "your PIN, and Require Unrestricted battery stops battery saving from holding back rings " +
                        "and alerts. Each one you require is a step in Set up this phone on their phone, and " +
                        "you're told if it gets turned off. Not requiring one only stops the step and the alert: " +
                        "whatever is already on stays on." +
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

/** [padded]: its own side margins (off inside the cards' grid, which has them). */
@Composable
private fun LookAfterNote(text: String, padded: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = if (padded) 16.dp else 0.dp, vertical = 8.dp),
    )
}

/** Their phone: what's wrong with it (true: shown red), or when it last checked in. */
private fun phoneStatus(person: LookedAfter): Pair<String, Boolean> {
    val phone = person.phone ?: return "Their phone hasn't checked in yet" to false
    val problem = phone.problemText
    return if (problem != null) {
        "$problem (last seen ${relativeTime(phone.lastSeenAt)})" to true
    } else {
        "Phone checked in ${relativeTime(phone.lastSeenAt)}" to false
    }
}

/**
 * One person on the first page: what needs seeing at a glance (their phone,
 * location, today's app time, setup, the last ring), and Ring. Tapping it
 * opens their page, where everything can be changed. A red edge while their
 * phone has a problem.
 */
@Composable
private fun PersonCard(person: LookedAfter, canRing: Boolean, onOpen: () -> Unit, onRing: () -> Unit) {
    val (status, problem) = phoneStatus(person)
    Card(
        onClick = onOpen,
        border = if (problem) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (canRing) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    person.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardLine(status, error = problem)
            CardLine(if (person.required) "Location required" else person.sharingNote.orEmpty())
            val latest = person.appUsage.latest
            CardLine(
                when {
                    !person.appUsage.shared -> "App usage not shared"
                    latest != null -> "${latest.header(java.time.LocalDate.now().toString())} in apps"
                    else -> "No app usage from their phone yet"
                },
            )
            person.setupSummary()?.let { CardLine(it) }
            person.ring?.let { ring ->
                CardLine("You rang ${relativeTime(ring.sentAt)}: ${ring.outcome}", error = ring.phase == "no_answer")
            }
            if (canRing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRing) { Text("Ring") }
                }
            }
        }
    }
}

@Composable
private fun CardLine(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One child or teen: their phone, Ring, the Location required and Share app
 * usage switches, a switch per protection their phone must have on, and how
 * far their phone got with Set up this phone.
 */
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
    /** The rule being saved for them, and to what. */
    savingRule: Pair<PhoneRule, Boolean>?,
    onRule: (PhoneRule, Boolean) -> Unit,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    /** Whether the week's chart and every app show under their latest day, and what loaded for it. */
    usageOpen: Boolean,
    usageHistory: UsageHistory?,
    usageHistoryFailed: Boolean,
    onToggleUsage: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Their name is the page's heading.
                person.sharingNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val phone = person.phone
                val (status, problem) = phoneStatus(person)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
                // My last ring of their phone and what it said back.
                person.ring?.let { ring ->
                    Text(
                        "You rang ${relativeTime(ring.sentAt)}: ${ring.outcome}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ring.phase == "no_answer") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            when {
                latest == null -> Unit
                !usageOpen -> latest.top.forEach { app -> UsageAppRow(app, icons, loadIcon) }
                usageHistory != null -> UsageHistoryView(usageHistory, icons, loadIcon)
                usageHistoryFailed -> Text(
                    "Couldn't load their app usage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text("Loading...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (latest != null) {
                TextButton(onClick = onToggleUsage) {
                    Text(if (usageOpen) "Show less" else "All apps and the last 7 days")
                }
            }
        }
        // Only from servers that send the rules.
        person.rules?.let { rules ->
            PhoneRule.entries.forEach { rule ->
                val pending = savingRule?.takeIf { it.first == rule }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Require ${rule.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = pending?.second ?: rule.of(rules),
                        // One rule saves at a time per person.
                        enabled = savingRule == null,
                        onCheckedChange = { onRule(rule, it) },
                    )
                }
            }
        }
        // How far their phone got with Set up this phone (only from servers that send it).
        SetupProgress(person)
    }
}
