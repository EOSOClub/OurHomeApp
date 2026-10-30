package com.eosoclub.ourhome.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.eosoclub.ourhome.data.PlaceGroups
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import com.eosoclub.ourhome.data.Places
import com.eosoclub.ourhome.data.groupByPlace
import com.eosoclub.ourhome.data.moveId
import com.eosoclub.ourhome.data.placesLabel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.CategoryRef
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.PageAccess
import com.eosoclub.ourhome.data.DEFAULT_MINUTES_PER_POINT
import com.eosoclub.ourhome.data.Features
import com.eosoclub.ourhome.data.Subtask
import com.eosoclub.ourhome.data.Task
import com.eosoclub.ourhome.data.TaskCompletion
import com.eosoclub.ourhome.data.centi
import com.eosoclub.ourhome.data.formatPoints
import androidx.compose.ui.graphics.ImageBitmap
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.CheckText
import com.eosoclub.ourhome.data.CheckCode
import com.eosoclub.ourhome.data.checkCode
import com.eosoclub.ourhome.data.takeBackId
import com.eosoclub.ourhome.data.headHint
import androidx.compose.foundation.layout.FlowRow
import com.eosoclub.ourhome.data.TaskSubmission
import com.eosoclub.ourhome.data.canSetPhotoProof
import com.eosoclub.ourhome.data.sendFailure
import com.eosoclub.ourhome.data.undoOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class TasksViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val tasks: List<Task> = emptyList(),
        /** The household's minutes per point, for a new task's points (null until loaded). */
        val minutesPerPoint: Double? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val busy: Set<String> = emptySet(),
        val editing: Task? = null,
        /** The editor is open for a new task. */
        val creating: Boolean = false,
        val saving: Boolean = false,
        // Null until loaded (the editor then shows only the task's current value).
        val members: List<Member>? = null,
        val categories: List<CategoryRef>? = null,
        /** Completion history per expanded task (loaded on demand). */
        val history: Map<String, List<TaskCompletion>> = emptyMap(),
        /** Floors and rooms; null until loaded (or from an older server). */
        val places: Places? = null,
        /** To check (children I look after now) and my own checks; empty from a server without checks. */
        val checks: List<TaskSubmission> = emptyList(),
        /** The task the "Send for a check" dialog is open for. */
        val sending: Task? = null,
        val sendBusy: Boolean = false,
        /** Why the last send failed (said in the dialog). */
        val sendError: String? = null,
        /** Checks being approved, sent back or taken back (ids). */
        val deciding: Set<String> = emptySet(),
        /** The To check item the Send back dialog is open for. */
        val sendingBack: TaskSubmission? = null,
        /** Opened To check items (ids). */
        val openChecks: Set<String> = emptySet(),
    )

    /** A snackbar message, optionally with one action (e.g. Undo). */
    data class Message(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<Message>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private suspend fun say(text: String) = _messages.send(Message(text))

    /** False when the server admin turned Points off: messages leave points out. */
    var showPoints = true

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        launch { loadChecks() }
        // Rooms change rarely; a failure (older server) keeps the last.
        launch { runCatching { api.places() }.onSuccess { p -> state.update { it.copy(places = p) } } }
        // Every member may read the rate; a failure keeps the one the tasks carry.
        launch { runCatching { api.pointsSettings() }.onSuccess { p -> state.update { it.copy(minutesPerPoint = p.minutesPerPoint) } } }
        state.load({ api.tasks() }, { s, v -> s.copy(tasks = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
    }

    /** "By room" instead of by due date; kept here so it survives switching tabs. */
    val byRoom = MutableStateFlow(false)

    /** Folded floor/room headings ("floor:<id>", "room:<id>", "house"). */
    val collapsed = MutableStateFlow(emptySet<String>())

    fun toggleCollapsed(key: String) = collapsed.update { if (key in it) it - key else it + key }

    /** A room a scanned location tag asked for; the list shows it once, then clears this. */
    val focusRoom = MutableStateFlow<String?>(null)

    /** Switches to "By room" and asks the list to show [roomId] (refreshed first). */
    fun openRoom(roomId: String) {
        byRoom.value = true
        focusRoom.value = roomId
        refresh()
    }

    // In-flight reorders: while any are pending, a reply must not undo a later move.
    private var pendingReorders = 0

    /** Hand-set order of one place's tasks: moves at once, reverts if the server refuses. */
    fun reorder(taskIds: List<String>) {
        val before = state.value.tasks
        val rank = taskIds.withIndex().associate { (i, id) -> id to i }
        state.update { s -> s.copy(tasks = s.tasks.map { t -> rank[t.id]?.let { t.copy(position = it) } ?: t }) }
        pendingReorders++
        viewModelScope.launch {
            try {
                api.reorderTasks(taskIds)
            } catch (e: Exception) {
                if (pendingReorders == 1) state.update { it.copy(tasks = before) }
                say(e.message ?: "Couldn't reorder")
            } finally {
                pendingReorders--
            }
        }
    }

    /**
     * Completes the task (paying its points) and offers Undo for the 10-minute
     * window. When the server says this task goes through a check for me
     * (`review.checkForMe`), the send dialog opens instead. If that changed
     * since the list loaded, the server refuses with "check_needed" and the
     * dialog opens then.
     */
    fun complete(task: Task) {
        if (task.review.checkForMe) {
            openSend(task)
            return
        }
        completeNow(task)
    }

    private fun completeNow(task: Task) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + task.id) }
        try {
            val updated = api.completeTask(task.id)
            // Recurring tasks come back pending with the next due date (or done
            // for this cycle); one-time tasks come back completed.
            replaceTask(updated)
            val pts = completedPointsSuffix(task.points, showPoints)
            val text = when {
                updated.doneThisCycle -> "Done for this cycle$pts"
                task.recurrence != null && updated.status != "completed" ->
                    updated.opensAt?.let { "Done - next one opens ${shortDate(it)}$pts" } ?: "Done - next one scheduled$pts"
                else -> "Completed “${task.title}”$pts"
            }
            val completionId = updated.completionId
            _messages.send(Message(text, if (completionId != null) "Undo" else null, completionId?.let { id -> { undo(id) } }))
        } catch (e: Exception) {
            val code = e.checkCode
            if (code == CheckCode.NEEDED) {
                // Checks were turned on for this child: fetch the task as it is now, then send it.
                reloadAndSend(task.id)
            } else if (code in CheckCode.TASK_CHANGED) {
                // It waits for an OK now (another send won, or it was already waiting): show it as it is.
                say(e.message ?: "Couldn't complete task")
                refresh()
            } else {
                say(e.message ?: "Couldn't complete task")
            }
        } finally {
            state.update { it.copy(busy = it.busy - task.id) }
        }
    }

    /**
     * Undo a completion: the task is restored and its points voided. One paid
     * by an approved check goes back to To check while the child still needs
     * one ([fromCheck]: the Undo after Approve, which says so in its words).
     */
    fun undo(completionId: String, fromCheck: Boolean = false) = viewModelScope.launch {
        try {
            val updated = api.undoCompletion(completionId)
            replaceTask(updated)
            state.update { it.copy(history = emptyMap()) }
            say(
                when {
                    updated.undo == "back_to_check" -> undoOutcome(updated.undo)
                    !showPoints -> "Completion undone"
                    fromCheck -> undoOutcome(updated.undo)
                    else -> "Undone; the points were taken back"
                },
            )
            if (fromCheck || updated.undo == "back_to_check") loadChecks()
        } catch (e: Exception) {
            say(e.message ?: "Couldn't undo")
        }
    }

    // --- Checks (Task photo proof) --------------------------------------------

    /** The photo for the open send dialog: in memory only, kept when a send fails. */
    val checkPhoto = MutableStateFlow<ByteArray?>(null)

    /** To check and my own checks; a failure (an older server) keeps the last list. */
    private suspend fun loadChecks() {
        try {
            val list = api.taskSubmissions()
            state.update { it.copy(checks = list) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** "This task now needs a check.": the task as it is now, then the send dialog. */
    private suspend fun reloadAndSend(taskId: String) {
        try {
            val tasks = api.tasks()
            state.update { it.copy(tasks = tasks) }
            tasks.firstOrNull { it.id == taskId }?.takeUnless { it.waitingForOk }?.let(::openSend)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say(e.message ?: "Couldn't load tasks")
        }
    }

    fun openSend(task: Task) {
        checkPhoto.value = null
        state.update { it.copy(sending = task, sendError = null) }
    }

    fun cancelSend() {
        checkPhoto.value = null
        state.update { it.copy(sending = null, sendError = null) }
    }

    fun setCheckPhoto(bytes: ByteArray?) {
        checkPhoto.value = bytes
        state.update { it.copy(sendError = null) }
    }

    /** Sends the open dialog's task for a check; Undo takes it back. */
    fun sendForCheck(note: String) {
        val task = state.value.sending ?: return
        if (state.value.sendBusy) return
        val photo = checkPhoto.value
        state.update { it.copy(sendBusy = true, sendError = null) }
        viewModelScope.launch {
            try {
                val updated = api.completeTask(task.id, forCheck = true, note = note, photo = photo)
                replaceTask(updated)
                checkPhoto.value = null
                state.update { it.copy(sending = null, sendBusy = false) }
                loadChecks()
                val id = updated.submissionId
                _messages.send(Message(CheckText.SENT, if (id != null) "Undo" else null, id?.let { s -> { takeBack(s) } }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = sendFailure(e, photo != null)
                if (failure.dropPhoto) checkPhoto.value = null
                if (failure.close) {
                    state.update { it.copy(sending = null, sendBusy = false) }
                    say(failure.message)
                    refresh()
                } else {
                    state.update { it.copy(sendBusy = false, sendError = failure.message) }
                }
            }
        }
    }

    /** Takes back my own waiting check: the task goes back to how it was. */
    fun takeBack(submissionId: String) {
        if (submissionId in state.value.deciding) return
        state.update { it.copy(deciding = it.deciding + submissionId) }
        viewModelScope.launch {
            try {
                replaceTask(api.withdrawSubmission(submissionId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "Couldn't take it back")
            } finally {
                state.update { it.copy(deciding = it.deciding - submissionId) }
                loadChecks()
            }
        }
    }

    fun toggleCheck(id: String) {
        val opening = id !in state.value.openChecks
        state.update { it.copy(openChecks = if (opening) it.openChecks + id else it.openChecks - id) }
        if (opening) state.value.checks.firstOrNull { it.id == id }?.takeIf { it.hasPhoto }?.let { loadPhoto(it.id) }
    }

    /**
     * Check photos, loaded only when their item is opened and kept in memory
     * only (never on disk), a few at a time. A failure is kept as one, so the
     * item says so instead of spinning.
     */
    val photos = MutableStateFlow<Map<String, Result<ImageBitmap?>>>(emptyMap())

    private fun loadPhoto(id: String) {
        if (photos.value[id]?.isSuccess == true) return
        photos.update { it - id }
        viewModelScope.launch {
            val result = try {
                Result.success(api.submissionPhoto(id)?.let { bytes -> withContext(Dispatchers.Default) { decodePhoto(bytes, 1600) } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            photos.update { (it - id + (id to result)).entries.toList().takeLast(PHOTO_CACHE_SIZE).associate { e -> e.key to e.value } }
        }
    }

    /** Approves a check: points paid (Undo for the adult who approved it, 10 minutes). */
    fun approve(item: TaskSubmission) {
        if (item.id in state.value.deciding) return
        state.update { it.copy(deciding = it.deciding + item.id) }
        viewModelScope.launch {
            try {
                val updated = api.decideSubmission(item.id, approve = true)
                replaceTask(updated)
                state.update { it.copy(history = emptyMap(), openChecks = it.openChecks - item.id) }
                val completionId = updated.completionId
                _messages.send(
                    Message(
                        CheckText.approved(item.task.title, item.child.name),
                        if (completionId != null) "Undo" else null,
                        completionId?.let { id -> { undo(id, fromCheck = true) } },
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "Couldn't approve it")
            } finally {
                state.update { it.copy(deciding = it.deciding - item.id) }
                photos.update { it - item.id }
                loadChecks()
            }
        }
    }

    /** Why the open Send back dialog's last try failed. */
    val sendBackForm = FormErrors()

    fun startSendBack(item: TaskSubmission) {
        sendBackForm.clear()
        state.update { it.copy(sendingBack = item) }
    }

    fun cancelSendBack() {
        sendBackForm.clear()
        state.update { it.copy(sendingBack = null) }
    }

    /** Sends a check back (or, counted as done at its day's end, takes the points back). */
    fun sendBack(item: TaskSubmission, note: String, redoStepIds: List<String>) {
        if (item.id in state.value.deciding) return
        sendBackForm.clear()
        state.update { it.copy(deciding = it.deciding + item.id) }
        viewModelScope.launch {
            try {
                replaceTask(api.decideSubmission(item.id, approve = false, reviewNote = note, redoStepIds = redoStepIds))
                state.update { it.copy(sendingBack = null, history = emptyMap(), openChecks = it.openChecks - item.id) }
                photos.update { it - item.id }
                say(
                    if (item.countedAtCycleEnd) CheckText.tookBack(item.task.title, item.child.name)
                    else CheckText.sentBack(item.task.title, item.child.name),
                )
                loadChecks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && e.status == 422) {
                    // A note or step the server named: fix it in the dialog.
                    sendBackForm.fail(e, "Couldn't send it back")
                } else {
                    state.update { it.copy(sendingBack = null) }
                    say(e.message ?: "Couldn't send it back")
                    loadChecks()
                }
            } finally {
                state.update { it.copy(deciding = it.deciding - item.id) }
            }
        }
    }

    /** Loads (or reloads) a task's completion history. */
    fun loadHistory(taskId: String) = viewModelScope.launch {
        runCatching { api.taskCompletions(taskId) }
            .onSuccess { list -> state.update { it.copy(history = it.history + (taskId to list)) } }
    }

    private fun replaceTask(updated: Task) =
        state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == updated.id) updated else it }) }

    /** The open editor's last save failure: why, and which fields to mark. */
    val form = FormErrors()

    fun startEdit(task: Task) {
        form.clear()
        state.update { it.copy(editing = task) }
        loadLookups()
    }

    fun startCreate() {
        form.clear()
        state.update { it.copy(creating = true) }
        loadLookups()
    }

    /** Assignee and category choices; household members are readable by anyone who may create tasks. */
    private fun loadLookups() {
        if (state.value.members == null) viewModelScope.launch {
            runCatching { api.householdMembers() }.onSuccess { m -> state.update { it.copy(members = m) } }
        }
        if (state.value.categories == null) viewModelScope.launch {
            runCatching { api.categories("task") }.onSuccess { c -> state.update { it.copy(categories = c) } }
        }
    }

    fun cancelEdit() {
        form.clear()
        state.update { it.copy(editing = null, creating = false) }
    }

    /** Creates the task with its whole checklist (time/points included) in one call. */
    fun create(edit: TaskEdit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            val task = api.createTask(edit.input, edit.steps)
            state.update { it.copy(tasks = it.tasks + task, creating = false) }
            say("Added “${task.title}”")
        } catch (e: Exception) {
            // Shown in the editor, which sits over the snackbar.
            form.fail(e, "Couldn't add task")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    /** Saves the task and its whole checklist in one call (the server replaces the checklist). */
    fun save(task: Task, edit: TaskEdit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            replaceTask(api.updateTask(task.id, edit.input, edit.steps))
            state.update { it.copy(editing = null) }
            say("Saved “${edit.input.title}”")
        } catch (e: Exception) {
            form.fail(e, "Couldn't save task")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    fun delete(task: Task) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            api.deleteTask(task.id)
            state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == task.id }, editing = null) }
            say("Deleted “${task.title}”")
        } catch (e: Exception) {
            form.fail(e, "Couldn't delete task")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    /** Ids of expanded cards; kept here so they survive switching tabs. */
    val expanded = MutableStateFlow(emptySet<String>())

    fun toggleExpanded(taskId: String) =
        expanded.update { if (taskId in it) it - taskId else it + taskId }

    // In-flight step toggles per task. While any are pending, a server response
    // (which may predate a later tap) must not overwrite the optimistic state.
    private val pendingSteps = mutableMapOf<String, Int>()

    /** Optimistically ticks one checklist step; reverts if the server refuses. */
    fun toggleStep(task: Task, step: Subtask) {
        val target = !step.done
        replaceStep(task.id, step.id, target)
        pendingSteps[task.id] = (pendingSteps[task.id] ?: 0) + 1
        viewModelScope.launch {
            try {
                val updated = api.setSubtaskDone(step.id, target)
                if ((pendingSteps[task.id] ?: 1) <= 1) {
                    state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == updated.id) updated else it }) }
                }            } catch (e: Exception) {
                // Undo this tap only if nothing tapped the step again since.
                val now = state.value.tasks.firstOrNull { it.id == task.id }?.subtasks?.firstOrNull { it.id == step.id }
                if (now?.done == target) replaceStep(task.id, step.id, !target)
                say(e.message ?: "Couldn't update step")
            } finally {
                pendingSteps[task.id] = (pendingSteps[task.id] ?: 1) - 1
            }
        }
    }

    private fun replaceStep(taskId: String, stepId: String, done: Boolean) = state.update { s ->
        s.copy(tasks = s.tasks.map { t ->
            if (t.id != taskId) t
            else t.copy(subtasks = t.subtasks.map { if (it.id == stepId) it.copy(done = done) else it })
        })
    }
}

