package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.Task
import java.time.Instant

/** A checklist row in the editor; [id] is null for a step not yet saved. */
data class StepEdit(val key: Int, val id: String?, val title: String)

data class TaskEdit(
    val title: String,
    val notes: String?,
    val priority: String,
    val dueDate: Instant?,
    val assigneeId: String?,
    val steps: List<StepEdit>,
)

private val TASK_PRIORITIES = listOf("low", "medium", "high", "urgent")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskEditor(
    task: Task,
    members: List<Member>?,
    saving: Boolean,
    /** False when the user may only delete this task, not change it. */
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskEdit) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(task.title) }
    var notes by remember { mutableStateOf(task.notes.orEmpty()) }
    var priority by remember { mutableStateOf(task.priority) }
    var due by remember { mutableStateOf(task.dueDate?.let(::localDate)) }
    var assigneeId by remember { mutableStateOf(task.assignee?.id) }
    var nextKey by remember { mutableStateOf(task.subtasks.size) }
    val steps = remember {
        mutableStateListOf<StepEdit>().apply {
            task.subtasks.sortedBy { it.position }.forEachIndexed { i, s -> add(StepEdit(i, s.id, s.title)) }
        }
    }

    EditorDialog(
        title = if (canSave) "Edit task" else "Task",
        saving = saving,
        saveEnabled = canSave && title.isNotBlank(),
        onDismiss = onDismiss,
        onSave = {
            onSave(
                TaskEdit(
                    title = title.trim(),
                    notes = notes.trim().ifEmpty { null },
                    priority = priority,
                    dueDate = dueInstant(due, task.dueDate),
                    assigneeId = assigneeId,
                    steps = steps.toList(),
                ),
            )
        },
        deleteLabel = "Delete task",
        onDelete = onDelete,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel("Priority")
        ChoiceChips(TASK_PRIORITIES, priority) { priority = it }
        DateField("Due date", due) { due = it }

        if (members != null) {
            var open by remember { mutableStateOf(false) }
            val selectedName = members.firstOrNull { it.id == assigneeId }?.name
                ?: task.assignee?.takeIf { it.id == assigneeId }?.name
                ?: "Unassigned"
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Assignee") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Unassigned") }, onClick = { assigneeId = null; open = false })
                    members.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = { assigneeId = m.id; open = false })
                    }
                }
            }
        }

        FieldLabel("Checklist")
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = step.title,
                    onValueChange = { steps[index] = step.copy(title = it) },
                    placeholder = { Text("Step ${index + 1}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { steps.removeAt(index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove step")
                }
            }
        }
        TextButton(
            onClick = { steps.add(StepEdit(nextKey, null, "")); nextKey++ },
            modifier = Modifier.padding(top = 0.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add step")
        }
    }
}
