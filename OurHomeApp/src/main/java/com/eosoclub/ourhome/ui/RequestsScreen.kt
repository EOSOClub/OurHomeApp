package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.HouseholdRequest
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.Permission
import com.eosoclub.ourhome.data.SessionUser
import com.eosoclub.ourhome.data.UserRef
import com.eosoclub.ourhome.data.can
import com.eosoclub.ourhome.data.effectiveStatus
import com.eosoclub.ourhome.data.mediaStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Mirrors REQUEST_CATEGORIES / MEDIA_TYPES / MAINTENANCE_STATUSES in the web
// app's src/lib/enums.ts.
private val CATEGORIES = listOf("media" to "Media", "maintenance" to "Maintenance")
private val MEDIA_TYPES = listOf("movie" to "Movie", "tv" to "TV show")
private val MEDIA_SECTIONS = listOf("movie" to "Movies", "tv" to "TV shows")
// Media is one step: waiting → added. ([mediaStatus] folds the older flow's
// "accepted" into waiting.)
private val MEDIA_STATUS_ORDER = listOf("pending", "completed")
private val MEDIA_STATUS_LABELS = mapOf("pending" to "Waiting", "completed" to "Added")
private val MAINTENANCE_SECTIONS = listOf(
    "pending" to "Waiting for acceptance",
    "accepted" to "In progress",
    "completed" to "Done",
)

/** What the editor submits. */
sealed interface RequestEdit {
    data class Media(val mediaType: String, val title: String, val year: Int, val season: Int?) : RequestEdit
    data class Maintenance(val title: String, val details: String?, val assigneeId: String) : RequestEdit
}