/** Open tasks; one waiting for an adult's OK is still open (with its waiting state). */
private val OPEN_STATUSES = setOf("pending", "in_progress", "awaiting_review")

/** How many check photos stay in memory at once. */
private const val PHOTO_CACHE_SIZE = 4

@Composable
fun TasksScreen(
    api: ApiClient,
    access: PageAccess,
    userId: String,
    /** With Points off, points are hidden (the server still records them). */
    features: Features,
    showMessage: (String) -> Unit,
    /** A snackbar with one action (Undo). */
    showAction: (String, String, () -> Unit) -> Unit,
    /** null when the server admin turned Points off. */
    onOpenPoints: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** The signed-in member's role, and whether an adult looks after them (null: not known yet). */
    role: String? = null,
    supervised: Boolean? = null,
) {
    val vm = viewModel { TasksViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val checkPhoto by vm.checkPhoto.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    var enlarged by remember { mutableStateOf<ImageBitmap?>(null) }
    // To check: what this adult may decide (the list also holds the viewer's own checks).
    val toCheck = state.checks.filter { it.canDecide }
    val expandedIds by vm.expanded.collectAsStateWithLifecycle()
    val byRoomChosen by vm.byRoom.collectAsStateWithLifecycle()
    val collapsed by vm.collapsed.collectAsStateWithLifecycle()
    val places = state.places?.takeUnless { it.isEmpty }
    val byRoom = byRoomChosen && places != null
    val focusRoom by vm.focusRoom.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val showPoints = features.points
    SideEffect { vm.showPoints = showPoints }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            if (m.actionLabel != null && m.action != null) showAction(m.text, m.actionLabel, m.action) else showMessage(m.text)
        }
    }

    val open = state.tasks
        .filter { it.status in OPEN_STATUSES }
        .sortedWith(compareBy(nullsLast()) { it.dueDate?.let(Instant::parse) })
    // Cycle tasks finished early wait here until their next cycle starts.
    val doneThisCycle = state.tasks.filter { it.doneThisCycle }.sortedBy { it.cycleEndsAt }

    // A scanned location tag: unfold its room (and floor) and scroll to it once
    // the tasks and rooms are in.
    LaunchedEffect(focusRoom, state.loading, places) {
        val roomId = focusRoom ?: return@LaunchedEffect
        if (state.loading || places == null) return@LaunchedEffect
        val floorId = places.rooms.firstOrNull { it.id == roomId }?.floorId?.takeIf { f -> places.floors.any { it.id == f } }
        val unfolded = collapsed - "room:$roomId" - "floor:${floorId ?: "none"}"
        vm.collapsed.value = unfolded
        // Rows above the groups: the Points / sort row (always there with rooms),
        // then To check (its heading and one row per item) when there is any.
        val toCheckRows = if (toCheck.isEmpty()) 0 else toCheck.size + 1
        val index = roomRowIndex(groupByPlace(open, places), unfolded, roomId, leading = 1 + toCheckRows)
        val name = places.roomLabel(roomId) ?: "that room"
        if (index != null) listState.animateScrollToItem(index) else showMessage("Nothing to do in $name right now.")
        vm.focusRoom.value = null
    }
    // [group] = the place's tasks in order, for Move up/down (By room only).
    val cardFor: @Composable (Task, List<Task>?) -> Unit = { task, group ->
        val i = group?.indexOfFirst { it.id == task.id } ?: -1
        TaskCard(
            task,
            // By room the heading says where; a task in several places still lists them all.
            showPlace = !byRoom || (task.places?.size ?: 0) > 1,
            showPoints = showPoints,
            // Ordering is the household's list: "Edit others'" on Tasks, as on the web.
            onMove = if (group != null && group.size > 1 && access.editOthers) {
                { direction -> vm.reorder(moveId(group.map { it.id }, task.id, direction)) }
            } else {
                null
            },
            canMoveUp = i > 0,
            canMoveDown = group != null && i in 0 until group.size - 1,
            busy = task.id in state.busy,
            expanded = task.id in expandedIds,
            history = state.history[task.id],
            onToggleExpanded = {
                if (task.id !in expandedIds) vm.loadHistory(task.id)
                vm.toggleExpanded(task.id)
            },
            onComplete = { vm.complete(task) },
            onToggleStep = { step -> vm.toggleStep(task, step) },
            onUndo = { id -> vm.undo(id) },
            userId = userId,
            // My own waiting check: Take back, by the id the task carries.
            onTakeBack = takeBackId(task, userId)?.let { id -> { vm.takeBack(id) } },
            takingBack = takeBackId(task, userId) in state.deciding,
            // The head decides only for children they look after: say how to.
            headHint = headHint(task, role, userId),
            // The editor also holds Delete, so either opens it.
            onEdit = if (access.canEdit(task.createdById, userId) || access.canDelete(task.createdById, userId)) {
                { vm.startEdit(task) }
            } else {
                null
            },
        )
    }

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.loading && state.tasks.isNotEmpty(), onRefresh = { vm.refresh() }) {
            when {
                state.loading && state.tasks.isEmpty() -> Centered { CircularProgressIndicator() }
                state.error != null && state.tasks.isEmpty() -> Centered {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (onOpenPoints != null || places != null) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                if (onOpenPoints != null) {
                                    TextButton(onClick = onOpenPoints) {
                                        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Points", modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                // Rooms are set up in Settings → Rooms & floors.
                                if (places != null) {
                                    FilterChip(selected = !byRoom, onClick = { vm.byRoom.value = false }, label = { Text("Due date") })
                                    Spacer(Modifier.size(8.dp))
                                    FilterChip(selected = byRoom, onClick = { vm.byRoom.value = true }, label = { Text("By room") })
                                }
                            }
                        }
                    }
                    // To check, for the adults who look after the children who sent them.
                    if (toCheck.isNotEmpty()) {
                        item(key = "to-check") { ToCheckHeading() }
                        items(toCheck, key = { "check/${it.id}" }) { item ->
                            CheckCard(
                                item = item,
                                showPoints = showPoints,
                                expanded = item.id in state.openChecks,
                                photo = photos[item.id],
                                busy = item.id in state.deciding,
                                onToggle = { vm.toggleCheck(item.id) },
                                onApprove = { vm.approve(item) },
                                onSendBack = { vm.startSendBack(item) },
                                onEnlarge = { enlarged = it },
                            )
                        }
                    }
                    if (open.isEmpty()) {
                        item { Text("Nothing to do. Nice.", modifier = Modifier.padding(16.dp)) }
                    }
                    if (byRoom && places != null) {
                        val groups = groupByPlace(open, places)
                        // A task in several places shows under each, so a row's key
                        // includes its group (LazyColumn keys must be unique).
                        fun LazyListScope.placeTasks(group: String, list: List<Task>) =
                            items(list, key = { "$group/${it.id}" }) { cardFor(it, list) }
                        groups.floors.forEach { g ->
                            val key = "floor:${g.floor?.id ?: "none"}"
                            // Each task once, even when it's in several rooms on this floor.
                            val count = (g.tasks + g.rooms.flatMap { it.tasks }).distinctBy { it.id }.size
                            item(key = key) {
                                PlaceHeading(g.floor?.name ?: "Other rooms", count, floor = true,
                                    open = key !in collapsed, onToggle = { vm.toggleCollapsed(key) })
                            }
                            if (key !in collapsed) {
                                placeTasks(key, g.tasks)
                                g.rooms.forEach { r ->
                                    val roomKey = "room:${r.room.id}"
                                    item(key = roomKey) {
                                        PlaceHeading(r.room.name, r.tasks.size, floor = false,
                                            open = roomKey !in collapsed, onToggle = { vm.toggleCollapsed(roomKey) })
                                    }
                                    if (roomKey !in collapsed) placeTasks(roomKey, r.tasks)
                                }
                            }
                        }
                        if (groups.house.isNotEmpty()) {
                            item(key = "house") {
                                PlaceHeading("Whole house", groups.house.size, floor = true,
                                    open = "house" !in collapsed, onToggle = { vm.toggleCollapsed("house") })
                            }
                            if ("house" !in collapsed) placeTasks("house", groups.house)
                        }
                    } else {
                        items(open, key = { it.id }) { cardFor(it, null) }
                    }
                    if (doneThisCycle.isNotEmpty()) {
                        item {
                            Text(
                                "Done this cycle",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            )
                        }
                        items(doneThisCycle, key = { it.id }) { cardFor(it, null) }
                    }
                }
            }
        }
        if (access.create) {
            FloatingActionButton(
                onClick = vm::startCreate,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Add task") }
        }
    }

    state.editing?.let { task ->
        TaskEditor(
            task = task,
            members = state.members,
            categories = state.categories,
            places = state.places,
            saving = state.saving,
            errors = vm.form,
            onDismiss = vm::cancelEdit,
            canSave = access.canEdit(task.createdById, userId),
            showPoints = showPoints,
            onSave = { edit -> vm.save(task, edit) },
            onDelete = if (access.canDelete(task.createdById, userId)) ({ vm.delete(task) }) else null,
            canSetPhotoProof = canSetPhotoProof(role, supervised),
            sentByMe = task.review.submittedBy?.id == userId,
        )
    }

    if (state.creating) {
        TaskEditor(
            task = null,
            members = state.members,
            categories = state.categories,
            places = state.places,
            saving = state.saving,
            errors = vm.form,
            onDismiss = vm::cancelEdit,
            canSave = true,
            showPoints = showPoints,
            // The household rate (Settings → Task points): asked for on load, else the one every task
            // carries; the default only before either arrives (no tasks yet used to mean 10 here).
            minutesPerPoint = state.minutesPerPoint ?: state.tasks.firstOrNull()?.minutesPerPoint ?: DEFAULT_MINUTES_PER_POINT,
            onSave = vm::create,
            onDelete = null,
            canSetPhotoProof = canSetPhotoProof(role, supervised),
        )
    }

    state.sending?.let { task ->
        SendCheckDialog(
            task = task,
            photo = checkPhoto,
            busy = state.sendBusy,
            error = state.sendError,
            onPhoto = vm::setCheckPhoto,
            onSend = vm::sendForCheck,
            onDismiss = vm::cancelSend,
        )
    }

    state.sendingBack?.let { item ->
        SendBackDialog(
            item = item,
            showPoints = showPoints,
            busy = item.id in state.deciding,
            errors = vm.sendBackForm,
            onSend = { note, redo -> vm.sendBack(item, note, redo) },
            onDismiss = vm::cancelSendBack,
        )
    }

    enlarged?.let { PhotoViewer(it) { enlarged = null } }
}

