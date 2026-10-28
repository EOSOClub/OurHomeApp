package com.eosoclub.ourhome.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.eosoclub.ourhome.data.LinkPreview
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.NamedRef
import com.eosoclub.ourhome.data.Permission
import com.eosoclub.ourhome.data.RequestModule
import com.eosoclub.ourhome.data.SessionUser
import com.eosoclub.ourhome.data.UserRef
import com.eosoclub.ourhome.data.can
import com.eosoclub.ourhome.data.decides
import com.eosoclub.ourhome.data.defaultRequestModules
import com.eosoclub.ourhome.data.isOpen
import com.eosoclub.ourhome.data.mediaStatus
import com.eosoclub.ourhome.data.productLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
// Purchase and Permission are yes or no (web DECISION_STATUS_LABELS), shown
// in this order above Maintenance and Media.
private val DECISION_KINDS = listOf("permission", "purchase")
private val DECISION_SECTIONS = listOf(
    "pending" to "Waiting for a decision",
    "completed" to "Approved",
    "declined" to "Declined",
)
private val DECISION_STATUS_LABELS = mapOf("pending" to "Waiting", "completed" to "Approved", "declined" to "Declined")

private val dayAndTime = DateTimeFormatter.ofPattern("EEE, MMM d")
private val timeOfDay = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "Fri, Oct 30, 6:00 PM" in this phone's time. */
private fun dateTimeLabel(iso: String): String {
    val at = Instant.parse(iso).atZone(ZoneId.systemDefault())
    return "${at.format(dayAndTime)}, ${at.format(timeOfDay)}"
}

/** A permission's from and until: "Fri, Oct 30, 6:00 PM until Sat, Oct 31, 11:00 AM". */
private fun whenLabel(r: HouseholdRequest): String? =
    r.startsAt?.let { from -> dateTimeLabel(from) + r.endsAt?.let { " until ${dateTimeLabel(it)}" }.orEmpty() }

