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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import com.eosoclub.ourhome.data.RequestModule
import com.eosoclub.ourhome.data.SessionUser
import com.eosoclub.ourhome.data.UserRef
import com.eosoclub.ourhome.data.can
import com.eosoclub.ourhome.data.defaultRequestModules
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

// Mirrors MEDIA_TYPES / MAINTENANCE_STATUSES in the web app's src/lib/enums.ts.
// Which kinds of request are on, and their names, come from the server
// ([RequestModule], set by the head in Settings → Request types).
private val MEDIA_TYPES = listOf("movie" to "Movie", "tv" to "TV show")
private val MEDIA_SECTIONS = listOf("movie" to "Movies", "tv" to "TV shows")
// Media is one step: waiting → added, or declined where the head allows it.
// ([mediaStatus] folds the older flow's "accepted" into waiting.)
private val MEDIA_STATUS_ORDER = listOf("pending", "completed", "declined")
private val MEDIA_STATUS_LABELS = mapOf("pending" to "Waiting", "completed" to "Added", "declined" to "Declined")
private val MAINTENANCE_SECTIONS = listOf(
    "pending" to "Waiting for acceptance",
    "accepted" to "In progress",
    "completed" to "Done",
    "declined" to "Declined",
)

/** What the editor submits. */
sealed interface RequestEdit {
    /** [issue]: a problem with something the household has; [details] says what's wrong. */
    data class Media(
        val mediaType: String,
        val title: String,
        val year: Int,
        val season: Int?,
        val issue: Boolean = false,
        val episode: Int? = null,
        val details: String? = null,
    ) : RequestEdit
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
        val declining: HouseholdRequest? = null,
        /** An own declined request waiting for "Remove?" to be confirmed. */
        val removing: HouseholdRequest? = null,
        val saving: Boolean = false,
        val busy: Set<String> = emptySet(),
        // Null until loaded; for "who should do it?".
        val members: List<Member>? = null,
        val membersError: String? = null,
        /**
         * The kinds of request that are on, as this member sees them. Null until
         * loaded, or from a server from before request types: callers then use
         * [defaultRequestModules].
         */
        val modules: List<RequestModule>? = null,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        launch {
            // Kept from the last load when this one fails; null stays null on an older server.
            runCatching { api.requestModules() }.onSuccess { m -> state.update { it.copy(modules = m) } }
        }
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

    /** The open editor's (or the accept date picker's) last failure: why, and which fields to mark. */
    val form = FormErrors()

    fun startCreate() {
        form.clear()
        state.update { it.copy(creating = true, editing = null) }
        loadMembers()
    }

    fun startEdit(r: HouseholdRequest) {
        form.clear()
        state.update { it.copy(editing = r, creating = false) }
        if (r.category == "maintenance") loadMembers()
    }

    fun closeEditor() {
        form.clear()
        state.update { it.copy(editing = null, creating = false) }
    }