/**
 * The points at the end of the "Completed" toast: " · 5 pts" (nothing at 0 or
 * with Points off). "pts" even for 1, like the task card, step rows and history.
 */
internal fun completedPointsSuffix(points: Double, showPoints: Boolean): String =
    if (showPoints && points > 0) " · ${formatPoints(points.centi())} pts" else ""

/**
 * The LazyColumn index of [roomId]'s heading in the "By room" list, or null
 * when the room has nothing open. Mirrors the list below: [leading] rows, then
 * per floor its heading, whole-floor tasks and rooms (heading + tasks), each
 * folded one contributing only its heading.
 */
internal fun roomRowIndex(groups: PlaceGroups<Task>, collapsed: Set<String>, roomId: String, leading: Int): Int? {
    var index = leading
    for (g in groups.floors) {
        index++ // floor heading
        if ("floor:${g.floor?.id ?: "none"}" in collapsed) {
            if (g.rooms.any { it.room.id == roomId }) return null
            continue
        }
        index += g.tasks.size
        for (r in g.rooms) {
            if (r.room.id == roomId) return index
            index++ // room heading
            if ("room:${r.room.id}" !in collapsed) index += r.tasks.size
        }
    }
    return null
}

/** A floor or room heading in the "By room" list; tap to fold it away. */
@Composable
private fun PlaceHeading(title: String, count: Int, floor: Boolean, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = if (floor) 4.dp else 16.dp, top = if (floor) 12.dp else 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Fold" else "Unfold",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).rotate(if (open) 0f else -90f),
        )
        Text(
            "$title ($count)",
            style = if (floor) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            color = if (floor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TaskCard(
    task: Task,
    /** Off under a room heading, which already says where. */
    showPlace: Boolean,
    /** Off when the server admin turned Points off. */
    showPoints: Boolean,
    /** Up (-1) / down (+1) within its place; null when it can't be reordered. */
    onMove: ((Int) -> Unit)?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    busy: Boolean,
    expanded: Boolean,
    history: List<TaskCompletion>?,
    onToggleExpanded: () -> Unit,
    onComplete: () -> Unit,
    onToggleStep: (Subtask) -> Unit,
    onUndo: (String) -> Unit,
    onEdit: (() -> Unit)?,
    userId: String,
    /** Takes back my own waiting check; null when it isn't mine (or not known). */
    onTakeBack: (() -> Unit)?,
    takingBack: Boolean,
    /** For the head on a check they can't decide: how to become able to. */
    headHint: String?,
) {
    val notes = task.notes?.takeIf { it.isNotBlank() }
    // Sent for a check: open, but nothing changes until an adult decides (or it's taken back).
    val waiting = task.waitingForOk
    // Done for this occurrence: the next one can't be completed before it
    // opens. The clock moves on then, so the button comes back without a refresh.
    var now by remember { mutableStateOf(Instant.now()) }
    val waitsUntil = task.waitsUntil(now)
    LaunchedEffect(waitsUntil) {
        if (waitsUntil == null) return@LaunchedEffect
        delay((waitsUntil.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0))
        now = Instant.now()
    }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .clickable(onClick = onToggleExpanded)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                val due = task.dueDate?.let { dueLabel(it) }.takeUnless { task.doneThisCycle }
                val meta = listOfNotNull(
                    waitsUntil?.let { "Done · next opens ${shortDate(it.toString())}" },
                    if (task.doneThisCycle) "Reopens ${task.cycleEndsAt?.let { shortDate(it) }}" else due?.first,
                    task.points.takeIf { showPoints && it > 0 }?.let {
                        if (waiting) CheckText.waitingPoints(it) else "${formatPoints(it.centi())} pts"
                    },
                    task.priority.takeIf { it == "high" || it == "urgent" }?.replaceFirstChar(Char::uppercase),
                    task.recurrence?.let { if (it.rollover) "Cycles ${it.kind}" else "Repeats ${it.kind}" },
                    task.assignee?.name?.let { if (task.rotation.isNotEmpty()) "$it's turn" else it },
                    task.nextAssignee?.name?.let { "next $it" },
                    task.subtasks.takeIf { it.isNotEmpty() }?.let { s -> "${s.count { it.done }}/${s.size} steps" },
                    task.placesLabel()?.takeIf { showPlace },
                    task.category?.name,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (due?.second == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (waiting) {
                    // Wraps: on a narrow card "Riley sent this for a check" goes under the chip
                    // instead of being squeezed beside it.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(CheckText.WAITING)
                        val sender = task.review.submittedBy
                        if (sender != null && sender.id != userId) {
                            Text(
                                CheckText.sentBy(sender.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (headHint != null) {
                        Text(headHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onTakeBack != null) {
                        TextButton(onClick = onTakeBack, enabled = !takingBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(CheckText.TAKE_BACK)
                        }
                    }
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f).padding(horizontal = 4.dp),
            )
            FilledTonalIconButton(onClick = onComplete, enabled = !busy && !task.doneThisCycle && waitsUntil == null && !waiting) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Check, contentDescription = if (waitsUntil != null) "Done for now" else "Complete")
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 4.dp, end = 16.dp, bottom = 12.dp)) {
                notes?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                }
                task.subtasks.sortedBy { it.position }.forEach { step ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // While the task waits for an OK its steps are locked (the server refuses a tick).
                            .then(if (waiting) Modifier else Modifier.clickable { onToggleStep(step) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = step.done, onCheckedChange = { onToggleStep(step) }, enabled = !waiting)
                        Column(Modifier.weight(1f)) {
                            Text(
                                step.title,
                                style = MaterialTheme.typography.bodyLarge,
                                textDecoration = if (step.done) TextDecoration.LineThrough else null,
                                color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            // Who did it, and whether its points wait for the task.
                            // With Points off, just the name.
                            val who = step.queuedFor?.let { if (showPoints) "Queued for ${it.name ?: "someone"}" else it.name ?: "someone" }
                                ?: step.doneBy?.name?.takeIf { step.done }
                            if (who != null) {
                                Text(who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // Ticked again by a child someone looks after: its points wait for an OK.
                            if (step.done && step.waitingForOk) {
                                StatusChip(CheckText.WAITING, Modifier.padding(top = 2.dp))
                            }
                        }
                        if (showPoints && step.points > 0) {
                            // A gap from the title at any width (a long title wraps in its own
                            // column instead of running into the points), and the points on one line.
                            Text(
                                "${formatPoints(step.points.centi())} pts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                            )
                        }
                    }
                }
                HistoryList(history, showPoints, onUndo)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (onMove != null) {
                        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                        }
                    }
                    if (onEdit != null) {
                        TextButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Edit", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Recent completions (and missed cycles), with Undo where the server allows it. */
@Composable
private fun HistoryList(history: List<TaskCompletion>?, showPoints: Boolean, onUndo: (String) -> Unit) {
    if (history.isNullOrEmpty()) return
    Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
        Text("History", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        history.take(5).forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val what = when {
                    c.outcome == "missed" -> "Missed (cycle ran out)"
                    else -> listOfNotNull(c.user?.name, c.points.takeIf { showPoints && it > 0 }?.let { "${formatPoints(it.centi())} pts" })
                        .joinToString(" · ")
                }
                Text(
                    "${relativeTime(c.completedAt)} · $what" + if (c.undoneAt != null) " · undone" else "",
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = if (c.undoneAt != null) TextDecoration.LineThrough else null,
                    color = if (c.outcome == "missed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (c.canUndo) TextButton(onClick = { onUndo(c.id) }) { Text("Undo") }
            }
        }
    }
}

private fun shortDate(iso: String): String =
    Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d"))