class RequestsViewModel(private val api: ApiClient) : ViewModel() {
    /** The editor is open when [creating] is true or [editing] holds the request being changed. */
    data class UiState(
        val requests: List<HouseholdRequest> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val editing: HouseholdRequest? = null,
        val creating: Boolean = false,
        val accepting: HouseholdRequest? = null,
        val saving: Boolean = false,
        val busy: Set<String> = emptySet(),
        // Null until loaded; for "who should do it?".
        val members: List<Member>? = null,
        val membersError: String? = null,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load({ api.requests() }, { s, v -> s.copy(requests = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
    }

    private fun loadMembers() {
        if (state.value.members != null) return
        state.update { it.copy(membersError = null) }
        viewModelScope.launch {
            runCatching { api.householdMembers() }
                .onSuccess { m -> state.update { it.copy(members = m) } }
                // Left null so the next time the editor opens retries.
                .onFailure { e -> state.update { it.copy(membersError = e.message ?: "Couldn't load household members") } }
        }
    }

    fun startCreate() {
        state.update { it.copy(creating = true, editing = null) }
        loadMembers()
    }

    fun startEdit(r: HouseholdRequest) {
        state.update { it.copy(editing = r, creating = false) }
        if (r.category == "maintenance") loadMembers()
    }

    fun closeEditor() = state.update { it.copy(editing = null, creating = false) }

    fun save(id: String?, edit: RequestEdit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            val saved = when (edit) {
                is RequestEdit.Media -> api.saveMediaRequest(id, edit.mediaType, edit.title, edit.year, edit.season)
                is RequestEdit.Maintenance -> api.saveMaintenanceRequest(id, edit.title, edit.details, edit.assigneeId)
            }
            closeEditor()
            _messages.send(
                when {
                    id != null -> "Request updated"
                    saved.category == "maintenance" -> "Sent to ${saved.assignee?.name ?: "them"}"
                    else -> "Requested “${saved.title}”"
                },
            )
            refresh()
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't save request")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    fun delete(r: HouseholdRequest) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            api.deleteRequest(r.id)
            state.update { s -> s.copy(requests = s.requests.filterNot { it.id == r.id }) }
            closeEditor()
            _messages.send("Request removed")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't remove request")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    fun startAccept(r: HouseholdRequest) = state.update { it.copy(accepting = r) }
    fun cancelAccept() = state.update { it.copy(accepting = null) }

    /** Assignee commits to a done-by date — the request's deadline. */
    fun accept(r: HouseholdRequest, date: LocalDate) = runBusy(r) {
        val updated = api.acceptRequest(r.id, dueInstant(date, null)!!)
        replace(updated)
        state.update { it.copy(accepting = null) }
        _messages.send("Done by ${formatDate(updated.dueAt!!)} — got it")
    }

    /** Maintenance: the assignee marks it done. Media: an approver marks it added (one step). */
    fun complete(r: HouseholdRequest) = runBusy(r) {
        replace(api.completeRequest(r.id))
        _messages.send(if (r.category == "media") "Added “${r.title}”" else "Marked “${r.title}” done")
    }

    private fun runBusy(r: HouseholdRequest, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + r.id) }
        try {
            block()
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't update request")
        } finally {
            state.update { it.copy(busy = it.busy - r.id) }
        }
    }

    private fun replace(updated: HouseholdRequest) =
        state.update { s -> s.copy(requests = s.requests.map { if (it.id == updated.id) updated else it }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    api: ApiClient,
    user: SessionUser,
    /** Requests "Add" in the head's permissions grid (Members → Permissions). */
    canSubmit: Boolean,
    /** Requests "Approve" in that grid: marks media requests added. */
    canApproveMedia: Boolean,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { RequestsViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val canWrite = can(user.role, Permission.RequestsWrite)

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.loading && state.requests.isNotEmpty(),
            onRefresh = { vm.refresh() },
        ) {
            if (LoadState(state.loading, state.error, state.requests.isEmpty(), "No requests yet.", vm::refresh)) {
                return@PullToRefreshBox
            }
            val maintenance = state.requests.filter { it.category == "maintenance" }
            val media = state.requests.filter { it.category == "media" }
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (maintenance.isNotEmpty()) {
                    categoryHeader("Maintenance")
                    MAINTENANCE_SECTIONS.forEach { (status, label) ->
                        val items = maintenance.filter { it.status == status }
                            .let { list -> if (status == "accepted") list.sortedBy { it.dueAt } else list }
                        if (items.isEmpty()) return@forEach
                        sectionHeader("maint-$status", label, items.size)
                        items(items, key = { it.id }) { r ->
                            MaintenanceCard(
                                r = r,
                                me = user.id,
                                busy = r.id in state.busy,
                                onAccept = { vm.startAccept(r) },
                                onComplete = { vm.complete(r) },
                                onEdit = if (canWrite && r.requester.id == user.id) ({ vm.startEdit(r) }) else null,
                            )
                        }
                    }
                }
                if (media.isNotEmpty()) {
                    categoryHeader("Media")
                    MEDIA_SECTIONS.forEach { (type, label) ->
                        // Waiting first, added last.
                        val items = media.filter { it.mediaType == type }
                            .sortedBy { MEDIA_STATUS_ORDER.indexOf(it.mediaStatus) }
                        sectionHeader("media-$type", label, items.size)
                        if (items.isEmpty()) {
                            item(key = "empty-$type") {
                                Text(
                                    "No ${label.lowercase()} requested yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(items, key = { it.id }) { r ->
                            val isOwn = r.requester.id == user.id
                            MediaCard(
                                r = r,
                                isOwn = isOwn,
                                canManage = canApproveMedia,
                                busy = r.id in state.busy,
                                onMarkAdded = { vm.complete(r) },
                                onEdit = if (isOwn && canWrite) ({ vm.startEdit(r) }) else null,
                            )
                        }
                    }
                }
            }
        }
        if (canSubmit) {
            ExtendedFloatingActionButton(
                onClick = { vm.startCreate() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Request") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (state.creating || state.editing != null) {
        RequestEditor(
            initial = state.editing,
            assignees = state.members?.filterNot { it.id == user.id },
            assigneesError = state.membersError,
            saving = state.saving,
            onDismiss = vm::closeEditor,
            onSave = { edit -> vm.save(state.editing?.id, edit) },
            onDelete = state.editing?.let { r -> { vm.delete(r) } },
        )
    }

    state.accepting?.let { r ->
        AcceptDatePicker(
            rescheduling = r.status == "accepted",
            initial = r.dueAt?.let(::localDate),
            onDismiss = vm::cancelAccept,
            onPick = { date -> vm.accept(r, date) },
        )
    }
}

private fun LazyListScope.categoryHeader(label: String) {
    item(key = "cat-$label") {
        Column(Modifier.padding(top = 8.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }
    }
}

private fun LazyListScope.sectionHeader(key: String, label: String, count: Int) {
    item(key = "sec-$key") {
        Text(
            "${label.uppercase()} ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
    }
}

private fun who(person: UserRef?, me: String) = when {
    person == null -> "someone"
    person.id == me -> "you"
    else -> person.name ?: "someone"
}

@Composable
private fun MediaCard(
    r: HouseholdRequest,
    isOwn: Boolean,
    canManage: Boolean,
    busy: Boolean,
    onMarkAdded: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val status = r.mediaStatus
    // Highlight requests waiting on *you* (an approver).
    val colors = if (canManage && status == "pending") {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        r.year?.let { "${r.title} ($it)" } ?: r.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SuggestionChip(onClick = {}, label = { Text(MEDIA_STATUS_LABELS[status] ?: status) })
                        r.season?.let { SuggestionChip(onClick = {}, label = { Text("Season $it") }) }
                    }
                    Text(
                        "Requested by ${if (isOwn) "you" else r.requester.name ?: "someone"} · ${formatDate(r.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onEdit != null) {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${r.title}") }
                }
            }
            if (canManage && status == "pending") {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Button(onClick = onMarkAdded, enabled = !busy) { Text(if (busy) "Saving…" else "Mark as added") }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceCard(
    r: HouseholdRequest,
    me: String,
    busy: Boolean,
    onAccept: () -> Unit,
    onComplete: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val isAssignee = r.assignee?.id == me
    val done = r.status == "completed"
    // Highlight requests waiting on *you*.
    val colors = if (isAssignee && r.status == "pending") {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        r.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    val deadline = when {
                        r.status == "accepted" && r.dueAt != null -> dueLabel(r.dueAt, prefix = "Done by")
                        done && r.completedAt != null -> "Done ${formatDate(r.completedAt)}" to false
                        else -> null
                    }
                    deadline?.let { (label, overdue) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    r.details?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${who(r.requester, me).replaceFirstChar(Char::uppercase)} asked ${who(r.assignee, me)} · ${formatDate(r.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onEdit != null) {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${r.title}") }
                }
            }
            if (isAssignee && !done) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    if (r.status == "pending") {
                        Button(onClick = onAccept, enabled = !busy) { Text("Accept") }
                    } else {
                        TextButton(onClick = onAccept, enabled = !busy) { Text("Change date") }
                        OutlinedButton(onClick = onComplete, enabled = !busy) { Text(if (busy) "Saving…" else "Mark done") }
                    }
                }
            }
        }
    }
}

/** The assignee picks their done-by date; past days can't be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcceptDatePicker(
    rescheduling: Boolean,
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val todayUtcMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtcMillis
            override fun isSelectableYear(year: Int) = year >= LocalDate.now().year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
            ) { Text(if (rescheduling) "Save date" else "Accept") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    "When will you have it done?",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

/**
 * Step-by-step form, matching the web: request type, then either media
 * (type → name, season, year) or maintenance (what → details → who).
 * The category is fixed once a request exists.
 */
@Composable
private fun RequestEditor(
    initial: HouseholdRequest?,
    assignees: List<Member>?,
    assigneesError: String?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (RequestEdit) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var category by remember { mutableStateOf(initial?.category.orEmpty()) }
    var mediaType by remember { mutableStateOf(initial?.mediaType.orEmpty()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var year by remember { mutableStateOf(initial?.year?.toString().orEmpty()) }
    var season by remember { mutableStateOf(initial?.season?.toString().orEmpty()) }
    var details by remember { mutableStateOf(initial?.details.orEmpty()) }
    var assigneeId by remember { mutableStateOf(initial?.assignee?.id.orEmpty()) }

    val yearValue = year.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1870..2100 }
    val seasonValue = season.toIntOrNull()
    val seasonValid = season.isBlank() || seasonValue in 1..100
    val mediaReady = category == "media" && mediaType.isNotEmpty() && title.isNotBlank() && yearValue != null && seasonValid
    val maintenanceReady = category == "maintenance" && title.isNotBlank() && assigneeId.isNotEmpty()
    val yearLabel = when {
        mediaType != "tv" -> "Release year"
        seasonValue != null && seasonValid -> "Year season $seasonValue came out"
        else -> "Year of first season"
    }

    EditorDialog(
        title = if (initial == null) "New request" else "Edit request",
        saving = saving,
        saveEnabled = mediaReady || maintenanceReady,
        onDismiss = onDismiss,
        onSave = {
            onSave(
                if (category == "media") {
                    RequestEdit.Media(mediaType, title.trim(), yearValue!!, if (mediaType == "tv") seasonValue else null)
                } else {
                    RequestEdit.Maintenance(title.trim(), details.trim().ifEmpty { null }, assigneeId)
                },
            )
        },
        deleteLabel = "Remove request",
        onDelete = onDelete,
    ) {
        if (initial == null) {
            DropdownField("What would you like to request?", CATEGORIES, category, { category = it })
        } else {
            FieldLabel(CATEGORIES.firstOrNull { it.first == category }?.second ?: category)
        }

        if (category == "media") {
            DropdownField("Type of media", MEDIA_TYPES, mediaType, { mediaType = it })
        }
        if (category == "media" && mediaType.isNotEmpty()) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                placeholder = { Text(if (mediaType == "tv") "e.g. Severance" else "e.g. Dune") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            if (mediaType == "tv") {
                OutlinedTextField(
                    value = season,
                    onValueChange = { season = it.filter(Char::isDigit).take(3) },
                    label = { Text("Season (optional)") },
                    supportingText = { Text("Leave blank to request the whole show.") },
                    singleLine = true,
                    isError = !seasonValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter(Char::isDigit).take(4) },
                label = { Text(yearLabel) },
                placeholder = { Text("YYYY") },
                singleLine = true,
                isError = year.isNotEmpty() && yearValue == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (category == "maintenance") {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What needs doing?") },
                placeholder = { Text("e.g. Fix the leaky kitchen faucet") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("Details (optional)") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (assignees == null) {
                Text(
                    assigneesError?.let { "Couldn't load household members: $it" } ?: "Loading household members…",
                    color = if (assigneesError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (assignees.isEmpty()) {
                Text("There's no one else in the household to ask yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                DropdownField(
                    "Who should do it?",
                    assignees.map { it.id to it.name },
                    assigneeId,
                    { assigneeId = it },
                    placeholder = "Choose someone…",
                )
                if (initial?.status == "accepted" && assigneeId != initial.assignee?.id) {
                    Text(
                        "Changing who does it sends the request back for them to accept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
