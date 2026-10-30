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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import com.eosoclub.ourhome.data.CheckText
import com.eosoclub.ourhome.data.Places
import com.eosoclub.ourhome.data.placeChoices
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

/** Parsed reset cadence: null when blank, 0 when invalid (1-365 like the web). */
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

/** The keys the server reports a task's location under. */
private val PLACE_KEYS = listOf("roomIds", "floorIds", "roomId", "floorId")

/** The keys the server reports the task's time and points under. */
private val POINTS_KEYS = listOf("estimatedMinutes", "points", "pointsFollowTime")

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
    /** Floors and rooms for the Location picker; null while loading (or unreadable). */
    places: Places?,
    saving: Boolean,
    /** The last save failure: said at the top, and the fields the server named marked. */
    errors: FormErrors,
    /** False when the user may only delete this task, not change it. */
    canSave: Boolean,
    /** False when the server admin turned Points off: only time shows (points still follow it). */
    showPoints: Boolean,
    /** Household rate (from any loaded task; the default before that). */
    minutesPerPoint: Double = task?.minutesPerPoint ?: DEFAULT_MINUTES_PER_POINT,
    onDismiss: () -> Unit,
    onSave: (TaskEdit) -> Unit,
    onDelete: (() -> Unit)?,
    /** "Ask for a photo" shows; false for a teen or child someone looks after (the server refuses their change). */
    canSetPhotoProof: Boolean = true,
    /** The task waits for an OK on my own check: I can change nothing until I take it back. */
    sentByMe: Boolean = false,
) {
    val zone = ZoneId.systemDefault()
    // While it waits for an OK only the name, notes, places and step names may
    // change (the server compares every value, so the full form is still sent).
    val locked = task?.waitingForOk == true
    val allLocked = locked && sentByMe
    val open = !locked
    val originalDue = task?.dueDate?.let { Instant.parse(it).atZone(zone) }
    val rule = task?.recurrence

    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var notes by remember { mutableStateOf(task?.notes.orEmpty()) }
    var type by remember { mutableStateOf(task?.type ?: "one_time") }
    var priority by remember { mutableStateOf(task?.priority ?: "medium") }
    var dueDate by remember { mutableStateOf(originalDue?.toLocalDate()) }
    var dueTime by remember { mutableStateOf(originalDue?.toLocalTime()) }
    var categoryId by remember { mutableStateOf(task?.category?.id.orEmpty()) }
    // "room:<id>" / "floor:<id>" for each place; empty = the whole house.
    var placeValues by remember { mutableStateOf(task?.placeChoices().orEmpty()) }
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
    var photoProof by remember { mutableStateOf(task?.photoProof ?: false) }
    var nextKey by remember { mutableIntStateOf(task?.subtasks?.size ?: 0) }
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
    // A new task worth 0 points: Add goes red and asks first (like the web).
    // Skipped when the household has points turned off.
    val noPoints = task == null && showPoints && liveTotals(points).pointsCenti == 0
    var confirmNoPoints by remember { mutableStateOf(false) }

    fun save() {
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
                    // Without the rooms loaded the picker wasn't shown: keep the places.
                    places = placeValues.takeIf { places?.isEmpty == false },
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
                    // Not sent while the switch is hidden, so it stays as it is.
                    photoProof = photoProof.takeIf { canSetPhotoProof },
                ),
                steps = steps.mapIndexedNotNull { i, s ->
                    s.title.trim().takeIf { it.isNotEmpty() }?.let {
                        StepInput(s.id, it, s.resetIntervalDays(), points.steps[i])
                    }
                },
            ),
        )
    }

    EditorDialog(
        title = when {
            task == null -> "New task"
            canSave -> "Edit task"
            else -> "Task"
        },
        saveLabel = if (task == null) "Add" else "Save",
        saveWarning = noPoints,
        saving = saving,
        saveEnabled = canSave && title.isNotBlank() && intervalValid && stepsValid && cycleValid,
        onDismiss = onDismiss,
        onSave = { if (noPoints) confirmNoPoints = true else save() },
        deleteLabel = "Delete task",
        onDelete = onDelete,
        error = errors.message,
    ) {
        if (locked) {
            Text(
                if (allLocked) CheckText.EDIT_WHILE_MINE_WAITS else CheckText.EDIT_WHILE_WAITING,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; errors.clear("title") },
            label = { Text("Title") },
            placeholder = { Text("e.g. Take the trash out") },
            singleLine = true,
            enabled = !allLocked,
            isError = errors["title"] != null,
            supportingText = errors["title"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; errors.clear("notes") },
            label = { Text("Notes") },
            placeholder = { Text("Optional details…") },
            minLines = 2,
            enabled = !allLocked,
            isError = errors["notes"] != null,
            supportingText = errors["notes"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField("Type", TASK_TYPES, type, onSelect = { type = it; errors.clear("type") }, error = errors["type"], enabled = open)
        FieldLabel("Priority")
        ChoiceChips(TASK_PRIORITIES, priority, enabled = open) { priority = it; errors.clear("priority") }
        FieldError(errors["priority"])
        DateField("Due date", dueDate, errors["dueDate"], enabled = open) {
            dueDate = it
            if (it == null) dueTime = null
            errors.clear("dueDate")
        }
        // The web disables the time until a date is picked; here it's hidden.
        if (dueDate != null) TimeField("Due time", dueTime, enabled = open) { dueTime = it; errors.clear("dueDate") }
        // Until the lists load, offer the task's current value so it still shows.
        if (rotating) {
            DropdownField(
                "Whose turn now",
                rotation.map { it to (memberNames[it] ?: "Member") },
                currentTurn,
                onSelect = { assigneeId = it; errors.clear("assigneeId") },
                error = errors["assigneeId"],
                enabled = open,
            )
        } else {
            DropdownField(
                "Assignee",
                listOf("" to "Unassigned") + (members?.map { it.id to it.name }
                    ?: listOfNotNull(task?.assignee?.let { it.id to (it.name ?: "Member") })),
                assigneeId,
                onSelect = { assigneeId = it; errors.clear("assigneeId") },
                error = errors["assigneeId"],
                enabled = open,
            )
        }
        DropdownField(
            "Category",
            listOf("" to "None") + (categories?.map { it.id to it.name }
                ?: listOfNotNull(task?.category?.let { it.id to it.name })),
            categoryId,
            onSelect = { categoryId = it; errors.clear("categoryId") },
            error = errors["categoryId"],
            enabled = open,
        )
        if (canSetPhotoProof) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(CheckText.PHOTO_PROOF, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        CheckText.PHOTO_PROOF_HELP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = photoProof, onCheckedChange = { photoProof = it; errors.clear("photoProof") }, enabled = open)
            }
            FieldError(errors["photoProof"])
        }
        // Rooms are set up in Settings → Rooms & floors (here or on the website).
        if (places?.isEmpty == false) {
            LocationChips(places, placeValues, enabled = !allLocked) { value ->
                placeValues = if (value in placeValues) placeValues - value else placeValues + value
                PLACE_KEYS.forEach(errors::clear)
            }
            FieldError(PLACE_KEYS.firstNotNullOfOrNull { errors[it] })
        }

        if (recurring) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The server reports anything in the repeat rule under "recurrence".
                    FieldError(errors["recurrence"])
                    DropdownField("Repeats", REPEAT_KINDS, repeatKind, onSelect = { repeatKind = it; errors.clear("recurrence") }, enabled = open)
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { v -> interval = v.filter(Char::isDigit); errors.clear("recurrence") },
                        enabled = open,
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
                        DayToggleRow(WEEKDAY_LABELS.indices.toList(), weekdays, { WEEKDAY_LABELS[it] }, enabled = open) { day ->
                            weekdays = if (day in weekdays) weekdays - day else weekdays + day
                            errors.clear("recurrence")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { weekdays = setOf(1, 2, 3, 4, 5); errors.clear("recurrence") }, enabled = open) { Text("Weekdays") }
                            OutlinedButton(onClick = { weekdays = setOf(0, 6); errors.clear("recurrence") }, enabled = open) { Text("Weekend") }
                        }
                    }
                    if (repeatKind == "monthly") {
                        FieldLabel("On days of month (optional)")
                        (1..31).chunked(7).forEach { week ->
                            DayToggleRow(week, monthdays, { it.toString() }, columns = 7, enabled = open) { day ->
                                monthdays = if (day in monthdays) monthdays - day else monthdays + day
                                errors.clear("recurrence")
                            }
                        }
                    }
                    DateField("Ends on (optional)", until, enabled = open) { until = it; errors.clear("recurrence") }
                    RotationSection(
                        members = members ?: task?.rotation?.map { Member(it.id, it.name ?: "Member") }.orEmpty(),
                        rotation = rotation,
                        rollover = rollover,
                        enabled = open,
                        onToggle = { id ->
                            rotation = if (id in rotation) rotation - id else rotation + id
                            errors.clear("rotationUserIds")
                        },
                        onClear = { rotation = emptyList(); errors.clear("rotationUserIds") },
                    )
                    FieldError(errors["rotationUserIds"])
                    CycleSection(
                        repeatKind = repeatKind,
                        interval = intervalValue ?: 1,
                        rollover = rollover,
                        onRollover = { rollover = it; errors.clear("recurrence") },
                        weekdays = cycleWeekdays,
                        onWeekday = { d ->
                            cycleWeekdays = if (d in cycleWeekdays) cycleWeekdays - d else cycleWeekdays + d
                            errors.clear("recurrence")
                        },
                        monthdays = cycleMonthdays,
                        onMonthday = { d ->
                            cycleMonthdays = if (d in cycleMonthdays) cycleMonthdays - d else cycleMonthdays + d
                            errors.clear("recurrence")
                        },
                        valid = cycleValid,
                        enabled = open,
                    )
                }
            }
        }

        PointsSection(
            points = points,
            minutesPerPoint = minutesPerPoint,
            showPoints = showPoints,
            enabled = open,
            onChange = { points = it; POINTS_KEYS.forEach(errors::clear) },
            onTotalEdit = { what, run ->
                val r = run(false)
                if (r.needsConfirm) pendingTotal = what to run else points = r.state
                POINTS_KEYS.forEach(errors::clear)
            },
        )
        FieldError(POINTS_KEYS.firstNotNullOfOrNull { errors[it] })

        FieldLabel(if (task == null) "Checklist (optional)" else "Checklist")
        // The server reports anything in the steps under "subtasks", saying which step.
        FieldError(errors["subtasks"])
        steps.forEachIndexed { index, step ->
            // Keyed by the step, so a row's typed text and focus move with it.
            key(step.key) {
                ChecklistStepRow(
                    step = step,
                    values = points.steps[index],
                    index = index,
                    isFirst = index == 0,
                    isLast = index == steps.lastIndex,
                    showPoints = showPoints,
                    // While it waits, a step keeps its place and values; only its name may change.
                    locked = locked,
                    titleLocked = allLocked,
                    onChange = { steps[index] = it; errors.clear("subtasks") },
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
        }
        TextButton(
            onClick = {
                steps.add(StepEdit(nextKey, null, ""))
                points = addStep(points)
                nextKey++
            },
            enabled = open,
        ) {
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

    if (confirmNoPoints) {
        AlertDialog(
            onDismissRequest = { confirmNoPoints = false },
            title = { Text("No points for this task?") },
            text = {
                Text(
                    "Nobody will earn points for completing this task. Go back to set a time to complete " +
                        "or points, or add it anyway.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmNoPoints = false; save() }) {
                    Text("Add anyway", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmNoPoints = false }) { Text("Go back") } },
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
    showPoints: Boolean,
    enabled: Boolean,
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
                    enabled = enabled,
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
                if (showPoints) NumberBox(
                    label = "Points",
                    value = if (hasPoints) live.pointsCenti / 100.0 else null,
                    decimals = true,
                    marked = custom > 0,
                    enabled = enabled,
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
            if (showPoints) Row(verticalAlignment = Alignment.CenterVertically) {
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
                    enabled = enabled,
                )
            }
            if (custom > 0) {
                Text(
                    "Totals influenced by $custom customised step${if (custom == 1) "" else "s"}" +
                        " (task set to ${points.task.baseMinutes ?: 0} min" +
                        (if (showPoints) " · ${formatPoints(points.task.basePointsCenti ?: 0)} pts" else "") + ")." +
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
    enabled: Boolean = true,
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
        enabled = enabled,
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
    enabled: Boolean,
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
                enabled = enabled,
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
    if (rotation.isNotEmpty()) TextButton(onClick = onClear, enabled = enabled) { Text("Clear turns") }
}

/**
 * The task Location picker (the web's LocationMultiSelect): chips per floor
 * (the whole floor, then its rooms), then rooms on no floor. Several can be
 * picked; none = the whole house. [onToggle] gets the tapped value.
 */
@Composable
private fun LocationChips(places: Places, picked: List<String>, enabled: Boolean = true, onToggle: (String) -> Unit) {
    FieldLabel("Location")
    Text(
        if (picked.isEmpty()) "Whole house. Pick one or more places to narrow it down."
        else "Tap a place again to take it off.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    places.choiceGroups().forEach { (title, options) ->
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = value in picked, onClick = { onToggle(value) }, label = { Text(label) }, enabled = enabled)
            }
        }
    }
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
    enabled: Boolean,
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
        Switch(checked = rollover, onCheckedChange = onRollover, enabled = enabled)
    }
    if (!rollover) return
    when (repeatKind) {
        "weekly" -> {
            FieldLabel("A new cycle starts on")
            DayToggleRow(WEEKDAY_LABELS.indices.toList(), weekdays, { WEEKDAY_LABELS[it] }, enabled = enabled, onToggle = onWeekday)
        }
        "monthly" -> {
            FieldLabel("A new cycle starts on day")
            (1..31).chunked(7).forEach { week ->
                DayToggleRow(week, monthdays, { it.toString() }, columns = 7, enabled = enabled, onToggle = onMonthday)
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
    showPoints: Boolean,
    /** Waiting for an OK: everything but the name is fixed. */
    locked: Boolean,
    /** My own check waits: the name too. */
    titleLocked: Boolean,
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
                    enabled = !titleLocked,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove, enabled = !locked) { Icon(Icons.Filled.Delete, contentDescription = "Remove step") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp, top = 4.dp)) {
                NumberBox(
                    label = if (values.minutesCustom) "Min (set)" else "Min",
                    value = values.minutes.toDouble(),
                    decimals = false,
                    marked = values.minutesCustom,
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                    onValue = { v -> v?.let { onMinutes(it.toInt()) } },
                )
                if (showPoints) NumberBox(
                    label = if (values.pointsFollowTime) "Pts" else "Pts (set)",
                    value = values.pointsCenti / 100.0,
                    decimals = true,
                    marked = !values.pointsFollowTime,
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                    onValue = { v -> v?.let { onPoints(toCenti(it)) } },
                )
            }
            if (showPoints) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                Text("Points follow time", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = values.pointsFollowTime, onCheckedChange = onFollow, enabled = !locked)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = step.resetDays,
                    onValueChange = { v -> onChange(step.copy(resetDays = v.filter(Char::isDigit))) },
                    label = { Text("Unchecks every N days") },
                    placeholder = { Text("Never") },
                    singleLine = true,
                    enabled = !locked,
                    isError = step.resetIntervalDays() == 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst && !locked) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move step up")
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast && !locked) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move step down")
                }
            }
        }
    }
}
