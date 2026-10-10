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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import com.eosoclub.ourhome.data.Places
import com.eosoclub.ourhome.data.groupByPlace
import com.eosoclub.ourhome.data.moveId
import com.eosoclub.ourhome.data.placeLabel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.eosoclub.ourhome.data.Subtask
import com.eosoclub.ourhome.data.Task
import com.eosoclub.ourhome.data.TaskCompletion
import com.eosoclub.ourhome.data.centi
import com.eosoclub.ourhome.data.formatPoints
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class TasksViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val tasks: List<Task> = emptyList(),
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
    )

    /** A snackbar message, optionally with one action (e.g. Undo). */
    data class Message(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<Message>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private suspend fun say(text: String) = _messages.send(Message(text))

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        // Rooms change rarely and only on the website; a failure (older server) keeps the last.
        launch { runCatching { api.places() }.onSuccess { p -> state.update { it.copy(places = p) } } }
        state.load({ api.tasks() }, { s, v -> s.copy(tasks = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
    }

    /** "By room" instead of by due date; kept here so it survives switching tabs. */
    val byRoom = MutableStateFlow(false)

    /** Folded floor/room headings ("floor:<id>", "room:<id>", "house"). */
    val collapsed = MutableStateFlow(emptySet<String>())

    fun toggleCollapsed(key: String) = collapsed.update { if (key in it) it - key else it + key }

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

    /** Completes the task (paying its points) and offers Undo for the 10-minute window. */
    fun complete(task: Task) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + task.id) }
        try {
            val updated = api.completeTask(task.id)
            // Recurring tasks come back pending with the next due date (or done
            // for this cycle); one-time tasks come back completed.
            replaceTask(updated)
            val pts = if (task.points > 0) " · ${formatPoints(task.points.centi())} pts" else ""
            val text = when {
                updated.doneThisCycle -> "Done for this cycle$pts"
                task.recurrence != null && updated.status != "completed" -> "Done — next one scheduled$pts"
                else -> "Completed “${task.title}”$pts"
            }
            val completionId = updated.completionId
            _messages.send(Message(text, if (completionId != null) "Undo" else null, completionId?.let { id -> { undo(id) } }))
        } catch (e: Exception) {
            say(e.message ?: "Couldn't complete task")
        } finally {
            state.update { it.copy(busy = it.busy - task.id) }
        }
    }

    /** Undo a completion: the task is restored and its points voided. */
    fun undo(completionId: String) = viewModelScope.launch {
        try {
            replaceTask(api.undoCompletion(completionId))
            state.update { it.copy(history = emptyMap()) }
            say("Undone; the points were taken back")
        } catch (e: Exception) {
            say(e.message ?: "Couldn't undo")
        }
    }

    /** Loads (or reloads) a task's completion history. */
    fun loadHistory(taskId: String) = viewModelScope.launch {
        runCatching { api.taskCompletions(taskId) }
            .onSuccess { list -> state.update { it.copy(history = it.history + (taskId to list)) } }
    }

    private fun replaceTask(updated: Task) =
        state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == updated.id) updated else it }) }

    fun startEdit(task: Task) {
        state.update { it.copy(editing = task) }
        loadLookups()
    }

    fun startCreate() {
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

    fun cancelEdit() = state.update { it.copy(editing = null, creating = false) }

    /** Creates the task with its whole checklist (time/points included) in one call. */
    fun create(edit: TaskEdit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            val task = api.createTask(edit.input, edit.steps)
            state.update { it.copy(tasks = it.tasks + task, creating = false) }
            say("Added “${task.title}”")
        } catch (e: Exception) {
            say(e.message ?: "Couldn't add task")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    /** Saves the task and its whole checklist in one call (the server replaces the checklist). */
    fun save(task: Task, edit: TaskEdit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            replaceTask(api.updateTask(task.id, edit.input, edit.steps))
            state.update { it.copy(editing = null) }
            say("Saved “${edit.input.title}”")
        } catch (e: Exception) {
            say(e.message ?: "Couldn't save task")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    fun delete(task: Task) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            api.deleteTask(task.id)
            state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == task.id }, editing = null) }
            say("Deleted “${task.title}”")
        } catch (e: Exception) {
            say(e.message ?: "Couldn't delete task")
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
                }
            } catch (e: Exception) {
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

private val OPEN_STATUSES = setOf("pending", "in_progress")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    api: ApiClient,
    access: PageAccess,
    userId: String,
    showMessage: (String) -> Unit,
    /** A snackbar with one action (Undo). */
    showAction: (String, String, () -> Unit) -> Unit,
    /** null when the server admin turned Points off. */
    onOpenPoints: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { TasksViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val expandedIds by vm.expanded.collectAsStateWithLifecycle()
    val byRoomChosen by vm.byRoom.collectAsStateWithLifecycle()
    val collapsed by vm.collapsed.collectAsStateWithLifecycle()
    val places = state.places?.takeUnless { it.isEmpty }
    val byRoom = byRoomChosen && places != null

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
    // [group] = the place's tasks in order, for Move up/down (By room only).
    val cardFor: @Composable (Task, List<Task>?) -> Unit = { task, group ->
        val i = group?.indexOfFirst { it.id == task.id } ?: -1
        TaskCard(
            task,
            showPlace = !byRoom,
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
                                // Rooms are set up on the website (Settings → Rooms & floors).
                                if (places != null) {
                                    FilterChip(selected = !byRoom, onClick = { vm.byRoom.value = false }, label = { Text("Due date") })
                                    Spacer(Modifier.size(8.dp))
                                    FilterChip(selected = byRoom, onClick = { vm.byRoom.value = true }, label = { Text("By room") })
                                }
                            }
                        }
                    }
                    if (open.isEmpty()) {
                        item { Text("Nothing to do. Nice.", modifier = Modifier.padding(16.dp)) }
                    }
                    if (byRoom && places != null) {
                        val groups = groupByPlace(open, places)
                        fun LazyListScope.placeTasks(list: List<Task>) =
                            items(list, key = { it.id }) { cardFor(it, list) }
                        groups.floors.forEach { g ->
                            val key = "floor:${g.floor?.id ?: "none"}"
                            item(key = key) {
                                PlaceHeading(g.floor?.name ?: "Other rooms", g.tasks.size + g.rooms.sumOf { it.tasks.size }, floor = true,
                                    open = key !in collapsed, onToggle = { vm.toggleCollapsed(key) })
                            }
                            if (key !in collapsed) {
                                placeTasks(g.tasks)
                                g.rooms.forEach { r ->
                                    val roomKey = "room:${r.room.id}"
                                    item(key = roomKey) {
                                        PlaceHeading(r.room.name, r.tasks.size, floor = false,
                                            open = roomKey !in collapsed, onToggle = { vm.toggleCollapsed(roomKey) })
                                    }
                                    if (roomKey !in collapsed) placeTasks(r.tasks)
                                }
                            }
                        }
                        if (groups.house.isNotEmpty()) {
                            item(key = "house") {
                                PlaceHeading("Whole house", groups.house.size, floor = true,
                                    open = "house" !in collapsed, onToggle = { vm.toggleCollapsed("house") })
                            }
                            if ("house" !in collapsed) placeTasks(groups.house)
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
            onDismiss = vm::cancelEdit,
            canSave = access.canEdit(task.createdById, userId),
            onSave = { edit -> vm.save(task, edit) },
            onDelete = if (access.canDelete(task.createdById, userId)) ({ vm.delete(task) }) else null,
        )
    }

    if (state.creating) {
        TaskEditor(
            task = null,
            members = state.members,
            categories = state.categories,
            places = state.places,
            saving = state.saving,
            onDismiss = vm::cancelEdit,
            canSave = true,
            // The household rate comes with every task; the default before any load.
            minutesPerPoint = state.tasks.firstOrNull()?.minutesPerPoint ?: DEFAULT_MINUTES_PER_POINT,
            onSave = vm::create,
            onDelete = null,
        )
    }
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
) {
    val notes = task.notes?.takeIf { it.isNotBlank() }
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
                    if (task.doneThisCycle) "Reopens ${task.cycleEndsAt?.let { shortDate(it) }}" else due?.first,
                    task.points.takeIf { it > 0 }?.let { "${formatPoints(it.centi())} pts" },
                    task.priority.takeIf { it == "high" || it == "urgent" }?.replaceFirstChar(Char::uppercase),
                    task.recurrence?.let { if (it.rollover) "Cycles ${it.kind}" else "Repeats ${it.kind}" },
                    task.assignee?.name?.let { if (task.rotation.isNotEmpty()) "$it's turn" else it },
                    task.nextAssignee?.name?.let { "next $it" },
                    task.subtasks.takeIf { it.isNotEmpty() }?.let { s -> "${s.count { it.done }}/${s.size} steps" },
                    task.placeLabel()?.takeIf { showPlace },
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
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f).padding(horizontal = 4.dp),
            )
            FilledTonalIconButton(onClick = onComplete, enabled = !busy && !task.doneThisCycle) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Check, contentDescription = "Complete")
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
                            .clickable { onToggleStep(step) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = step.done, onCheckedChange = { onToggleStep(step) })
                        Column(Modifier.weight(1f)) {
                            Text(
                                step.title,
                                style = MaterialTheme.typography.bodyLarge,
                                textDecoration = if (step.done) TextDecoration.LineThrough else null,
                                color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            // Who did it, and whether its points wait for the task.
                            val who = step.queuedFor?.let { "Queued for ${it.name ?: "someone"}" }
                                ?: step.doneBy?.name?.takeIf { step.done }
                            if (who != null) {
                                Text(who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (step.points > 0) {
                            Text(
                                "${formatPoints(step.points.centi())} pts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }
                HistoryList(history, onUndo)
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
private fun HistoryList(history: List<TaskCompletion>?, onUndo: (String) -> Unit) {
    if (history.isNullOrEmpty()) return
    Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
        Text("History", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        history.take(5).forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val what = when {
                    c.outcome == "missed" -> "Missed (cycle ran out)"
                    else -> listOfNotNull(c.user?.name, c.points.takeIf { it > 0 }?.let { "${formatPoints(it.centi())} pts" })
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
