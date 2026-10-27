package com.eosoclub.ourhome.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.eosoclub.ourhome.data.setupSummary
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import com.eosoclub.ourhome.data.ALL_APPS
import com.eosoclub.ourhome.data.ALL_APPS_LABEL
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.AppLimitEntry
import com.eosoclub.ourhome.data.CAP_CHOICES
import com.eosoclub.ourhome.data.EVERY_DAY
import com.eosoclub.ourhome.data.GIVE_STEPS
import com.eosoclub.ourhome.data.LookedAfterPointsTime
import com.eosoclub.ourhome.data.LookedAfterWebFilter
import com.eosoclub.ourhome.data.WebFilterChoice
import com.eosoclub.ourhome.data.RATE_CHOICES
import com.eosoclub.ourhome.data.formatMinutes
import com.eosoclub.ourhome.data.formatPoints
import com.eosoclub.ourhome.data.LimitChoice
import com.eosoclub.ourhome.data.MAX_ALLOWED
import com.eosoclub.ourhome.data.MAX_SCHEDULES
import com.eosoclub.ourhome.data.SCHEDULE_DAYS
import com.eosoclub.ourhome.data.SCHEDULE_PRESETS
import com.eosoclub.ourhome.data.ScheduleEntry
import com.eosoclub.ourhome.data.SchedulePreset
import com.eosoclub.ourhome.data.WEEKDAYS
import com.eosoclub.ourhome.data.daysLabel
import com.eosoclub.ourhome.data.scheduleTimes
import com.eosoclub.ourhome.data.schedulesSummary
import com.eosoclub.ourhome.data.LookAfter
import com.eosoclub.ourhome.data.LookedAfter
import com.eosoclub.ourhome.data.LookedAfterBlocking
import com.eosoclub.ourhome.data.LookedAfterLimits
import com.eosoclub.ourhome.data.PhoneRule
import com.eosoclub.ourhome.data.blockedSummary
import com.eosoclub.ourhome.data.limitLabel
import com.eosoclub.ourhome.data.limitOptions
import com.eosoclub.ourhome.data.limitsSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

// "People you look after" (web: /look-after): the children and teens this
// adult supervises (the Head's supervision list). A summary card each; a card
// opens that person's page: their phone with Ring, then a folded card per
// subject, each saying in its title row how it stands and explaining itself
// inside. There this adult can make their
// location sharing required, share their app usage, block apps from it, give
// apps a daily limit, set schedules and let them buy time with points,
// choose which protections their phone must have on (PhoneRule). Only offered in the menu
// to someone who supervises somebody; the server checks it again.

