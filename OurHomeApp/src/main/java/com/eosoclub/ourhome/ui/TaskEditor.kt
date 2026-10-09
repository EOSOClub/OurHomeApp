package com.eosoclub.ourhome.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.CategoryRef
import com.eosoclub.ourhome.data.DEFAULT_MINUTES_PER_POINT
import com.eosoclub.ourhome.data.EditResult
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.PointsState
import com.eosoclub.ourhome.data.RecurrenceInput
import com.eosoclub.ourhome.data.StepInput
import com.eosoclub.ourhome.data.Task
import com.eosoclub.ourhome.data.TaskBase
import com.eosoclub.ourhome.data.TaskInput
import com.eosoclub.ourhome.data.addStep
import com.eosoclub.ourhome.data.asPoints
import com.eosoclub.ourhome.data.customisedCount
import com.eosoclub.ourhome.data.formatPoints
import com.eosoclub.ourhome.data.liveTotals
import com.eosoclub.ourhome.data.removeStep
import com.eosoclub.ourhome.data.setStepFollow
import com.eosoclub.ourhome.data.setStepMinutes
import com.eosoclub.ourhome.data.setStepPoints
import com.eosoclub.ourhome.data.setTaskFollow
import com.eosoclub.ourhome.data.setTaskMinutes
import com.eosoclub.ourhome.data.setTaskPoints
import com.eosoclub.ourhome.data.toCenti
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A checklist row in the editor; [id] is null for a step not yet saved.
 * [resetDays] is the raw "unchecks itself every N days" text ("" = never).
 * Its time/points live in the editor's [PointsState] at the same index.
 */
data class StepEdit(val key: Int, val id: String?, val title: String, val resetDays: String = "")

data class TaskEdit(val input: TaskInput, val steps: List<StepInput>)

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

/** Amber outline for values set by hand (the web marks them the same way). */
private val CustomColor = Color(0xFFF59E0B)

private fun parseIntList(value: String?): List<Int> =
    value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

/**
 * The create/edit task form, with every field of the web's form. [task] null =
 * creating. [members] / [categories] are null while loading (or if unreadable).
 * Time to complete and points follow TaskPoints.kt (the web's taskPoints.ts).
 */
