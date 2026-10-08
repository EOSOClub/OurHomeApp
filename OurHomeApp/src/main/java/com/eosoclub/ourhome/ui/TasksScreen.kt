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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.PageAccess
import com.eosoclub.ourhome.data.Subtask
import com.eosoclub.ourhome.data.Task
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
        val saving: Boolean = false,
        // Null until loaded, or if this role can't list members.
        val members: List<Member>? = null,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.update {
            try {
                it.copy(tasks = api.tasks(), loading = false, error = null)
            } catch (e: Exception) {
                it.copy(loading = false, error = e.message)
            }
        }
    }

    fun complete(task: Task) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + task.id) }
        try {
            val updated = api.completeTask(task.id)
            // Recurring tasks come back pending with the next due date.
            state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == updated.id) updated else it }) }
            _messages.send(if (task.recurrence != null) "Done — next one scheduled" else "Completed “${task.title}”")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't complete task")
        } finally {
            state.update { it.copy(busy = it.busy - task.id) }
        }
    }

    fun startEdit(task: Task) {
        state.update { it.copy(editing = task) }
        if (state.value.members == null) viewModelScope.launch {
            runCatching { api.members() }.onSuccess { m -> state.update { it.copy(members = m) } }
        }
    }

    fun cancelEdit() = state.update { it.copy(editing = null) }

    /** Saves the task's fields, then applies checklist removals, renames and additions. */
    fun save(task: Task, edit: TaskEdit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            api.updateTask(task.id, edit.title, edit.notes, edit.priority, edit.dueDate, edit.assigneeId)
            val keptIds = edit.steps.mapNotNull { it.id }.toSet()
            task.subtasks.filter { it.id !in keptIds }.forEach { api.deleteSubtask(it.id) }
            val originalTitles = task.subtasks.associate { it.id to it.title }
            edit.steps.forEach { step ->
                val title = step.title.trim()
                when {
                    title.isEmpty() -> if (step.id != null) api.deleteSubtask(step.id)
                    step.id == null -> api.addSubtask(task.id, title)
                    originalTitles[step.id] != title -> api.renameSubtask(step.id, title)
                }
            }
            state.update { it.copy(editing = null) }
            _messages.send("Saved “${edit.title}”")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't save task")
        } finally {
            state.update { it.copy(saving = false) }
            refresh()
        }
    }

    fun delete(task: Task) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            api.deleteTask(task.id)
            state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == task.id }, editing = null) }
            _messages.send("Deleted “${task.title}”")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't delete task")
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
                replaceStep(task.id, step.id, step.done)
                _messages.send(e.message ?: "Couldn't update step")
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

    fun create(title: String, priority: String) = viewModelScope.launch {
        try {
            val task = api.createTask(title, priority)
            state.update { it.copy(tasks = it.tasks + task) }
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't add task")
        }
    }
}

private val OPEN_STATUSES = setOf("pending", "in_progress")
private val PRIORITIES = listOf("low", "medium", "high", "urgent")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    api: ApiClient,
    access: PageAccess,
    userId: String,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { TasksViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val expandedIds by vm.expanded.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    val open = state.tasks
        .filter { it.status in OPEN_STATUSES }
        .sortedWith(compareBy(nullsLast()) { it.dueDate?.let(Instant::parse) })

    Box(modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = state.loading && state.tasks.isNotEmpty(), onRefresh = { vm.refresh() }) {
            when {
                state.loading && state.tasks.isEmpty() -> Centered { CircularProgressIndicator() }
                state.error != null && state.tasks.isEmpty() -> Centered {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                }
                open.isEmpty() -> Centered { Text("Nothing to do. Nice.") }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(open, key = { it.id }) { task ->
                        TaskCard(
                            task,
                            busy = task.id in state.busy,
                            expanded = task.id in expandedIds,
                            onToggleExpanded = { vm.toggleExpanded(task.id) },
                            onComplete = { vm.complete(task) },
                            onToggleStep = { step -> vm.toggleStep(task, step) },
                            // The editor also holds Delete, so either opens it.
                            onEdit = if (access.canEdit(task.createdById, userId) || access.canDelete(task.createdById, userId)) {
                                { vm.startEdit(task) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        if (access.create) {
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Filled.Add, contentDescription = "Add task") }
        }
    }

    state.editing?.let { task ->
        TaskEditor(
            task = task,
            members = state.members,
            saving = state.saving,
            onDismiss = vm::cancelEdit,
            canSave = access.canEdit(task.createdById, userId),
            onSave = { edit -> vm.save(task, edit) },
            onDelete = if (access.canDelete(task.createdById, userId)) ({ vm.delete(task) }) else null,
        )
    }

    if (adding) {
        AddTaskDialog(
            onDismiss = { adding = false },
            onAdd = { title, priority -> vm.create(title, priority); adding = false },
        )
    }
}

@Composable
private fun TaskCard(
    task: Task,
    busy: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onComplete: () -> Unit,
    onToggleStep: (Subtask) -> Unit,
    onEdit: (() -> Unit)?,
) {
    val notes = task.notes?.takeIf { it.isNotBlank() }
    val expandable = task.subtasks.isNotEmpty() || notes != null || onEdit != null
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .clickable(enabled = expandable, onClick = onToggleExpanded)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                val due = task.dueDate?.let { dueLabel(it) }
                val meta = listOfNotNull(
                    due?.first,
                    task.priority.takeIf { it == "high" || it == "urgent" }?.replaceFirstChar(Char::uppercase),
                    task.recurrence?.let { "Repeats ${it.kind}" },
                    task.assignee?.name,
                    task.subtasks.takeIf { it.isNotEmpty() }?.let { s -> "${s.count { it.done }}/${s.size} steps" },
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
            if (expandable) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f).padding(horizontal = 4.dp),
                )
            }
            FilledTonalIconButton(onClick = onComplete, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Check, contentDescription = "Complete")
            }
        }
        if (expanded && expandable) {
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
                        Text(
                            step.title,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (step.done) TextDecoration.LineThrough else null,
                            color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (onEdit != null) {
                    TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Edit", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("medium") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRIORITIES.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(title.trim(), priority) }, enabled = title.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