/** A new app limit starts at this many minutes; its menu changes it. */
private const val NEW_LIMIT_MINUTES = 60

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
        /** Whose web filter is being saved, and to what (null: off). */
        val savingWebFilter: Pair<String, WebFilterChoice?>? = null,
        /** Whose app is being blocked or unblocked (person id, package name), and to what. */
        val savingBlock: Triple<String, String, Boolean>? = null,
        /** Whose daily limit is being saved (person id, package name or ALL_APPS), and to what (null: removed). */
        val savingLimit: Triple<String, String, Int?>? = null,
        /** Whose schedules are being saved or removed (person id). */
        val savingSchedule: String? = null,
        /** Whose points for screen time, or extra time, is being saved (person id). */
        val savingPoints: String? = null,
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

    /** Chooses the web filter on [person]'s phone, or turns it off ([choice] null). */
    fun setWebFilter(person: LookedAfter, choice: WebFilterChoice?, onDone: (String) -> Unit) = viewModelScope.launch {
        edits++
        state.update { it.copy(savingWebFilter = person.id to choice) }
        try {
            val next = api.setWebFilter(person.id, choice)
            // Set before the saving state ends, so the choice never flicks back.
            state.update { it.copy(lookAfter = next, savingWebFilter = null) }
            onDone(choice?.let { "${person.name}'s web filter is now ${it.label}" } ?: "${person.name}'s web filter is off")
        } catch (e: Exception) {
            state.update { it.copy(savingWebFilter = null) }
            onDone(e.message ?: "Couldn't change it")
        }
    }

    /** Blocks [app] ([label] for the message) on [person]'s phone, or unblocks it. One at a time. */
    fun setBlocked(person: LookedAfter, app: String, label: String, blocked: Boolean, onDone: (String) -> Unit) {
        if (state.value.savingBlock != null) return
        edits++
        state.update { it.copy(savingBlock = Triple(person.id, app, blocked)) }
        viewModelScope.launch {
            try {
                val next = api.setAppBlocked(person.id, app, blocked)
                // Set before the saving state ends, so the button never flicks back.
                state.update { it.copy(lookAfter = next, savingBlock = null) }
                onDone(if (blocked) "$label is blocked on ${person.name}'s phone" else "$label is unblocked on ${person.name}'s phone")
            } catch (e: Exception) {
                state.update { it.copy(savingBlock = null) }
                onDone(e.message ?: "Couldn't change it")
            }
        }
    }

    /**
     * Adds a schedule to [person]'s phone, or changes one ([id]); [onSaved]
     * closes the form once it's saved. One save at a time.
     */
    fun saveSchedule(
        person: LookedAfter,
        id: String?,
        preset: SchedulePreset,
        allowed: List<String>,
        onDone: (String) -> Unit,
        onSaved: () -> Unit,
    ) {
        if (state.value.savingSchedule != null) return
        edits++
        state.update { it.copy(savingSchedule = person.id) }
        viewModelScope.launch {
            try {
                val next = api.saveSchedule(person.id, id, preset.name, preset.days, preset.start, preset.end, allowed)
                state.update { it.copy(lookAfter = next, savingSchedule = null) }
                onSaved()
                onDone("${preset.name} is set on ${person.name}'s phone")
            } catch (e: Exception) {
                state.update { it.copy(savingSchedule = null) }
                onDone(e.message ?: "Couldn't save it")
            }
        }
    }

    /** Lets [person] spend points on screen time at [minutesPerPoint] a point, up to [dailyCap] a day (null: stops it). */
    fun setPointsForTime(person: LookedAfter, minutesPerPoint: Int?, dailyCap: Int, onDone: (String) -> Unit) =
        savePoints(person, onDone) {
            api.setPointsForTime(person.id, minutesPerPoint, dailyCap) to
                if (minutesPerPoint == null) "${person.name} can no longer spend points on screen time"
                else "${person.name} can spend points on screen time"
        }

    /** Gives [person] [minutes] more on today's limits. */
    fun giveExtraTime(person: LookedAfter, minutes: Int, onDone: (String) -> Unit) =
        savePoints(person, onDone) { api.giveExtraTime(person.id, minutes) to "${person.name} has ${formatMinutes(minutes)} more today" }

    /** One points change at a time; [call] answers the page's new data and the message. */
    private fun savePoints(person: LookedAfter, onDone: (String) -> Unit, call: suspend () -> Pair<LookAfter, String>) {
        if (state.value.savingPoints != null) return
        edits++
        state.update { it.copy(savingPoints = person.id) }
        viewModelScope.launch {
            try {
                val (next, message) = call()
                state.update { it.copy(lookAfter = next, savingPoints = null) }
                onDone(message)
            } catch (e: Exception) {
                state.update { it.copy(savingPoints = null) }
                onDone(e.message ?: "Couldn't change it")
            }
        }
    }

    /** Removes [schedule] from [person]'s phone. */
    fun removeSchedule(person: LookedAfter, schedule: ScheduleEntry, onDone: (String) -> Unit) {
        if (state.value.savingSchedule != null) return
        edits++
        state.update { it.copy(savingSchedule = person.id) }
        viewModelScope.launch {
            try {
                val next = api.removeSchedule(person.id, schedule.id)
                state.update { it.copy(lookAfter = next, savingSchedule = null) }
                onDone("${schedule.name} is off ${person.name}'s phone")
            } catch (e: Exception) {
                state.update { it.copy(savingSchedule = null) }
                onDone(e.message ?: "Couldn't remove it")
            }
        }
    }

    /**
     * Gives [app] (or [ALL_APPS]; [label] for the message) a daily limit of
     * [minutes] on [person]'s phone, or removes it (null). One at a time.
     */
    fun setLimit(person: LookedAfter, app: String, label: String, minutes: Int?, onDone: (String) -> Unit) {
        if (state.value.savingLimit != null) return
        edits++
        state.update { it.copy(savingLimit = Triple(person.id, app, minutes)) }
        viewModelScope.launch {
            try {
                val next = api.setAppLimit(person.id, app, minutes)
                // Set before the saving state ends, so the menu never flicks back.
                state.update { it.copy(lookAfter = next, savingLimit = null) }
                onDone(
                    if (minutes == null) "$label: no limit on ${person.name}'s phone"
                    else "$label: ${limitLabel(minutes)} on ${person.name}'s phone",
                )
            } catch (e: Exception) {
                state.update { it.copy(savingLimit = null) }
                onDone(e.message ?: "Couldn't change it")
            }
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            TextButton(onClick = { selectedId = null }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                Text("People you look after")
            }
            Text(
                selected.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Keyed, so opening someone else's page starts with their cards folded.
            key(selected.id) {
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
                    savingWebFilter = state.savingWebFilter?.takeIf { it.first == person.id }?.let { it.second },
                    webFilterSaving = state.savingWebFilter?.first == person.id,
                    onWebFilter = { vm.setWebFilter(person, it, showMessage) },
                    icons = icons,
                    loadIcon = vm::loadIcon,
                    usageOpen = person.id in state.usageOpen,
                    usageHistory = state.usageHistory[person.id],
                    usageHistoryFailed = person.id in state.usageHistoryFailed,
                    onToggleUsage = { vm.toggleUsageHistory(person.id) },
                    savingBlock = state.savingBlock?.takeIf { it.first == person.id }?.let { it.second to it.third },
                    onBlock = { app, label, blocked -> vm.setBlocked(person, app, label, blocked, showMessage) },
                    savingLimit = state.savingLimit?.takeIf { it.first == person.id }?.let { it.second to it.third },
                    onLimit = { app, label, minutes -> vm.setLimit(person, app, label, minutes, showMessage) },
                    savingSchedule = state.savingSchedule == person.id,
                    onSaveSchedule = { id, preset, allowed, onSaved ->
                        vm.saveSchedule(person, id, preset, allowed, showMessage, onSaved)
                    },
                    onRemoveSchedule = { vm.removeSchedule(person, it, showMessage) },
                    savingPoints = state.savingPoints == person.id,
                    onPointsRule = { rate, cap -> vm.setPointsForTime(person, rate, cap, showMessage) },
                    onGive = { vm.giveExtraTime(person, it, showMessage) },
                )
            }
        }
    }
}