/** A picked day and time as the instant to send; no time picked means noon, as in the app's other forms. */
private fun instantOf(date: LocalDate?, time: LocalTime?): Instant? =
    date?.atTime(time ?: LocalTime.NOON)?.atZone(ZoneId.systemDefault())?.toInstant()

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
    /** [price]: the estimate for one; [details]: why. */
    data class Purchase(
        val title: String,
        val details: String?,
        val url: String?,
        val imageUrl: String?,
        val price: Double?,
        val quantity: Int,
    ) : RequestEdit
    /** [endsAt] only with [startsAt]. */
    data class Permission(val title: String, val details: String?, val startsAt: Instant?, val endsAt: Instant?) : RequestEdit
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
        /** A purchase being approved: which shopping list to put it on. */
        val approving: HouseholdRequest? = null,
        val declining: HouseholdRequest? = null,
        /** A maintenance request the head is giving to someone else. */
        val reassigning: HouseholdRequest? = null,
        /** A request removed from its card (own declined, or the head's), waiting for "Remove?" to be confirmed. */
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
                is RequestEdit.Purchase -> api.savePurchaseRequest(
                    id, edit.title, edit.details, edit.url, edit.imageUrl, edit.price, edit.quantity,
                )
                is RequestEdit.Permission -> api.savePermissionRequest(id, edit.title, edit.details, edit.startsAt, edit.endsAt)
            }
            closeEditor()
            _messages.send(
                when {
                    id != null -> "Request updated"
                    saved.category == "maintenance" -> "Sent to ${saved.assignee?.name ?: "them"}"
                    saved.issue -> "Reported a problem with “${saved.title}”"
                    saved.category == "permission" -> "Asked permission for “${saved.title}”"
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

    /**
     * Maintenance: the assignee marks it done. Media: an approver marks it
     * added, or a problem fixed (one step). Purchase and permission: whoever
     * decides approves it; a purchase may go on shopping list [listId].
     */
    fun complete(r: HouseholdRequest, listId: String? = null) = runBusy(r, inDialog = state.value.approving != null) {
        val updated = api.completeRequest(r.id, listId)
        replace(updated)
        state.update { it.copy(approving = null) }
        _messages.send(
            when {
                r.issue -> "Marked “${r.title}” fixed"
                r.category == "media" -> "Added “${r.title}”"
                r.category == "purchase" || r.category == "permission" ->
                    "Approved “${r.title}”" + updated.addedToList?.let { " and added it to $it" }.orEmpty()
                else -> "Marked “${r.title}” done"
            },
        )
    }

    /** A purchase with lists to offer: ask which one first. */
    fun startApprove(r: HouseholdRequest) {
        form.clear()
        state.update { it.copy(approving = r) }
    }

    fun cancelApprove() {
        form.clear()
        state.update { it.copy(approving = null) }
    }

    /** "Fetch details" on a purchase. */
    suspend fun linkPreview(url: String): LinkPreview = api.requestLinkPreview(url)

    private val pictures = mutableMapOf<String, ImageBitmap>()

    /** A purchase's product picture, or null if it can't be loaded. */
    suspend fun picture(url: String): ImageBitmap? = pictures[url] ?: api.remoteImage(url)?.let { bytes ->
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }?.also { pictures[url] = it }

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

    fun startReassign(r: HouseholdRequest) {
        form.clear()
        state.update { it.copy(reassigning = r) }
        loadMembers()
    }

    fun cancelReassign() {
        form.clear()
        state.update { it.copy(reassigning = null) }
    }

    /** The head gives a maintenance request to [assigneeId]; it goes back to waiting for them. */
    fun reassign(r: HouseholdRequest, assigneeId: String) = runBusy(r, inDialog = true) {
        val updated = api.reassignRequest(r.id, assigneeId)
        replace(updated)
        state.update { it.copy(reassigning = null) }
        _messages.send("Sent to ${updated.assignee?.name ?: "them"}")
    }

    /**
     * Removing from the card (a declined request can't be edited, only removed;
     * the head removing someone else's maintenance request): asks first.
     */
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
    // The head may reassign or remove anyone's maintenance request.
    val isHead = user.role == "head"
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
            if (loadState(state.loading, state.error, state.requests.isEmpty(), empty, vm::refresh)) {
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
                DECISION_KINDS.forEach { kind ->
                    val module = modules.firstOrNull { it.id == kind } ?: return@forEach
                    val list = state.requests.filter { it.category == kind }
                    if (list.isEmpty()) return@forEach
                    categoryHeader(module.label)
                    DECISION_SECTIONS.forEach { (status, label) ->
                        // Anything still open (no status, or "accepted" from an older app) waits for a decision.
                        val items = list.filter { if (status == "pending") it.isOpen else it.status == status }
                        if (items.isEmpty()) return@forEach
                        sectionHeader("$kind-$status", label, items.size)
                        items(items, key = { it.id }) { r ->
                            val isOwn = r.requester.id == user.id
                            DecisionCard(
                                r = r,
                                me = user.id,
                                decides = module.decides(r.requester.id),
                                busy = r.id in state.busy,
                                loadPicture = vm::picture,
                                onApprove = {
                                    // A purchase can go on a shopping list: ask which. Otherwise approve at once.
                                    if (kind == "purchase" && module.shoppingLists.isNotEmpty()) vm.startApprove(r) else vm.complete(r)
                                },
                                onDecline = { vm.startDecline(r) },
                                // Once decided it can't be edited, only removed.
                                onEdit = if (isOwn && canWrite && r.isOpen) ({ vm.startEdit(r) }) else null,
                                onRemove = if (isOwn && canWrite && !r.isOpen) ({ vm.askRemove(r) }) else null,
                            )
                        }
                    }
                }
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
                            // On their own request the head has the requester's edit and remove.
                            val headActions = isHead && !isOwn
                            MaintenanceCard(
                                r = r,
                                me = user.id,
                                requireDueDate = maintenanceModule.requireDueDate,
                                canDecline = maintenanceModule.allowDecline,
                                busy = r.id in state.busy,
                                onAccept = { vm.startAccept(r) },
                                onComplete = { vm.complete(r) },
                                onDecline = { vm.startDecline(r) },
                                onReassign = if (headActions && (r.status == "pending" || r.status == "accepted")) {
                                    { vm.startReassign(r) }
                                } else {
                                    null
                                },
                                // A declined request can't be edited, only removed.
                                onEdit = if (isOwn && canWrite && !declined) ({ vm.startEdit(r) }) else null,
                                onRemove = if ((isOwn && canWrite && declined) || headActions) ({ vm.askRemove(r) }) else null,
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
            fetchPreview = vm::linkPreview,
            loadPicture = vm::picture,
        )
    }

    state.approving?.let { r ->
        ApproveDialog(
            r = r,
            lists = modules.firstOrNull { it.id == "purchase" }?.shoppingLists.orEmpty(),
            error = vm.form["listId"] ?: vm.form.message,
            busy = r.id in state.busy,
            onDismiss = vm::cancelApprove,
            onApprove = { listId -> vm.complete(r, listId) },
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

    state.reassigning?.let { r ->
        ReassignDialog(
            r = r,
            choices = state.members?.filter { it.id != r.requester.id },
            membersError = state.membersError,
            error = vm.form["assigneeId"] ?: vm.form.message,
            busy = r.id in state.busy,
            onDismiss = vm::cancelReassign,
            onReassign = { assigneeId -> vm.reassign(r, assigneeId) },
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
    /** The head, on someone else's open request. */
    onReassign: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    /** Own declined request (removing is all that's left), or the head on someone else's. */
    onRemove: (() -> Unit)?,
) {
    val isAssignee = r.assignee?.id == me
    val done = r.status == "completed"
    val declined = r.status == "declined"
    val assigneeActions = isAssignee && !done && !declined
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
            if (assigneeActions || onReassign != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    if (onReassign != null) {
                        TextButton(onClick = onReassign, enabled = !busy) { Text("Reassign") }
                    }
                    if (assigneeActions) {
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
}

/**
 * A purchase or permission request: what's asked, the decision once made, and
 * Approve / Decline for whoever decides it ([decides]: [RequestModule.decides]).
 */
@Composable
private fun DecisionCard(
    r: HouseholdRequest,
    me: String,
    decides: Boolean,
    busy: Boolean,
    loadPicture: suspend (String) -> ImageBitmap?,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onEdit: (() -> Unit)?,
    /** Own decided request: removing is all that's left. */
    onRemove: (() -> Unit)?,
) {
    val status = if (r.isOpen) "pending" else r.status.orEmpty()
    val open = status == "pending"
    // Highlight requests waiting on *you*.
    val colors = if (decides && open) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The picture "Fetch details" found, as on the web; nothing until it loads.
                r.imageUrl?.let { url ->
                    val picture by produceState<ImageBitmap?>(null, url) { value = loadPicture(url) }
                    picture?.let {
                        Image(
                            it,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(end = 12.dp).size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val many = (r.quantity ?: 1) > 1
                    Text(
                        if (many) "${r.quantity} × ${r.title}" else r.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (status == "declined") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                        SuggestionChip(onClick = {}, label = { Text(DECISION_STATUS_LABELS[status] ?: status) })
                        r.price?.let { p ->
                            Text(
                                // As on the web: $39.98, $389.00.
                                "$" + String.format(java.util.Locale.US, "%.2f", p) + if (many) " each" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                    whenLabel(r)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    r.url?.takeIf { it.isNotBlank() }?.let { ItemLink(productLink(it)) }
                    r.details?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (status) {
                        "completed" -> Text(
                            "Approved by ${who(r.decidedBy, me)}" + r.addedToList?.let { " · added to $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        "declined" -> Text(
                            "Declined" + r.decidedBy?.let { " by ${who(it, me)}" }.orEmpty() + r.reply?.let { ": $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Asked by ${if (r.requester.id == me) "you" else r.requester.name ?: "someone"} · ${formatDate(r.createdAt)}",
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
            if (decides && open) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    TextButton(onClick = onDecline, enabled = !busy) { Text("Decline") }
                    Button(onClick = onApprove, enabled = !busy) { Text(if (busy) "Saving…" else "Approve") }
                }
            }
        }
    }
}

/** Approving a purchase: put it on one of the shopping lists, or not. */
@Composable
private fun ApproveDialog(
    r: HouseholdRequest,
    lists: List<NamedRef>,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    /** null: approved without putting it on a list. */
    onApprove: (String?) -> Unit,
) {
    var listId by remember { mutableStateOf(lists.firstOrNull()?.id.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Approve purchase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“${r.title}” from ${r.requester.name ?: "someone"}")
                DropdownField(
                    "Add it to a shopping list",
                    lists.map { it.id to it.name } + ("" to "Don't add it to a list"),
                    listId,
                    { listId = it },
                    error = error,
                )
                Text(
                    "Its link, picture, price and how many go with it, so whoever shops has it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onApprove(listId.ifEmpty { null }) }) {
                Text(if (busy) "Saving…" else "Approve")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                                r.category == "purchase" -> "e.g. Let's wait for your birthday"
                                r.category == "permission" -> "e.g. Not on a school night"
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
 * The head gives a maintenance request to someone else: anyone but the
 * requester ([choices], null while the members load). Matches the web's
 * Reassign request window.
 */
@Composable
private fun ReassignDialog(
    r: HouseholdRequest,
    choices: List<Member>?,
    membersError: String?,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReassign: (String) -> Unit,
) {
    var assigneeId by remember { mutableStateOf(r.assignee?.id.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reassign request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“${r.title}” from ${r.requester.name ?: "someone"}")
                if (choices == null) {
                    Text(
                        membersError?.let { "Couldn't load household members: $it" } ?: "Loading household members…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (membersError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DropdownField(
                        "Who should do it?",
                        choices.map { it.id to it.name },
                        assigneeId,
                        { assigneeId = it },
                        placeholder = "Choose someone…",
                        error = error,
                    )
                    if (error == null) {
                        Text(
                            "It goes back to waiting, for them to accept with their own date.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && choices != null && assigneeId.isNotEmpty() && assigneeId != r.assignee?.id,
                onClick = { onReassign(assigneeId) },
            ) {
                Text(if (busy) "Saving…" else "Reassign")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Step-by-step form, matching the web: request type, then either media
 * (something new or a problem → type → name, season, year, and for a problem
 * the episode and what's wrong) or maintenance (what → details → who) or
 * purchase (what, link, price, how many, why) or permission (what, from,
 * until, details).
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
    /** Purchase: "Fetch details" and the picture it found. */
    fetchPreview: suspend (String) -> LinkPreview,
    loadPicture: suspend (String) -> ImageBitmap?,
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
    // Purchase: link (Fetch details fills the picture and price), price of one, how many.
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var imageUrl by remember { mutableStateOf(initial?.imageUrl) }
    var price by remember { mutableStateOf(initial?.price?.let(::priceText).orEmpty()) }
    var quantity by remember { mutableStateOf((initial?.quantity ?: 1).toString()) }
    // Permission: from and until, each a day and a time (no time: noon).
    val zone = ZoneId.systemDefault()
    var fromDate by remember { mutableStateOf(initial?.startsAt?.let { Instant.parse(it).atZone(zone).toLocalDate() }) }
    var fromTime by remember { mutableStateOf(initial?.startsAt?.let { Instant.parse(it).atZone(zone).toLocalTime() }) }
    var untilDate by remember { mutableStateOf(initial?.endsAt?.let { Instant.parse(it).atZone(zone).toLocalDate() }) }
    var untilTime by remember { mutableStateOf(initial?.endsAt?.let { Instant.parse(it).atZone(zone).toLocalTime() }) }
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
    val priceValue = priceOrNull(price)
    val priceValid = price.isBlank() || priceValue != null
    val quantityValue = quantity.toIntOrNull()?.takeIf { it in 1..999 }
    val purchaseReady = category == "purchase" && title.isNotBlank() && priceValid && quantityValue != null
    val startsAt = instantOf(fromDate, fromTime)
    val endsAt = instantOf(untilDate, untilTime)
    val untilError = errors["endsAt"] ?: when {
        endsAt != null && startsAt == null -> "Say when it starts too."
        endsAt != null && startsAt != null && !endsAt.isAfter(startsAt) -> "Until has to be after from."
        else -> null
    }
    val permissionReady = category == "permission" && title.isNotBlank() && untilError == null
    val yearLabel = when {
        mediaType != "tv" -> "Release year"
        seasonValue != null && seasonValid -> "Year season $seasonValue came out"
        else -> "Year of first season"
    }

    EditorDialog(
        title = if (initial == null) "New request" else "Edit request",
        saving = saving,
        saveEnabled = mediaReady || maintenanceReady || purchaseReady || permissionReady,
        onDismiss = onDismiss,
        onSave = {
            onSave(
                when (category) {
                    "media" -> RequestEdit.Media(
                        mediaType,
                        title.trim(),
                        yearValue!!,
                        if (mediaType == "tv") seasonValue else null,
                        issue = issue,
                        episode = episodeValue,
                        details = if (issue) problem.trim() else null,
                    )
                    "purchase" -> {
                        val link = url.trim().ifEmpty { null }
                        RequestEdit.Purchase(
                            title.trim(),
                            details.trim().ifEmpty { null },
                            link,
                            // A picture goes with its link.
                            if (link != null) imageUrl else null,
                            priceValue,
                            quantityValue!!,
                        )
                    }
                    "permission" -> RequestEdit.Permission(
                        title.trim(),
                        details.trim().ifEmpty { null },
                        startsAt,
                        if (startsAt != null) endsAt else null,
                    )
                    else -> RequestEdit.Maintenance(title.trim(), details.trim().ifEmpty { null }, assigneeId)
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

        if (category == "purchase") {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; errors.clear("title") },
                label = { Text("What would you like?") },
                placeholder = { Text("e.g. LEGO Space set") },
                singleLine = true,
                isError = errors["title"] != null,
                supportingText = errors["title"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            ProductLinkField(
                url = url,
                imageUrl = imageUrl,
                error = errors["url"] ?: errors["imageUrl"],
                onUrlChange = { url = it; imageUrl = null; errors.clear("url") },
                fetchPreview = fetchPreview,
                onPreview = { p ->
                    p.imageUrl?.let { imageUrl = it }
                    if (title.isBlank()) p.title?.let { title = it }
                    if (price.isBlank()) p.price?.let { price = priceText(it) }
                },
                loadPicture = loadPicture,
                label = "Link (optional)",
            )
            val priceError = errors["price"] ?: if (!priceValid) "Enter a price of 0 or more." else null
            OutlinedTextField(
                value = price,
                onValueChange = { price = it; errors.clear("price") },
                label = { Text("Est. price (optional)") },
                placeholder = { Text("0.00") },
                supportingText = { Text(priceError ?: "For one.") },
                isError = priceError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            val quantityError = errors["quantity"] ?: if (quantityValue == null) "Enter a number from 1 to 999." else null
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it.filter(Char::isDigit).take(3); errors.clear("quantity") },
                label = { Text("How many") },
                supportingText = quantityError?.let { { Text(it) } },
                isError = quantityError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = details,
                onValueChange = { details = it.take(2000); errors.clear("details") },
                label = { Text("Why (optional)") },
                placeholder = { Text("What it's for, anything that helps decide") },
                minLines = 2,
                isError = errors["details"] != null,
                supportingText = errors["details"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (category == "permission") {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; errors.clear("title") },
                label = { Text("What would you like to do?") },
                placeholder = { Text("e.g. Sleepover at Sam's") },
                singleLine = true,
                isError = errors["title"] != null,
                supportingText = errors["title"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            DateField("From (optional)", fromDate, error = errors["startsAt"]) { fromDate = it; errors.clear("startsAt") }
            if (fromDate != null) {
                TimeField("Time", fromTime) { fromTime = it }
            } else {
                Text(
                    "Leave it empty if there's no time, like a new app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DateField("Until (optional)", untilDate, error = untilError) { untilDate = it; errors.clear("endsAt") }
            if (untilDate != null) {
                TimeField("Time", untilTime) { untilTime = it }
            }
            OutlinedTextField(
                value = details,
                onValueChange = { details = it.take(2000); errors.clear("details") },
                label = { Text("Details (optional)") },
                placeholder = { Text("Where, who with, how you'll get there") },
                minLines = 2,
                isError = errors["details"] != null,
                supportingText = errors["details"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