    fun save(id: String?, edit: RequestEdit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            val saved = when (edit) {
                is RequestEdit.Media -> api.saveMediaRequest(
                    id, edit.mediaType, edit.title, edit.year, edit.season, edit.issue, edit.episode, edit.details,
                )
                is RequestEdit.Maintenance -> api.saveMaintenanceRequest(id, edit.title, edit.details, edit.assigneeId)
            }
            closeEditor()
            _messages.send(
                when {
                    id != null -> "Request updated"
                    saved.category == "maintenance" -> "Sent to ${saved.assignee?.name ?: "them"}"
                    saved.issue -> "Reported a problem with “${saved.title}”"
                    else -> "Requested “${saved.title}”"
                },
            )
            refresh()
        } catch (e: Exception) {
            // Shown in the editor, which sits over the snackbar.
            form.fail(e, "Couldn't save request")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    fun delete(r: HouseholdRequest) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            api.deleteRequest(r.id)
            state.update { s -> s.copy(requests = s.requests.filterNot { it.id == r.id }) }
            closeEditor()
            _messages.send("Request removed")
        } catch (e: Exception) {
            form.fail(e, "Couldn't remove request")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    fun startAccept(r: HouseholdRequest) {
        form.clear()
        state.update { it.copy(accepting = r) }
    }

    fun cancelAccept() {
        form.clear()
        state.update { it.copy(accepting = null) }
    }

    /**
     * Assignee commits to a done-by date - the request's deadline. [date] null:
     * accepted without one, where the head made the date optional.
     */
    fun accept(r: HouseholdRequest, date: LocalDate?) = runBusy(r, inDialog = true) {
        val updated = api.acceptRequest(r.id, date?.let { dueInstant(it, null) })
        replace(updated)
        state.update { it.copy(accepting = null) }
        _messages.send(updated.dueAt?.let { "Done by ${formatDate(it)} - got it" } ?: "Accepted “${r.title}”")
    }

    /** Maintenance: the assignee marks it done. Media: an approver marks it added, or a problem fixed (one step). */
    fun complete(r: HouseholdRequest) = runBusy(r) {
        replace(api.completeRequest(r.id))
        _messages.send(
            when {
                r.issue -> "Marked “${r.title}” fixed"
                r.category == "media" -> "Added “${r.title}”"
                else -> "Marked “${r.title}” done"
            },
        )
    }

    fun startDecline(r: HouseholdRequest) {
        form.clear()
        state.update { it.copy(declining = r) }
    }

    fun cancelDecline() {
        form.clear()
        state.update { it.copy(declining = null) }
    }

    /** Whoever handles it says no, with an optional [reply] the requester sees. */
    fun decline(r: HouseholdRequest, reply: String?) = runBusy(r, inDialog = true) {
        replace(api.declineRequest(r.id, reply))
        state.update { it.copy(declining = null) }
        _messages.send("Declined “${r.title}”")
    }

    /** A declined request can't be edited, only removed: asks first. */
    fun askRemove(r: HouseholdRequest?) = state.update { it.copy(removing = r) }

    /** No editor is open, so a failure goes to the snackbar. */
    fun confirmRemove() {
        val r = state.value.removing ?: return
        state.update { it.copy(removing = null) }
        runBusy(r) {
            api.deleteRequest(r.id)
            state.update { s -> s.copy(requests = s.requests.filterNot { it.id == r.id }) }
            _messages.send("Request removed")
        }
    }

    /** [inDialog]: the failure is shown in the open date picker, which covers the snackbar. */
    private fun runBusy(r: HouseholdRequest, inDialog: Boolean = false, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + r.id) }
        if (inDialog) form.clear()
        try {
            block()
        } catch (e: Exception) {
            if (inDialog) form.fail(e, "Couldn't update request")
            else _messages.send(e.message ?: "Couldn't update request")
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
    /**
     * Requests "Add" in the head's permissions grid (Members → Permissions).
     * Only used with a server from before request types; newer ones say per kind.
     */
    canSubmit: Boolean,
    /** Requests "Approve" in that grid: marks media requests added. Same fallback. */
    canApproveMedia: Boolean,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { RequestsViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val canWrite = can(user.role, Permission.RequestsWrite)
    // The kinds that are on (Settings → Request types on the website).
    val modules = state.modules ?: defaultRequestModules(canSubmit, canApproveMedia)
    val mediaModule = modules.firstOrNull { it.id == "media" }
    val maintenanceModule = modules.firstOrNull { it.id == "maintenance" }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.loading && state.requests.isNotEmpty(),
            onRefresh = { vm.refresh() },
        ) {
            val empty = if (modules.isEmpty()) {
                "No request types are on. The Head of House chooses them on the website, in Settings → Request types."
            } else {
                "No requests yet."
            }
            if (LoadState(state.loading, state.error, state.requests.isEmpty(), empty, vm::refresh)) {
                return@PullToRefreshBox
            }
            // The server only sends requests of kinds that are on.
            val maintenance = if (maintenanceModule != null) state.requests.filter { it.category == "maintenance" } else emptyList()
            val media = if (mediaModule != null) state.requests.filter { it.category == "media" } else emptyList()
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (maintenanceModule != null && maintenance.isNotEmpty()) {
                    categoryHeader(maintenanceModule.label)
                    MAINTENANCE_SECTIONS.forEach { (status, label) ->
                        val items = maintenance.filter { it.status == status }
                            .let { list -> if (status == "accepted") list.sortedBy { it.dueAt } else list }
                        if (items.isEmpty()) return@forEach
                        sectionHeader("maint-$status", label, items.size)
                        items(items, key = { it.id }) { r ->
                            val isOwn = r.requester.id == user.id
                            val declined = r.status == "declined"
                            MaintenanceCard(
                                r = r,
                                me = user.id,
                                requireDueDate = maintenanceModule.requireDueDate,
                                canDecline = maintenanceModule.allowDecline,
                                busy = r.id in state.busy,
                                onAccept = { vm.startAccept(r) },
                                onComplete = { vm.complete(r) },
                                onDecline = { vm.startDecline(r) },
                                // A declined request can't be edited, only removed.
                                onEdit = if (isOwn && canWrite && !declined) ({ vm.startEdit(r) }) else null,
                                onRemove = if (isOwn && canWrite && declined) ({ vm.askRemove(r) }) else null,
                            )
                        }
                    }
                }
                if (mediaModule != null && media.isNotEmpty()) {
                    fun mediaCards(list: List<HouseholdRequest>) = items(list, key = { it.id }) { r ->
                        val isOwn = r.requester.id == user.id
                        val declined = r.mediaStatus == "declined"
                        MediaCard(
                            r = r,
                            me = user.id,
                            isOwn = isOwn,
                            canManage = mediaModule.handles,
                            canDecline = mediaModule.handles && mediaModule.allowDecline,
                            busy = r.id in state.busy,
                            onMarkAdded = { vm.complete(r) },
                            onDecline = { vm.startDecline(r) },
                            // A declined request can't be edited, only removed.
                            onEdit = if (isOwn && canWrite && !declined) ({ vm.startEdit(r) }) else null,
                            onRemove = if (isOwn && canWrite && declined) ({ vm.askRemove(r) }) else null,
                        )
                    }
                    categoryHeader(mediaModule.label)
                    // Problems with what the household already has, above the requests to add.
                    val problems = media.filter { it.issue }.sortedBy { MEDIA_STATUS_ORDER.indexOf(it.mediaStatus) }
                    if (problems.isNotEmpty()) {
                        sectionHeader("media-problems", "Problems", problems.size)
                        mediaCards(problems)
                    }
                    MEDIA_SECTIONS.forEach { (type, label) ->
                        // Waiting first, then added, then declined.
                        val items = media.filter { it.mediaType == type && !it.issue }
                            .sortedBy { MEDIA_STATUS_ORDER.indexOf(it.mediaStatus) }
                        // A kind the head stopped offering still shows while it has requests.
                        if (items.isEmpty() && type !in mediaModule.mediaTypes) return@forEach
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
                        mediaCards(items)
                    }
                }
            }
        }
        if (modules.any { it.canAsk }) {
            ExtendedFloatingActionButton(
                onClick = { vm.startCreate() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Request") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (state.creating || state.editing != null) {
        // Who may be asked: the head's list from the server; a server from
        // before request types lets anyone else be asked.
        val allowed = maintenanceModule?.assigneeIds?.toSet()?.takeIf { state.modules != null }
        val initial = state.editing
        RequestEditor(
            initial = initial,
            modules = modules,
            assignees = state.members?.filter {
                it.id != user.id && (allowed == null || it.id in allowed || it.id == initial?.assignee?.id)
            },
            assigneesError = state.membersError,
            saving = state.saving,
            errors = vm.form,
            onDismiss = vm::closeEditor,
            onSave = { edit -> vm.save(state.editing?.id, edit) },
            onDelete = state.editing?.let { r -> { vm.delete(r) } },
        )
    }

    state.accepting?.let { r ->
        AcceptDatePicker(
            rescheduling = r.status == "accepted",
            requireDueDate = maintenanceModule?.requireDueDate ?: true,
            initial = r.dueAt?.let(::localDate),
            error = vm.form["dueAt"] ?: vm.form.message,
            busy = r.id in state.busy,
            onDismiss = vm::cancelAccept,
            onPick = { date -> vm.accept(r, date) },
        )
    }

    state.declining?.let { r ->
        DeclineDialog(
            r = r,
            error = vm.form["reply"] ?: vm.form.message,
            busy = r.id in state.busy,
            onDismiss = vm::cancelDecline,
            onDecline = { reply -> vm.decline(r, reply) },
        )
    }

    state.removing?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.askRemove(null) },
            title = { Text("Remove this request?") },
            text = { Text("“${r.title}” will be removed for everyone.") },
            confirmButton = { TextButton(onClick = vm::confirmRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { vm.askRemove(null) }) { Text("Cancel") } },
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
    me: String,
    isOwn: Boolean,
    /** Handles media: marks it added. */
    canManage: Boolean,
    /** Handles media and the head allows declining. */
    canDecline: Boolean,
    busy: Boolean,
    onMarkAdded: () -> Unit,
    onDecline: () -> Unit,
    onEdit: (() -> Unit)?,
    /** Own declined request: removing is all that's left. */
    onRemove: (() -> Unit)?,
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
                    // Problems are listed together, so each says what kind of media it is.
                    val kind = if (r.issue) MEDIA_TYPES.firstOrNull { it.first == r.mediaType }?.second else null
                    if (kind != null) {
                        Text(kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        r.year?.let { "${r.title} ($it)" } ?: r.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val statusLabel = if (r.issue && status == "completed") "Fixed" else MEDIA_STATUS_LABELS[status] ?: status
                        SuggestionChip(onClick = {}, label = { Text(statusLabel) })
                        val place = when {
                            r.season != null -> "Season ${r.season}" + r.episode?.let { " · Episode $it" }.orEmpty()
                            r.issue && r.mediaType == "tv" -> "Whole show"
                            else -> null
                        }
                        place?.let { SuggestionChip(onClick = {}, label = { Text(it) }) }
                    }
                    if (r.issue) {
                        r.details?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (status == "declined") {
                        val by = r.decidedBy?.let { " by ${who(it, me)}" }.orEmpty()
                        Text(
                            "Declined$by" + r.reply?.let { ": $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${if (r.issue) "Reported" else "Requested"} by ${if (isOwn) "you" else r.requester.name ?: "someone"} · ${formatDate(r.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onEdit != null) {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${r.title}") }
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${r.title}") }
                }
            }
            if (canManage && status == "pending") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    if (canDecline) {
                        TextButton(onClick = onDecline, enabled = !busy) { Text("Decline") }
                    }
                    Button(onClick = onMarkAdded, enabled = !busy) {
                        Text(
                            when {
                                busy -> "Saving…"
                                r.issue -> "Mark fixed"
                                else -> "Mark as added"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceCard(
    r: HouseholdRequest,
    me: String,
    /** Accepting needs a done-by date (the head's choice). */
    requireDueDate: Boolean,
    /** The head lets the person asked decline. */
    canDecline: Boolean,
    busy: Boolean,
    onAccept: () -> Unit,
    onComplete: () -> Unit,
    onDecline: () -> Unit,
    onEdit: (() -> Unit)?,
    /** Own declined request: removing is all that's left. */
    onRemove: (() -> Unit)?,
) {
    val isAssignee = r.assignee?.id == me
    val done = r.status == "completed"
    val declined = r.status == "declined"
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
                        color = if (done || declined) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
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
                    if (declined) {
                        val by = r.decidedBy?.let { " by ${who(it, me)}" }.orEmpty()
                        Text(
                            "Declined$by" + r.reply?.let { ": $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                if (onRemove != null) {
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${r.title}") }
                }
            }
            if (isAssignee && !done && !declined) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    if (canDecline) {
                        TextButton(onClick = onDecline, enabled = !busy) { Text("Decline") }
                    }
                    if (r.status == "pending") {
                        Button(onClick = onAccept, enabled = !busy) { Text("Accept") }
                    } else {
                        // Accepted without a date (the head made it optional): one can still be set.
                        TextButton(onClick = onAccept, enabled = !busy) {
                            Text(if (r.dueAt != null || requireDueDate) "Change date" else "Set a date")
                        }
                        OutlinedButton(onClick = onComplete, enabled = !busy) { Text(if (busy) "Saving…" else "Mark done") }
                    }
                }
            }
        }
    }
}

/**
 * The assignee picks their done-by date; past days can't be chosen. Stays
 * open until it's saved; [error] says why it wasn't (the snackbar is behind).
 * Without [requireDueDate] (the head's choice) "No date" accepts without one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcceptDatePicker(
    rescheduling: Boolean,
    requireDueDate: Boolean,
    initial: LocalDate?,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    /** null: no date. */
    onPick: (LocalDate?) -> Unit,
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
                enabled = state.selectedDateMillis != null && !busy,
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
            ) { Text(if (rescheduling) "Save date" else "Accept") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (!requireDueDate) {
                    TextButton(enabled = !busy, onClick = { onPick(null) }) { Text("No date") }
                }
            }
        },
    ) {
        DatePicker(
            state = state,
            title = {
                Column(Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) {
                    Text(
                        if (requireDueDate) "When will you have it done?" else "When will you have it done? (optional)",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (error != null) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}

/** Whoever handles the request says no, with an optional reason the requester sees. */
@Composable
private fun DeclineDialog(
    r: HouseholdRequest,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDecline: (String?) -> Unit,
) {
    var reply by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Decline request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“${r.title}” from ${r.requester.name ?: "someone"}")
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it.take(500) },
                    label = { Text("Reason (optional)") },
                    placeholder = {
                        Text(
                            when {
                                r.category == "maintenance" -> "e.g. I'm away this week, ask someone else"
                                r.issue -> "e.g. That's the only copy there is"
                                else -> "e.g. We already have it on another service"
                            },
                        )
                    },
                    supportingText = { Text(error ?: "Everyone in the household sees it.") },
                    isError = error != null,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onDecline(reply.trim().ifEmpty { null }) }) {
                Text(if (busy) "Saving…" else "Decline")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Step-by-step form, matching the web: request type, then either media
 * (something new or a problem → type → name, season, year, and for a problem
 * the episode and what's wrong) or maintenance (what → details → who).
 * The category, and whether media is new or a problem, are fixed once a
 * request exists. What it offers comes from the
 * head's request types ([modules]): the kinds this member may ask for, the
 * kinds of media and the person picked to start with. A request being edited
 * keeps its own kind even if the head has since stopped offering it.
 */
@Composable
private fun RequestEditor(
    initial: HouseholdRequest?,
    modules: List<RequestModule>,
    /** Who may be asked (never the member); null while loading. */
    assignees: List<Member>?,
    assigneesError: String?,
    saving: Boolean,
    /** The last save failure: said at the top, and the fields the server named marked. */
    errors: FormErrors,
    onDismiss: () -> Unit,
    onSave: (RequestEdit) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val askable = modules.filter { it.canAsk }
    val mediaModule = modules.firstOrNull { it.id == "media" }
    val maintenanceModule = modules.firstOrNull { it.id == "maintenance" }
    val mediaTypes = MEDIA_TYPES.filter { (t, _) ->
        mediaModule == null || t in mediaModule.mediaTypes || t == initial?.mediaType
    }
    /** What picking a kind fills in to start with: the only media type, the default person. */
    fun startingMediaType(c: String) =
        if (c == "media") mediaModule?.mediaTypes?.singleOrNull().orEmpty() else ""
    fun startingAssignee(c: String) =
        if (c == "maintenance") maintenanceModule?.defaultAssigneeId.orEmpty() else ""
    // Creating with only one kind to ask for: it's already picked.
    val firstCategory = initial?.category ?: askable.singleOrNull()?.id.orEmpty()

    var category by remember { mutableStateOf(firstCategory) }
    var mediaType by remember { mutableStateOf(initial?.mediaType ?: startingMediaType(firstCategory)) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var year by remember { mutableStateOf(initial?.year?.toString().orEmpty()) }
    var season by remember { mutableStateOf(initial?.season?.toString().orEmpty()) }
    // Media problems: where (TV: the whole show, or a season and maybe an episode) and what's wrong.
    var issue by remember { mutableStateOf(initial?.issue ?: false) }
    var wholeShow by remember { mutableStateOf(initial?.issue == true && initial.mediaType == "tv" && initial.season == null) }
    var episode by remember { mutableStateOf(initial?.episode?.toString().orEmpty()) }
    var problem by remember { mutableStateOf(if (initial?.issue == true) initial.details.orEmpty() else "") }
    var details by remember { mutableStateOf(initial?.details.orEmpty()) }
    var assigneeId by remember { mutableStateOf(initial?.assignee?.id ?: startingAssignee(firstCategory)) }
    val categoryModule = modules.firstOrNull { it.id == category }

    val tvProblem = issue && mediaType == "tv"
    val needsSeason = tvProblem && !wholeShow
    val yearValue = year.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1870..2100 }
    val seasonValue = if (tvProblem && wholeShow) null else season.toIntOrNull()
    val seasonValid = if (season.isBlank() || (tvProblem && wholeShow)) !needsSeason else seasonValue in 1..100
    val episodeValue = if (needsSeason) episode.toIntOrNull() else null
    val episodeValid = !needsSeason || episode.isBlank() || episodeValue in 1..1000
    val mediaReady = category == "media" && mediaType.isNotEmpty() && title.isNotBlank() && yearValue != null &&
        seasonValid && episodeValid && (!issue || problem.isNotBlank())
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
                    RequestEdit.Media(
                        mediaType,
                        title.trim(),
                        yearValue!!,
                        if (mediaType == "tv") seasonValue else null,
                        issue = issue,
                        episode = episodeValue,
                        details = if (issue) problem.trim() else null,
                    )
                } else {
                    RequestEdit.Maintenance(title.trim(), details.trim().ifEmpty { null }, assigneeId)
                },
            )
        },
        deleteLabel = "Remove request",
        onDelete = onDelete,
        error = errors.message,
    ) {
        // Why each is red: the server's word first, then what's checked here.
        val seasonError = errors["season"] ?: when {
            seasonValid || (needsSeason && season.isBlank()) -> null
            needsSeason -> "Enter a season from 1 to 100."
            else -> "Enter a season from 1 to 100, or leave it blank."
        }
        val episodeError = errors["episode"] ?: if (!episodeValid) "Enter an episode from 1 to 1000, or leave it blank." else null
        val yearError = errors["year"] ?: if (year.isNotEmpty() && yearValue == null) "Enter a 4-digit year from 1870 to 2100." else null
        if (initial == null) {
            DropdownField(
                "What would you like to request?",
                askable.map { it.id to it.label },
                category,
                {
                    category = it
                    errors.clear("category")
                    if (mediaType.isEmpty()) mediaType = startingMediaType(it)
                    if (assigneeId.isEmpty()) assigneeId = startingAssignee(it)
                },
                error = errors["category"],
            )
        } else {
            FieldLabel(categoryModule?.label ?: category.replaceFirstChar(Char::uppercase))
        }
        // The head's note for this kind (Settings → Request types).
        categoryModule?.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (category == "media") {
            val purposes = listOf("add" to "Something new added", "issue" to "A problem fixed with something we have")
            if (initial == null) {
                DropdownField("What do you need?", purposes, if (issue) "issue" else "add", { issue = it == "issue" })
            } else {
                FieldLabel(purposes.first { it.first == if (issue) "issue" else "add" }.second)
            }
            DropdownField("Type of media", mediaTypes, mediaType, { mediaType = it; errors.clear("mediaType") }, error = errors["mediaType"])
        }
        if (category == "media" && mediaType.isNotEmpty()) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; errors.clear("title") },
                label = { Text("Name") },
                placeholder = { Text(if (mediaType == "tv") "e.g. Severance" else "e.g. Dune") },
                singleLine = true,
                isError = errors["title"] != null,
                supportingText = errors["title"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            if (tvProblem) {
                CheckRow("It's the whole show, not one season", wholeShow) { wholeShow = it }
            }
            if (needsSeason) {
                OutlinedTextField(
                    value = season,
                    onValueChange = { season = it.filter(Char::isDigit).take(3); errors.clear("season") },
                    label = { Text("Season") },
                    placeholder = { Text("e.g. 2") },
                    supportingText = seasonError?.let { { Text(it) } },
                    singleLine = true,
                    isError = seasonError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = episode,
                    onValueChange = { episode = it.filter(Char::isDigit).take(4); errors.clear("episode") },
                    label = { Text("Episode (optional)") },
                    placeholder = { Text("Whole season") },
                    supportingText = { Text(episodeError ?: "Leave blank if it's the whole season.") },
                    singleLine = true,
                    isError = episodeError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (mediaType == "tv" && !tvProblem) {
                OutlinedTextField(
                    value = season,
                    onValueChange = { season = it.filter(Char::isDigit).take(3); errors.clear("season") },
                    label = { Text("Season (optional)") },
                    supportingText = { Text(seasonError ?: "Leave blank to request the whole show.") },
                    singleLine = true,
                    isError = seasonError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter(Char::isDigit).take(4); errors.clear("year") },
                label = { Text(yearLabel) },
                placeholder = { Text("YYYY") },
                singleLine = true,
                isError = yearError != null,
                supportingText = yearError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (issue) {
                OutlinedTextField(
                    value = problem,
                    onValueChange = { problem = it.take(2000); errors.clear("details") },
                    label = { Text("What's wrong?") },
                    placeholder = { Text("e.g. No sound after 20 minutes, or the subtitles are out of sync") },
                    minLines = 2,
                    isError = errors["details"] != null,
                    supportingText = errors["details"]?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (category == "maintenance") {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; errors.clear("title") },
                label = { Text("What needs doing?") },
                placeholder = { Text("e.g. Fix the leaky kitchen faucet") },
                singleLine = true,
                isError = errors["title"] != null,
                supportingText = errors["title"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = details,
                onValueChange = { details = it; errors.clear("details") },
                label = { Text("Details (optional)") },
                minLines = 2,
                isError = errors["details"] != null,
                supportingText = errors["details"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (assignees == null) {
                Text(
                    assigneesError?.let { "Couldn't load household members: $it" } ?: "Loading household members…",
                    color = if (assigneesError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (assignees.isEmpty()) {
                Text("There's no one you can ask yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                DropdownField(
                    "Who should do it?",
                    assignees.map { it.id to it.name },
                    assigneeId,
                    { assigneeId = it; errors.clear("assigneeId") },
                    placeholder = "Choose someone…",
                    error = errors["assigneeId"],
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