/** A card's explanation, under the switch or list it's about. */
@Composable
private fun Help(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A switch with its label before it. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** A red line about something that stops a card's settings working on their phone. */
@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
            blockedSummary(person.blocking.apps)?.let { CardLine(it) }
            limitsSummary(person.limits)?.let { CardLine(it) }
            schedulesSummary(person.schedules)?.let { CardLine(it) }
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
 * Everything about one child or teen, as Profile is laid out (two stacks when
 * there's room, one list in the same order upright): their phone with Ring,
 * then a folded card each for Location, App usage (with what's blocked), Daily
 * limits, Schedules, Points for screen time and Phone protections (a switch
 * per protection their phone must have on, and how far Set up this phone has
 * got). Each title row says how it stands; a card opens by itself while
 * something in it isn't working.
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
    /** The web filter being saved for them ([webFilterSaving]), and to what (null: off). */
    savingWebFilter: WebFilterChoice?,
    webFilterSaving: Boolean,
    onWebFilter: (WebFilterChoice?) -> Unit,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    /** Whether the week's chart and every app show under their latest day, and what loaded for it. */
    usageOpen: Boolean,
    usageHistory: UsageHistory?,
    usageHistoryFailed: Boolean,
    onToggleUsage: () -> Unit,
    /** The app being blocked or unblocked for them, and to what. */
    savingBlock: Pair<String, Boolean>?,
    onBlock: (app: String, label: String, blocked: Boolean) -> Unit,
    /** The daily limit being saved for them (package name or ALL_APPS), and to what (null: removed). */
    savingLimit: Pair<String, Int?>?,
    onLimit: (app: String, label: String, minutes: Int?) -> Unit,
    savingSchedule: Boolean,
    onSaveSchedule: (id: String?, preset: SchedulePreset, allowed: List<String>, onSaved: () -> Unit) -> Unit,
    onRemoveSchedule: (ScheduleEntry) -> Unit,
    savingPoints: Boolean,
    onPointsRule: (minutesPerPoint: Int?, dailyCap: Int) -> Unit,
    onGive: (minutes: Int) -> Unit,
) {
    val blocking = person.blocking
    val blockAction: AppRowAction = { app ->
        val pending = savingBlock?.takeIf { it.first == app.app }
        BlockButton(
            alwaysOpen = app.app in blocking.alwaysAllowed,
            blocked = pending?.second ?: blocking.isBlocked(app.app),
            enabled = savingBlock == null,
            onClick = { blocked -> onBlock(app.app, app.label, blocked) },
        )
    }
    Box(Modifier.padding(16.dp)) {
        TwoStacks(
            first = {
                PhoneCard(person, canRing, onRing)
                CollapsibleCard("Location", if (person.required) "Location required" else person.sharingNote.orEmpty()) {
                    Column {
                        SwitchRow("Location required", required, enabled = !saving, onChange = onRequired)
                        Help(
                            "Keeps their location sharing on, and lets you check where they are on the Map without " +
                                "them allowing it. They always see when you check.",
                        )
                    }
                }
                // Opens by itself while their blocked apps open anyway.
                val blockedOpen = blocking.works == false && blocking.apps.isNotEmpty()
                CollapsibleCard("App usage", usageSummary(person), startOpen = blockedOpen, openWhen = blockedOpen) {
                    Column {
                        SwitchRow("Share app usage", sharesUsage, enabled = !savingUsage, onChange = onUsage)
                        Help(
                            "Shows you how long they spend in each app; their phone needs Usage access for it. Their " +
                                "phone sends it every 15 minutes, and opening this page asks it for the latest " +
                                "numbers. They see the same numbers on their Profile. While it's shared, you're also " +
                                "told when a new app turns up on their phone.",
                        )
                    }
                    if (person.appUsage.shared) {
                        Column {
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
                                !usageOpen -> latest.top.forEach { app -> UsageAppRow(app, icons, loadIcon, blockAction) }
                                usageHistory != null -> UsageHistoryView(usageHistory, icons, loadIcon, blockAction)
                                usageHistoryFailed -> Warning("Couldn't load their app usage.")
                                else -> Help("Loading...")
                            }
                            if (latest != null) {
                                TextButton(onClick = onToggleUsage, contentPadding = PaddingValues(horizontal = 0.dp)) {
                                    Text(if (usageOpen) "Show less" else "All apps and the last 7 days")
                                }
                            }
                            Help(
                                "Block next to an app stops it opening on their phone: they see a screen saying you " +
                                    "blocked it. Blocking needs Keep Our Home running on their phone. Phone calls, " +
                                    "messages and OurHome always stay open.",
                            )
                        }
                    }
                    BlockedApps(blocking, icons, loadIcon, savingBlock, onUnblock = { app, label -> onBlock(app, label, false) })
                }
                person.webFilter?.let { webFilter ->
                    WebFilterCard(
                        webFilter,
                        chosen = if (webFilterSaving) savingWebFilter else webFilter.choice,
                        saving = webFilterSaving,
                        onPick = onWebFilter,
                    )
                }
            },
            second = {
                // While their usage is shared, or while a limit is left from before.
                if (person.appUsage.shared || !person.limits.isEmpty) {
                    // Any app from their usage without a limit of its own yet.
                    val choices = person.usageApps.filter { a -> person.limits.apps.none { it.app == a.app } }
                    DailyLimits(person.limits, choices, blocking.works, icons, loadIcon, savingLimit, onLimit)
                }
                Schedules(person.schedules, person.usageApps, blocking.works, icons, loadIcon, savingSchedule, onSaveSchedule, onRemoveSchedule)
                PointsForTime(
                    person.pointsTime,
                    hasLimits = !person.limits.isEmpty,
                    saving = savingPoints,
                    onRule = onPointsRule,
                    onGive = onGive,
                )
                PhoneProtections(person, savingRule, onRule)
            },
        )
    }
}

/** The App usage card's title line: their latest day, and what's blocked. */
private fun usageSummary(person: LookedAfter): String {
    val latest = person.appUsage.latest
    val usage = when {
        !person.appUsage.shared -> "Not shared"
        latest != null -> "${latest.header(java.time.LocalDate.now().toString())} in apps"
        else -> "No app usage from their phone yet"
    }
    return blockedSummary(person.blocking.apps)?.let { "$usage · $it" } ?: usage
}

/**
 * Their phone, open at the top of their page: what's wrong with it or when it
 * last checked in, how instant alerts reach it, my last ring, and Ring. A red
 * edge while their phone has a problem, as on their summary card.
 */
@Composable
private fun PhoneCard(person: LookedAfter, canRing: Boolean, onRing: () -> Unit) {
    val phone = person.phone
    val (status, problem) = phoneStatus(person)
    Card(
        border = if (problem) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Their phone", style = MaterialTheme.typography.titleMedium)
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
                    alerts?.let { Help(it) }
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
            Help(
                if (canRing) {
                    "Ring rings their phone loudly for up to 30 seconds, even on silent. You see when it starts " +
                        "ringing, and whether someone tapped Stop."
                } else {
                    "Ringing needs instant phone alerts: Firebase on this server, or Stay connected turned on in the " +
                        "app on their phone."
                },
            )
        }
    }
}

