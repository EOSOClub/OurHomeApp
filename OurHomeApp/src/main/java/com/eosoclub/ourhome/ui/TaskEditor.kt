package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.CategoryRef
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.RecurrenceInput
import com.eosoclub.ourhome.data.Task
import com.eosoclub.ourhome.data.TaskInput
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A checklist row in the editor; [id] is null for a step not yet saved.
 * [resetDays] is the raw "unchecks itself every N days" text ("" = never).
 */
data class StepEdit(val key: Int, val id: String?, val title: String, val resetDays: String = "")

data class TaskEdit(val input: TaskInput, val steps: List<StepEdit>)

/** Parsed reset cadence: null when blank, 0 when invalid (1–365 like the web). */
internal fun StepEdit.resetIntervalDays(): Int? =
    resetDays.trim().takeIf { it.isNotEmpty() }?.let { s -> s.toIntOrNull()?.takeIf { it in 1..365 } ?: 0 }

// Mirrors of the web's TASK_TYPES / TASK_TYPE_LABELS and TASK_PRIORITIES.
private val TASK_TYPES = listOf(
    "one_time" to "One-time",
    "recurring" to "Recurring",
    "maintenance" to "Maintenance",
    "inventory" to "Inventory",
)
private val TASK_PRIORITIES = listOf("low", "medium", "high", "urgent")
private val REPEAT_KINDS = listOf(
    "daily" to "Daily",
    "weekly" to "Weekly",
    "monthly" to "Monthly",
    "interval" to "Every N days",
)
private val WEEKDAY_LABELS = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")

private fun parseIntList(value: String?): List<Int> =
    value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

/**
 * The create/edit task form, with every field of the web's form. [task] null =
 * creating. [members] / [categories] are null while loading (or if unreadable).
 */