@Composable
internal fun TaskEditor(
    task: Task?,
    members: List<Member>?,
    categories: List<CategoryRef>?,
    saving: Boolean,
    /** False when the user may only delete this task, not change it. */
    canSave: Boolean,
    /** Household rate (from any loaded task; the default before that). */
    minutesPerPoint: Double = task?.minutesPerPoint ?: DEFAULT_MINUTES_PER_POINT,
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
    var categoryId by remember { mutableStateOf(task?.category?.id.orEmpty()) }
    var assigneeId by remember { mutableStateOf(task?.assignee?.id.orEmpty()) }
    // Rotating assignees in turn order (web lib/taskRotation.ts).
    var rotation by remember { mutableStateOf(task?.rotation?.map { it.id }.orEmpty()) }
    var repeatKind by remember { mutableStateOf(rule?.kind?.takeIf { k -> REPEAT_KINDS.any { it.first == k } } ?: "weekly") }
    var interval by remember { mutableStateOf((rule?.interval ?: 1).toString()) }
    var weekdays by remember { mutableStateOf(parseIntList(rule?.byWeekday).toSet()) }
    var monthdays by remember { mutableStateOf(parseIntList(rule?.byMonthday).toSet()) }
    var until by remember { mutableStateOf(rule?.until?.let(::localDate)) }
    var rollover by remember { mutableStateOf(rule?.rollover ?: false) }
    var cycleWeekdays by remember { mutableStateOf(parseIntList(rule?.cycleWeekdays).toSet()) }
    var cycleMonthdays by remember { mutableStateOf(parseIntList(rule?.cycleMonthdays).toSet()) }
    var nextKey by remember { mutableStateOf(task?.subtasks?.size ?: 0) }
    // Titles/reset days per step, and their time/points at the same index.
    val steps = remember {
        mutableStateListOf<StepEdit>().apply {
            task?.subtasks?.sortedBy { it.position }?.forEachIndexed { i, s ->
                add(StepEdit(i, s.id, s.title, s.resetIntervalDays?.toString().orEmpty()))
            }
        }
    }
    var points by remember { mutableStateOf(task?.pointsState() ?: PointsState(TaskBase(null, null, true), emptyList())) }
    // A total edit that would overwrite customised steps, waiting for "Rescale".
    var pendingTotal by remember { mutableStateOf<Pair<String, (Boolean) -> EditResult>?>(null) }

    val recurring = type == "recurring"
    // Turns only move on when a recurring task does, so rotation needs it.
    val rotating = recurring && rotation.size >= 2
    val currentTurn = if (rotating && assigneeId !in rotation) rotation.first() else assigneeId
    // Names for the rotation, even before the member list loads.
    val memberNames = members?.associate { it.id to it.name }
        ?: task?.rotation?.associate { it.id to (it.name ?: "Member") }.orEmpty()
    val intervalValue = interval.trim().toIntOrNull()
    val intervalValid = !recurring || intervalValue in 1..365
    val stepsValid = steps.all { it.resetIntervalDays() != 0 }
    val cycleValid = !recurring || !rollover ||
        (repeatKind != "weekly" || cycleWeekdays.isNotEmpty()) && (repeatKind != "monthly" || cycleMonthdays.isNotEmpty())

    EditorDialog(
        title = when {
            task == null -> "New task"
            canSave -> "Edit task"
            else -> "Task"
        },
        saveLabel = if (task == null) "Add" else "Save",
        saving = saving,
        saveEnabled = canSave && title.isNotBlank() && intervalValid && stepsValid && cycleValid,
        onDismiss = onDismiss,
        onSave = {
            val base = points.task
            onSave(
                TaskEdit(
                    input = TaskInput(
                        title = title.trim(),
                        notes = notes.trim().ifEmpty { null },
                        type = type,
                        priority = priority,
                        // Like the web: a blank time means noon local.
                        dueDate = dueDate?.atTime(dueTime ?: LocalTime.NOON)?.atZone(zone)?.toInstant(),
                        estimatedMinutes = base.baseMinutes?.takeIf { it > 0 },
                        points = if (base.pointsFollowTime) null else base.basePointsCenti?.asPoints(),
                        pointsFollowTime = base.pointsFollowTime,
                        categoryId = categoryId.ifEmpty { null },
                        assigneeId = currentTurn.ifEmpty { null },
                        rotationUserIds = if (rotating) rotation else emptyList(),
                        recurrence = if (recurring) {
                            RecurrenceInput(
                                kind = repeatKind,
                                interval = intervalValue ?: 1,
                                byWeekday = weekdays.sorted(),
                                byMonthday = monthdays.sorted(),
                                until = until?.atTime(LocalTime.NOON)?.atZone(zone)?.toInstant(),
                                rollover = rollover,
                                cycleWeekdays = cycleWeekdays.sorted(),
                                cycleMonthdays = cycleMonthdays.sorted(),
                            )
                        } else {
                            null
                        },
                    ),
                    steps = steps.mapIndexedNotNull { i, s ->
                        s.title.trim().takeIf { it.isNotEmpty() }?.let {
                            StepInput(s.id, it, s.resetIntervalDays(), points.steps[i])
                        }
                    },
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
        // Until the lists load, offer the task's current value so it still shows.
        if (rotating) {
            DropdownField(
                "Whose turn now",
                rotation.map { it to (memberNames[it] ?: "Member") },
                currentTurn,
                onSelect = { assigneeId = it },
            )
        } else {
            DropdownField(
                "Assignee",
                listOf("" to "Unassigned") + (members?.map { it.id to it.name }
                    ?: listOfNotNull(task?.assignee?.let { it.id to (it.name ?: "Member") })),
                assigneeId,
                onSelect = { assigneeId = it },
            )
        }
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
                    RotationSection(
                        members = members ?: task?.rotation?.map { Member(it.id, it.name ?: "Member") }.orEmpty(),
                        rotation = rotation,
                        rollover = rollover,
                        onToggle = { id -> rotation = if (id in rotation) rotation - id else rotation + id },
                        onClear = { rotation = emptyList() },
                    )
                    CycleSection(
                        repeatKind = repeatKind,
                        interval = intervalValue ?: 1,
                        rollover = rollover,
                        onRollover = { rollover = it },
                        weekdays = cycleWeekdays,
                        onWeekday = { d -> cycleWeekdays = if (d in cycleWeekdays) cycleWeekdays - d else cycleWeekdays + d },
                        monthdays = cycleMonthdays,
                        onMonthday = { d -> cycleMonthdays = if (d in cycleMonthdays) cycleMonthdays - d else cycleMonthdays + d },
                        valid = cycleValid,
                    )
                }
            }
        }

        PointsSection(
            points = points,
            minutesPerPoint = minutesPerPoint,
            onChange = { points = it },
            onTotalEdit = { what, run ->
                val r = run(false)
                if (r.needsConfirm) pendingTotal = what to run else points = r.state
            },
        )

        FieldLabel(if (task == null) "Checklist (optional)" else "Checklist")
        steps.forEachIndexed { index, step ->
            ChecklistStepRow(
                step = step,
                values = points.steps[index],
                index = index,
                isFirst = index == 0,
                isLast = index == steps.lastIndex,
                onChange = { steps[index] = it },
                onMinutes = { m -> points = setStepMinutes(points, index, m, minutesPerPoint) },
                onPoints = { c -> points = setStepPoints(points, index, c) },
                onFollow = { on -> points = setStepFollow(points, index, on, minutesPerPoint) },
                onMove = { delta ->
                    val other = index + delta
                    steps[index] = steps[other].also { steps[other] = steps[index] }
                    val values = points.steps.toMutableList()
                    values[index] = values[other].also { values[other] = values[index] }
                    points = points.copy(steps = values)
                },
                onRemove = {
                    steps.removeAt(index)
                    points = removeStep(points, index)
                },
            )
        }
        TextButton(onClick = {
            steps.add(StepEdit(nextKey, null, ""))
            points = addStep(points)
            nextKey++
        }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add step")
        }
    }

    pendingTotal?.let { (what, run) ->
        AlertDialog(
            onDismissRequest = { pendingTotal = null },
            title = { Text("Rescale every step?") },
            text = {
                Text(
                    "Some steps have values set by hand. Changing the task's $what spreads it across all steps " +
                        "in proportion and clears those custom values.",
                )
            },
            confirmButton = {
                TextButton(onClick = { points = run(true).state; pendingTotal = null }) { Text("Rescale steps") }
            },
            dismissButton = { TextButton(onClick = { pendingTotal = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Task totals: TTC, points and "Points follow time". Edits are applied against
 * the state from when the field gained focus (so typing "1" then "12" scales
 * the original split), live while nothing is customised; with customised steps
 * they wait for the field to lose focus, then ask.
 */
@Composable
private fun PointsSection(
    points: PointsState,
    minutesPerPoint: Double,
    onChange: (PointsState) -> Unit,
    onTotalEdit: (String, (Boolean) -> EditResult) -> Unit,
) {
    val live = liveTotals(points)
    val custom = customisedCount(points.steps)
    var snapshot by remember { mutableStateOf<PointsState?>(null) }
    val hasMinutes = points.steps.isNotEmpty() || points.task.baseMinutes != null
    val hasPoints = points.steps.isNotEmpty() || points.task.basePointsCenti != null

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberBox(
                    label = "Time (min)",
                    value = if (hasMinutes) live.minutes.toDouble() else null,
                    decimals = false,
                    marked = custom > 0,
                    modifier = Modifier.weight(1f),
                    onFocus = { snapshot = points },
                    onValue = { v ->
                        val base = snapshot ?: points
                        if (custom == 0) onChange(setTaskMinutes(base, v?.toInt(), minutesPerPoint).state)
                    },
                    onCommit = { v ->
                        val base = snapshot ?: points
                        snapshot = null
                        if (custom > 0 && v?.toInt() != live.minutes) {
                            onTotalEdit("time to complete") { c -> setTaskMinutes(base, v?.toInt(), minutesPerPoint, c) }
                        }
                    },
                )
                NumberBox(
                    label = "Points",
                    value = if (hasPoints) live.pointsCenti / 100.0 else null,
                    decimals = true,
                    marked = custom > 0,
                    modifier = Modifier.weight(1f),
                    onFocus = { snapshot = points },
                    onValue = { v ->
                        val base = snapshot ?: points
                        if (custom == 0) onChange(setTaskPoints(base, v?.let(::toCenti)).state)
                    },
                    onCommit = { v ->
                        val base = snapshot ?: points
                        snapshot = null
                        if (custom > 0 && v?.let(::toCenti) != live.pointsCenti) {
                            onTotalEdit("points") { c -> setTaskPoints(base, v?.let(::toCenti), c) }
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Points follow time", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "1 point per ${formatPoints(toCenti(minutesPerPoint))} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = points.task.pointsFollowTime,
                    onCheckedChange = { on -> onTotalEdit("points") { c -> setTaskFollow(points, on, minutesPerPoint, c) } },
                )
            }
            if (custom > 0) {
                Text(
                    "Totals influenced by $custom customised step${if (custom == 1) "" else "s"}" +
                        " (task set to ${points.task.baseMinutes ?: 0} min · ${formatPoints(points.task.basePointsCenti ?: 0)} pts)." +
                        " Changing a total rescales every step.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CustomColor,
                )
            }
        }
    }
}

/** Number field that keeps its own text while focused; reports parsed values. */
@Composable
private fun NumberBox(
    label: String,
    value: Double?,
    decimals: Boolean,
    marked: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onValue: (Double?) -> Unit,
    onCommit: (Double?) -> Unit = {},
) {
    var text by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun show(v: Double?) = when {
        v == null -> ""
        decimals -> formatPoints(toCenti(v))
        else -> v.toInt().toString()
    }
    fun parse(t: String): Double? {
        val n = t.trim().toDoubleOrNull() ?: return null
        if (n < 0) return null
        return if (decimals) toCenti(n) / 100.0 else n.toInt().toDouble()
    }
    OutlinedTextField(
        value = text ?: show(value),
        onValueChange = { t ->
            val clean = t.filter { it.isDigit() || (decimals && it == '.') }
            text = clean
            onValue(parse(clean))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier
            .then(if (marked) Modifier.border(1.dp, CustomColor, RoundedCornerShape(4.dp)) else Modifier)
            .onFocusChanged { f ->
                if (f.isFocused && !focused) {
                    focused = true
                    text = show(value)
                    onFocus()
                } else if (!f.isFocused && focused) {
                    focused = false
                    onCommit(text?.let(::parse))
                    text = null
                }
            },
    )
}

/** "Take turns": people in tap order; the number on a chip is their turn. */
@Composable
private fun RotationSection(
    members: List<Member>,
    rotation: List<String>,
    rollover: Boolean,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    FieldLabel("Take turns (optional)")
    Text(
        "Pick people in turn order. Each time it's done" +
            (if (rollover) " or its cycle ends (even if missed)" else "") +
            ", it passes to the next person.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        members.forEach { m ->
            val turn = rotation.indexOf(m.id)
            FilterChip(
                selected = turn >= 0,
                onClick = { onToggle(m.id) },
                label = { Text(if (turn >= 0) "${turn + 1}. ${m.name}" else m.name) },
            )
        }
    }
    if (rotation.size == 1) {
        Text(
            "Pick at least two people to take turns.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (rotation.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear turns") }
}

@Composable
private fun CycleSection(
    repeatKind: String,
    interval: Int,
    rollover: Boolean,
    onRollover: (Boolean) -> Unit,
    weekdays: Set<Int>,
    onWeekday: (Int) -> Unit,
    monthdays: Set<Int>,
    onMonthday: (Int) -> Unit,
    valid: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Runs in cycles", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Not done when the next cycle starts = missed: steps reset and queued points drop. Done early, it waits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = rollover, onCheckedChange = onRollover)
    }
    if (!rollover) return
    when (repeatKind) {
        "weekly" -> {
            FieldLabel("A new cycle starts on")
            DayToggleRow(WEEKDAY_LABELS.indices.toList(), weekdays, { WEEKDAY_LABELS[it] }, onToggle = onWeekday)
        }
        "monthly" -> {
            FieldLabel("A new cycle starts on day")
            (1..31).chunked(7).forEach { week ->
                DayToggleRow(week, monthdays, { it.toString() }, columns = 7, onToggle = onMonthday)
            }
            if (monthdays.any { it > 28 }) {
                Text(
                    "In months without that day, the cycle starts on the month's last day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Text(
            if (repeatKind == "daily" && interval <= 1) "A new cycle starts every midnight."
            else "A new cycle starts every $interval days at midnight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!valid) Text("Pick when each cycle starts.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ChecklistStepRow(
    step: StepEdit,
    values: com.eosoclub.ourhome.data.StepValues,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (StepEdit) -> Unit,
    onMinutes: (Int) -> Unit,
    onPoints: (Int) -> Unit,
    onFollow: (Boolean) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp, top = 4.dp)) {
                NumberBox(
                    label = if (values.minutesCustom) "Min (set)" else "Min",
                    value = values.minutes.toDouble(),
                    decimals = false,
                    marked = values.minutesCustom,
                    modifier = Modifier.weight(1f),
                    onValue = { v -> v?.let { onMinutes(it.toInt()) } },
                )
                NumberBox(
                    label = if (values.pointsFollowTime) "Pts" else "Pts (set)",
                    value = values.pointsCenti / 100.0,
                    decimals = true,
                    marked = !values.pointsFollowTime,
                    modifier = Modifier.weight(1f),
                    onValue = { v -> v?.let { onPoints(toCenti(it)) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                Text("Points follow time", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = values.pointsFollowTime, onCheckedChange = onFollow)
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