/**
 * The web filter on their phone: Off or one of [WebFilterChoice] ([chosen]:
 * what's saved, or being saved). Opens by itself while their phone isn't
 * using the one chosen.
 */
@Composable
private fun WebFilterCard(
    webFilter: LookedAfterWebFilter,
    chosen: WebFilterChoice?,
    saving: Boolean,
    onPick: (WebFilterChoice?) -> Unit,
) {
    val warn = webFilter.choice != null && webFilter.on == false
    CollapsibleCard("Web filter", webFilter.summary, startOpen = warn, openWhen = warn) {
        Help(
            "Filters websites in every browser and app on their phone, through Android's Private DNS. Set up this " +
                "phone shows them how to turn it on, and you're told if it's turned off.",
        )
        if (warn) Warning("Their phone isn't using it. On their phone, open OurHome and follow Set up this phone.")
        if (webFilter.choice != null && webFilter.on == null) {
            Help("Waiting for their phone to say it's on. It needs the latest OurHome app.")
        }
        Column(Modifier.selectableGroup()) {
            (listOf(null) + WebFilterChoice.entries).forEach { choice ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = chosen == choice,
                            enabled = !saving,
                            role = Role.RadioButton,
                            onClick = { if (chosen != choice) onPick(choice) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = chosen == choice, onClick = null, enabled = !saving)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(choice?.label ?: "Off", style = MaterialTheme.typography.bodyMedium)
                        Help(choice?.blurb ?: "Websites aren't filtered.")
                    }
                }
            }
        }
        if (webFilter.choice != null) Help("While Keep Our Home running is on, they can't change it on their phone.")
    }
}