@Composable
internal fun TaskEditor(
    task: Task?,
    members: List<Member>?,
    categories: List<CategoryRef>?,
    saving: Boolean,
    /** False when the user may only delete this task, not change it. */
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskEdit) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val zone = ZoneId.systemDefault()
    val originalDue = task?.dueDate?.let { Instant.parse(it).atZone(zone) }
    val rule = task?.recurrence

    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var notes by remember { mutableStateOf(task?.notes.orEmpty()) }
    var type by remember { mutableStateOf(task?.type ?: "one_time") }
    var priority by remember { mutableStateOf(task?.priority ?: "medium") }
    var dueDate by remember { mutableStateOf(originalDue?.toLocalDate()) }
    var dueTime by remember { mutableStateOf(originalDue?.toLocalTime()) }
    var estimate by remember { mutableStateOf(task?.estimatedMinutes?.toString().orEmpty()) }
    var categoryId by remember { mutableStateOf(task?.category?.id.orEmpty()) }
    var assigneeId by remember { mutableStateOf(task?.assignee?.id.orEmpty()) }
    var repeatKind by remember { mutableStateOf(rule?.kind?.takeIf { k -> REPEAT_KINDS.any { it.first == k } } ?: "weekly") }
    var interval by remember { mutableStateOf((rule?.interval ?: 1).toString()) }
    var weekdays by remember { mutableStateOf(parseIntList(rule?.byWeekday).toSet()) }
    var monthdays by remember { mutableStateOf(parseIntList(rule?.byMonthday).toSet()) }
    var until by remember { mutableStateOf(rule?.until?.let(::localDate)) }
    var nextKey by remember { mutableStateOf(task?.subtasks?.size ?: 0) }
    val steps = remember {
        mutableStateListOf<StepEdit>().apply {
            task?.subtasks?.sortedBy { it.position }?.forEachIndexed { i, s ->
                add(StepEdit(i, s.id, s.title, s.resetIntervalDays?.toString().orEmpty()))
            }
        }
    }

    val recurring = type == "recurring"
    val estimateValue = estimate.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val estimateValid = estimate.isBlank() || estimateValue in 1..100_000
    val intervalValue = interval.trim().toIntOrNull()
    val intervalValid = !recurring || intervalValue in 1..365
    val stepsValid = steps.all { it.resetIntervalDays() != 0 }

    EditorDialog(
        title = when {
            task == null -> "New task"
            canSave -> "Edit task"
            else -> "Task"
        },
        saveLabel = if (task == null) "Add" else "Save",
        saving = saving,
        saveEnabled = canSave && title.isNotBlank() && estimateValid && intervalValid && stepsValid,
        onDismiss = onDismiss,
        onSave = {
            onSave(
                TaskEdit(
                    input = TaskInput(
                        title = title.trim(),
                        notes = notes.trim().ifEmpty { null },
                        type = type,
                        priority = priority,
                        // Like the web: a blank time means noon local.
                        dueDate = dueDate?.atTime(dueTime ?: LocalTime.NOON)?.atZone(zone)?.toInstant(),
                        estimatedMinutes = estimateValue,
                        categoryId = categoryId.ifEmpty { null },
                        assigneeId = assigneeId.ifEmpty { null },
                        recurrence = if (recurring) {
                            RecurrenceInput(
                                kind = repeatKind,
                                interval = intervalValue ?: 1,
                                byWeekday = weekdays.sorted(),
                                byMonthday = monthdays.sorted(),
                                until = until?.atTime(LocalTime.NOON)?.atZone(zone)?.toInstant(),
                            )
                        } else {
                            null
                        },
                    ),
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
            placeholder = { Text("e.g. Take the trash out") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            placeholder = { Text("Optional details…") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField("Type", TASK_TYPES, type, onSelect = { type = it })
        FieldLabel("Priority")
        ChoiceChips(TASK_PRIORITIES, priority) { priority = it }
        DateField("Due date", dueDate) { dueDate = it; if (it == null) dueTime = null }
        // The web disables the time until a date is picked; here it's hidden.
        if (dueDate != null) TimeField("Due time", dueTime) { dueTime = it }
        OutlinedTextField(
            value = estimate,
            onValueChange = { v -> estimate = v.filter(Char::isDigit) },
            label = { Text("Estimated effort (min)") },
            placeholder = { Text("e.g. 15") },
            singleLine = true,
            isError = !estimateValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        // Until the lists load, offer the task's current value so it still shows.
        DropdownField(
            "Assignee",
            listOf("" to "Unassigned") + (members?.map { it.id to it.name }
                ?: listOfNotNull(task?.assignee?.let { it.id to (it.name ?: "Member") })),
            assigneeId,
            onSelect = { assigneeId = it },
        )
        DropdownField(
            "Category",
            listOf("" to "None") + (categories?.map { it.id to it.name }
                ?: listOfNotNull(task?.category?.let { it.id to it.name })),
            categoryId,
            onSelect = { categoryId = it },
        )

        if (recurring) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField("Repeats", REPEAT_KINDS, repeatKind, onSelect = { repeatKind = it })
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { v -> interval = v.filter(Char::isDigit) },
                        label = {
                            Text(
                                when (repeatKind) {
                                    "weekly" -> "Every N weeks"
                                    "monthly" -> "Every N months"
                                    else -> "Interval (days)"
                                },
                            )
                        },
                        singleLine = true,
                        isError = !intervalValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (repeatKind == "weekly") {
                        FieldLabel("On days (optional)")
                        DayToggleRow(WEEKDAY_LABELS.indices.toList(), weekdays, { WEEKDAY_LABELS[it] }) { day ->
                            weekdays = if (day in weekdays) weekdays - day else weekdays + day
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { weekdays = setOf(1, 2, 3, 4, 5) }) { Text("Weekdays") }
                            OutlinedButton(onClick = { weekdays = setOf(0, 6) }) { Text("Weekend") }
                        }
                    }
                    if (repeatKind == "monthly") {
                        FieldLabel("On days of month (optional)")
                        (1..31).chunked(7).forEach { week ->
                            DayToggleRow(week, monthdays, { it.toString() }, columns = 7) { day ->
                                monthdays = if (day in monthdays) monthdays - day else monthdays + day
                            }
                        }
                    }
                    DateField("Ends on (optional)", until) { until = it }
                }
            }
        }

        FieldLabel(if (task == null) "Checklist (optional)" else "Checklist")
        steps.forEachIndexed { index, step ->
            ChecklistStepRow(
                step = step,
                index = index,
                isFirst = index == 0,
                isLast = index == steps.lastIndex,
                onChange = { steps[index] = it },
                onMove = { delta ->
                    val other = index + delta
                    steps[index] = steps[other].also { steps[other] = steps[index] }
                },
                onRemove = { steps.removeAt(index) },
            )
        }
        TextButton(onClick = { steps.add(StepEdit(nextKey, null, "")); nextKey++ }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add step")
        }
    }
}

@Composable
private fun ChecklistStepRow(
    step: StepEdit,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (StepEdit) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = step.title,
                    onValueChange = { onChange(step.copy(title = it)) },
                    placeholder = { Text("Step ${index + 1}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove step") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = step.resetDays,
                    onValueChange = { v -> onChange(step.copy(resetDays = v.filter(Char::isDigit))) },
                    label = { Text("Unchecks every N days") },
                    placeholder = { Text("Never") },
                    singleLine = true,
                    isError = step.resetIntervalDays() == 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move step up")
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move step down")
                }
            }
        }
    }
}