/** What each protection does, under its switch. */
private val PhoneRule.help: String
    get() = when (this) {
        PhoneRule.KeepRunning -> "Stops OurHome being closed on their phone."
        PhoneRule.PreventUninstall -> "Stops it being removed without your PIN."
        PhoneRule.BatteryUnrestricted -> "Stops battery saving from holding back rings and alerts."
    }

/**
 * The protections their phone must have on (one saves at a time), then how far
 * their phone got with Set up this phone. Each only from servers that send it.
 */
@Composable
private fun PhoneProtections(
    person: LookedAfter,
    savingRule: Pair<PhoneRule, Boolean>?,
    onRule: (PhoneRule, Boolean) -> Unit,
) {
    val rules = person.rules
    val setup = person.setupSummary()
    if (rules == null && setup == null) return
    val subtitle = listOfNotNull(
        rules?.let { r ->
            val count = PhoneRule.entries.count { it.of(r) }
            if (count == 0) "None required" else "$count of ${PhoneRule.entries.size} required"
        },
        setup,
    ).joinToString(" · ")
    CollapsibleCard("Phone protections", subtitle) {
        rules?.let {
            PhoneRule.entries.forEach { rule ->
                val pending = savingRule?.takeIf { it.first == rule }
                Column {
                    SwitchRow(
                        "Require ${rule.label}",
                        checked = pending?.second ?: rule.of(rules),
                        // One rule saves at a time per person.
                        enabled = savingRule == null,
                        onChange = { onRule(rule, it) },
                    )
                    Help(rule.help)
                }
            }
            Help(
                "Each one you require is a step in Set up this phone on their phone, and you're told if it gets " +
                    "turned off. Not requiring one only stops the step and the alert: whatever is already on stays on.",
            )
        }
        if (setup != null) {
            HorizontalDivider()
            Text("Set up this phone", style = MaterialTheme.typography.titleSmall)
            SetupSteps(person)
        }
    }
}

/** The end of an app's row in their usage: Block, or Unblock (red) once blocked; "Always open" for an app their phone never blocks. */
@Composable
private fun BlockButton(alwaysOpen: Boolean, blocked: Boolean, enabled: Boolean, onClick: (blocked: Boolean) -> Unit) {
    // One width whatever it says, so the times before it stay lined up.
    Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
        if (alwaysOpen) {
            Text(
                "Always open",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        } else {
            TextButton(
                onClick = { onClick(!blocked) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = if (blocked) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) {
                Text(if (blocked) "Unblock" else "Block", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The apps blocked on their phone, who blocked each and when, with Unblock. */
@Composable
private fun BlockedApps(
    blocking: LookedAfterBlocking,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    savingBlock: Pair<String, Boolean>?,
    onUnblock: (app: String, label: String) -> Unit,
) {
    if (blocking.apps.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text("Blocked apps", style = MaterialTheme.typography.titleSmall)
        if (blocking.works == false) {
            Warning("Keep Our Home running is off on their phone, so these apps open anyway until it's back on.")
        }
        blocking.apps.forEach { app ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                AppIconBox(app.app, app.label, app.icon, icons, loadIcon)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(app.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Blocked by ${app.byName} ${relativeTime(app.at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onUnblock(app.app, app.label) },
                    enabled = savingBlock == null,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Unblock", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Their daily limits: all apps together, then each app's own with who set it
 * (each changed or removed from its menu), and adding one for an app from
 * their usage ([NEW_LIMIT_MINUTES] to start). [blocksWork]: Keep Our Home
 * running is on there, which does the covering.
 */
@Composable
private fun DailyLimits(
    limits: LookedAfterLimits,
    choices: List<LimitChoice>,
    blocksWork: Boolean?,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    saving: Pair<String, Int?>?,
    onLimit: (app: String, label: String, minutes: Int?) -> Unit,
) {
    val minutesOf = { app: String, stored: Int? -> saving?.takeIf { it.first == app }?.let { it.second } ?: stored }
    // Opens by itself while something stops their limits working.
    val warn = !limits.isEmpty && (limits.counts == false || blocksWork == false)
    CollapsibleCard("Daily limits", limitsSummary(limits) ?: "No limits yet", startOpen = warn, openWhen = warn) {
        Help(
            "Give all their apps together, or one app, so much time a day. Their phone warns them 5 minutes " +
                "before, then closes the app until midnight, and you're told. Limits need Usage access and Keep " +
                "Our Home running on their phone.",
        )
        if (limits.counts == false) {
            Warning("Usage access is off on their phone, so it can't count their time until it's back on.")
        }
        if (blocksWork == false && !limits.isEmpty) {
            Warning("Keep Our Home running is off on their phone, so nothing closes when time runs out until it's back on.")
        }
        // The rows close together, as a list.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(ALL_APPS_LABEL, style = MaterialTheme.typography.bodySmall)
                    Text(
                        limits.total?.let { "Set by ${it.byName} ${relativeTime(it.at)}" } ?: "Together, apart from calls and messages",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LimitMenu(
                    minutes = if (saving?.first == ALL_APPS) saving.second else limits.total?.minutes,
                    noneLabel = "No limit",
                    enabled = saving == null,
                    onPick = { onLimit(ALL_APPS, ALL_APPS_LABEL, it) },
                )
            }
            limits.apps.forEach { limit ->
                LimitRow(limit, minutesOf(limit.app, limit.minutes), saving == null, icons, loadIcon, onLimit)
            }
            if (choices.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }, enabled = saving == null, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("Add a limit for an app", style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        choices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                leadingIcon = { AppIconBox(choice.app, choice.label, choice.icon, icons, loadIcon) },
                                onClick = {
                                    open = false
                                    onLimit(choice.app, choice.label, NEW_LIMIT_MINUTES)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One app's own limit: who set it, and its minutes as a menu that also removes it. */
@Composable
private fun LimitRow(
    limit: AppLimitEntry,
    minutes: Int?,
    enabled: Boolean,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    onLimit: (app: String, label: String, minutes: Int?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIconBox(limit.app, limit.label, limit.icon, icons, loadIcon)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(limit.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Set by ${limit.byName} ${relativeTime(limit.at)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LimitMenu(minutes, noneLabel = "Remove limit", enabled = enabled, onPick = { onLimit(limit.app, limit.label, it) })
    }
}

/** A limit's minutes ("No limit" when null), opening a menu of [limitOptions] with [noneLabel] (minutes null) first. */
@Composable
private fun LimitMenu(minutes: Int?, noneLabel: String, enabled: Boolean, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(start = 12.dp, end = 4.dp)) {
            Text(minutes?.let(::limitLabel) ?: "No limit", style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(noneLabel) }, onClick = { open = false; onPick(null) })
            limitOptions(minutes).forEach { option ->
                DropdownMenuItem(text = { Text(limitLabel(option)) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}

/**
 * Their schedules, each with Edit and Remove, and Add a schedule; both open
 * [ScheduleDialog]. [apps]: what a schedule can leave open.
 */
@Composable
private fun Schedules(
    schedules: List<ScheduleEntry>,
    apps: List<LimitChoice>,
    blocksWork: Boolean?,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    saving: Boolean,
    onSave: (id: String?, preset: SchedulePreset, allowed: List<String>, onSaved: () -> Unit) -> Unit,
    onRemove: (ScheduleEntry) -> Unit,
) {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleEntry?>(null) }
    // Opens by itself while their apps stay open during these.
    val warn = blocksWork == false && schedules.isNotEmpty()
    CollapsibleCard("Schedules", schedulesSummary(schedules) ?: "No schedules yet", startOpen = warn, openWhen = warn) {
        Help(
            "Close all their apps at set times, like bedtime or school, apart from calls, messages and any apps " +
                "you leave open. Their phone warns them 5 minutes before. Schedules need Keep Our Home running on " +
                "their phone.",
        )
        if (warn) {
            Warning("Keep Our Home running is off on their phone, so their apps stay open during these until it's back on.")
        }
        schedules.forEach { schedule ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(schedule.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(
                        "${daysLabel(schedule.days)} · ${scheduleTimes(schedule.start, schedule.end, is24)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (schedule.allowed.isNotEmpty()) {
                        Text(
                            "Still open: ${schedule.allowed.joinToString(", ") { it.label }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Set by ${schedule.byName} ${relativeTime(schedule.at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { editing = schedule }, enabled = !saving, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { onRemove(schedule) }, enabled = !saving, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Remove", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (schedules.size < MAX_SCHEDULES) {
            TextButton(onClick = { adding = true }, enabled = !saving, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("Add a schedule", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (adding || editing != null) {
        val current = editing
        ScheduleDialog(
            schedule = current,
            apps = apps,
            icons = icons,
            loadIcon = loadIcon,
            saving = saving,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { preset, allowed ->
                onSave(current?.id, preset, allowed) {
                    adding = false
                    editing = null
                }
            },
        )
    }
}

/**
 * A schedule's name, days, times and the apps still open meanwhile. A new one
 * starts as Bedtime, and the presets fill it in with a tap.
 */
@Composable
private fun ScheduleDialog(
    schedule: ScheduleEntry?,
    apps: List<LimitChoice>,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (preset: SchedulePreset, allowed: List<String>) -> Unit,
) {
    val first = schedule?.let { SchedulePreset(it.name, it.days, it.start, it.end) } ?: SCHEDULE_PRESETS[0]
    val timeOf = { minutes: Int -> LocalTime.of(minutes / 60, minutes % 60) }
    var name by remember { mutableStateOf(first.name) }
    var days by remember { mutableStateOf(first.days.toSet()) }
    var start by remember { mutableStateOf<LocalTime?>(timeOf(first.start)) }
    var end by remember { mutableStateOf<LocalTime?>(timeOf(first.end)) }
    var allowed by remember { mutableStateOf(schedule?.allowed?.map { it.app }.orEmpty()) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    // Apps already allowed that have left their usage still show, so they can be unticked.
    val choices = apps + schedule?.allowed.orEmpty().filter { a -> apps.none { it.app == a.app } }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (schedule == null) "Add a schedule" else "Edit ${schedule.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (schedule == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SCHEDULE_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = name == preset.name,
                                onClick = {
                                    name = preset.name
                                    days = preset.days.toSet()
                                    start = timeOf(preset.start)
                                    end = timeOf(preset.end)
                                },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = errors["name"] != null,
                    supportingText = errors["name"]?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldLabel("Days")
                DayToggleRow(EVERY_DAY, days, { SCHEDULE_DAYS[it] }) { day -> days = if (day in days) days - day else days + day }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { days = WEEKDAYS.toSet() }) { Text("Weekdays") }
                    TextButton(onClick = { days = EVERY_DAY.toSet() }) { Text("Every day") }
                }
                FieldError(errors["days"])
                TimeField("From", start, errors["start"]) { start = it }
                TimeField("To", end, errors["end"]) { end = it }
                val from = start
                val to = end
                if (from != null && to != null && to < from) {
                    Text(
                        "It ends the next morning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (choices.isNotEmpty()) {
                    FieldLabel("Still open (calls and messages always are)")
                    choices.forEach { app ->
                        val on = app.app in allowed
                        val canTick = on || allowed.size < MAX_ALLOWED
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canTick) { allowed = if (on) allowed - app.app else allowed + app.app },
                        ) {
                            Checkbox(checked = on, onCheckedChange = null, enabled = canTick)
                            AppIconBox(app.app, app.label, app.icon, icons, loadIcon)
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val from = start
                    val to = end
                    val found = buildMap {
                        if (name.isBlank()) put("name", "Give it a name.")
                        if (days.isEmpty()) put("days", "Pick at least one day.")
                        if (from == null) put("start", "Choose a start time.")
                        if (to == null) put("end", "Choose an end time.") else if (from == to) put("end", "The end has to be a different time from the start.")
                    }
                    errors = found
                    if (found.isEmpty() && from != null && to != null) {
                        val minutes = { t: LocalTime -> t.hour * 60 + t.minute }
                        onSave(SchedulePreset(name.trim(), days.sorted(), minutes(from), minutes(to)), allowed)
                    }
                },
            ) { Text(if (saving) "Saving..." else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

/**
 * Points for screen time: whether they may spend points on more time, what a
 * point buys and the most a day, their points to spend and today's extra
 * minutes, and Give extra time. Only while the household uses Points.
 */
@Composable
private fun PointsForTime(
    points: LookedAfterPointsTime,
    hasLimits: Boolean,
    saving: Boolean,
    onRule: (minutesPerPoint: Int?, dailyCap: Int) -> Unit,
    onGive: (minutes: Int) -> Unit,
) {
    if (!points.available) return
    val rule = points.rule
    val subtitle = rule?.let { "1 point buys ${formatMinutes(it.minutesPerPoint)}, up to ${formatMinutes(it.dailyCap)} a day" } ?: "Off"
    CollapsibleCard("Points for screen time", subtitle) {
        Help(
            "Lets them spend task points on more time on today's limits, at the rate you set and up to a daily " +
                "most; you're told each time. Their earned points in the stats don't change.",
        )
        // The switch, its menus and the balance close together.
        Column {
            SwitchRow(
                "Let them spend points on more time",
                checked = rule != null,
                enabled = !saving,
                onChange = { on -> onRule(if (on) 5 else null, rule?.dailyCap ?: 30) },
            )
            if (rule != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("1 point buys", style = MaterialTheme.typography.bodySmall)
                    MinutesMenu(rule.minutesPerPoint, (RATE_CHOICES + rule.minutesPerPoint).distinct().sorted(), !saving) {
                        onRule(it, rule.dailyCap)
                    }
                    Text("up to", style = MaterialTheme.typography.bodySmall)
                    MinutesMenu(rule.dailyCap, (CAP_CHOICES + rule.dailyCap).distinct().sorted(), !saving) {
                        onRule(rule.minutesPerPoint, it)
                    }
                    Text("a day", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "${formatPoints(points.balanceCenti.coerceAtLeast(0))} points to spend" +
                    (if (points.bought > 0) " · bought ${formatMinutes(points.bought)} today" else "") +
                    (if (points.given > 0) " · given ${formatMinutes(points.given)} today" else "") +
                    (rule?.let { " · set by ${it.byName} ${relativeTime(it.at)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasLimits) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Give extra time today:", style = MaterialTheme.typography.bodySmall)
                    GIVE_STEPS.forEach { minutes ->
                        TextButton(onClick = { onGive(minutes) }, enabled = !saving, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("+${formatMinutes(minutes)}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Help("Adds minutes to today's limits without points.")
            }
        } else {
            Help("Extra time goes on top of their daily limits, and they have none yet.")
        }
    }
}

/** [minutes] as a small menu of [options]. */
@Composable
private fun MinutesMenu(minutes: Int, options: List<Int>, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(start = 8.dp, end = 2.dp)) {
            Text(formatMinutes(minutes), style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(formatMinutes(option)) }, onClick = { open = false; onPick(option) })
            }
        }
    }
}
