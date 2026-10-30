package com.eosoclub.ourhome.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.eosoclub.ourhome.data.CheckText
import com.eosoclub.ourhome.data.Task
import com.eosoclub.ourhome.data.TaskSubmission
import com.eosoclub.ourhome.data.checkHeader
import com.eosoclub.ourhome.data.decideByLine
import com.eosoclub.ourhome.data.entryLines
import com.eosoclub.ourhome.data.pointsDelta
import com.eosoclub.ourhome.data.redoChoices
import com.eosoclub.ourhome.data.sentLine

/** The server's note limits (web validation/task.ts). */
private const val NOTE_MAX = 500
private const val REVIEW_NOTE_MAX = 300

/** The photo area's height, the same while loading, shown or missing, so nothing below it moves. */
private val PHOTO_HEIGHT = 200.dp

/** A small tonal label, e.g. "Waiting for an OK" on a task or a step. */
@Composable
internal fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/**
 * "Send “Dishes” for a check": an optional photo (required when the task asks
 * for one, unless a note says why not), an optional note, and Send. [photo]
 * lives in the ViewModel, so a failed send keeps it. The camera permission is
 * asked the first time Take a photo is tapped.
 */
@Composable
internal fun SendCheckDialog(
    task: Task,
    photo: ByteArray?,
    busy: Boolean,
    error: String?,
    onPhoto: (ByteArray?) -> Unit,
    onSend: (note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var note by rememberSaveable(task.id) { mutableStateOf("") }
    // Tapped "Send without a photo": the note says why.
    var withoutPhoto by rememberSaveable(task.id) { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    val noteFocus = remember { FocusRequester() }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        if (granted) cameraOpen = true
    }
    val takePhoto = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            refused = false
            cameraOpen = true
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }
    // A task that asks for a photo goes without one only with a note.
    val needsNote = task.photoProof && photo == null
    val thumbnail = remember(photo) { photo?.let { decodePhoto(it, 600) } }
    // The cycle end, after which it counts as done; nothing for tasks without cycles.
    val decideBy = task.cycleEndsAt?.takeIf { task.recurrence?.rollover == true }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(CheckText.sendTitle(task.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(CheckText.SEND_INTRO, style = MaterialTheme.typography.bodyMedium)
                decideBy?.let {
                    Text(decideByLine(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (thumbnail != null) {
                    Image(
                        thumbnail,
                        contentDescription = "Your photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(PHOTO_HEIGHT),
                    )
                    Row {
                        if (hasCamera) TextButton(onClick = takePhoto, enabled = !busy) { Text(CheckText.TAKE_AGAIN) }
                        TextButton(onClick = { onPhoto(null) }, enabled = !busy) { Text(CheckText.REMOVE_PHOTO) }
                    }
                } else {
                    if (task.photoProof) Text(CheckText.NEEDS_PHOTO, style = MaterialTheme.typography.bodyMedium)
                    if (!hasCamera) {
                        if (task.photoProof) Hint(CheckText.NO_CAMERA)
                    } else {
                        if (refused) {
                            Text(CheckText.CAMERA_REFUSED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        // Still offered after a refusal: Android asks again unless it was refused for good.
                        OutlinedButton(onClick = takePhoto, enabled = !busy) { Text(CheckText.TAKE_PHOTO) }
                    }
                    if (task.photoProof && hasCamera) {
                        if (withoutPhoto) {
                            Hint(CheckText.NO_CAMERA)
                        } else {
                            TextButton(onClick = { withoutPhoto = true }, enabled = !busy) { Text(CheckText.WITHOUT_PHOTO) }
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(NOTE_MAX) },
                    label = { Text(if (needsNote) CheckText.NOTE else CheckText.NOTE_OPTIONAL) },
                    enabled = !busy,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(noteFocus),
                )
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(note) }, enabled = !busy && (!needsNote || note.isNotBlank())) {
                BusyLabel(CheckText.SEND, busy)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(CheckText.CANCEL) } },
    )
    LaunchedEffect(withoutPhoto) { if (withoutPhoto) runCatching { noteFocus.requestFocus() } }
    if (cameraOpen) {
        TaskCameraDialog(
            onTaken = { bytes ->
                cameraOpen = false
                withoutPhoto = false
                onPhoto(bytes)
            },
            onDismiss = { cameraOpen = false },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A button label that keeps its width while a spinner shows over it. */
@Composable
private fun BusyLabel(text: String, busy: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Text(text, modifier = Modifier.alpha(if (busy) 0f else 1f))
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

/**
 * One item in To check: who and what, when it was sent (or that it counted as
 * done when the day ended), how many steps were ticked, the points. Opened,
 * it shows the note, the photo (loaded only now, from memory afterwards; tap
 * to enlarge), the steps or the step entries, and Approve / Send back.
 */
@Composable
internal fun CheckCard(
    item: TaskSubmission,
    showPoints: Boolean,
    expanded: Boolean,
    /** Absent = not loaded yet; null = there is no photo any more. */
    photo: Result<ImageBitmap?>?,
    busy: Boolean,
    onToggle: () -> Unit,
    onApprove: () -> Unit,
    onSendBack: () -> Unit,
    onEnlarge: (ImageBitmap) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(checkHeader(item), style = MaterialTheme.typography.titleSmall)
                val meta = listOfNotNull(
                    if (item.countedAtCycleEnd) CheckText.COUNTED_AT_CYCLE_END else sentLine(item.submittedAt),
                    item.takeIf { it.kind == "task" && it.steps.isNotEmpty() }?.let { CheckText.stepsTicked(it.stepsTicked, it.steps.size) },
                    pointsDelta(item.points).takeIf { showPoints },
                )
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!item.countedAtCycleEnd) {
                    item.decideBy?.let {
                        Text(decideByLine(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        if (!expanded) return@Card
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (item.hasPhoto) {
                Box(Modifier.fillMaxWidth().height(PHOTO_HEIGHT), contentAlignment = Alignment.Center) {
                    val image = photo?.getOrNull()
                    when {
                        photo == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        image != null -> Image(
                            image,
                            contentDescription = "Their photo. Tap to make it bigger.",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().clickable { onEnlarge(image) },
                        )
                        else -> Hint(if (photo.isFailure) "Couldn't load the photo." else "No photo for that check.")
                    }
                }
            }
            if (item.kind == "task") {
                item.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Same space ticked or not, so the titles line up.
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            if (step.ticked) {
                                Icon(Icons.Filled.Check, contentDescription = "Ticked", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(
                            step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (step.ticked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            } else {
                entryLines(item.entries).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (showPoints) Text(CheckText.total(item.points), style = MaterialTheme.typography.labelLarge)
            }
            if (item.canDecide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    OutlinedButton(onClick = onSendBack, enabled = !busy) { Text(CheckText.SEND_BACK) }
                    Button(onClick = onApprove, enabled = !busy) { BusyLabel(CheckText.APPROVE, busy) }
                }
            }
        }
    }
}

/**
 * "Send “Dishes” back to Riley": what needs fixing (required), and for a task
 * the steps to do again. A steps item and one counted as done when its day
 * ended say what sending back does instead of listing steps.
 */
@Composable
internal fun SendBackDialog(
    item: TaskSubmission,
    showPoints: Boolean,
    busy: Boolean,
    errors: FormErrors,
    onSend: (note: String, redoStepIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable(item.id) { mutableStateOf("") }
    var redo by rememberSaveable(item.id) { mutableStateOf(listOf<String>()) }
    // Only the steps ticked when it was sent can be done again (as on the web).
    val choices = redoChoices(item)
    val listSteps = choices.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(CheckText.sendBackTitle(item.task.title, item.child.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    item.countedAtCycleEnd -> Text(
                        if (showPoints) CheckText.takeBack(item.points) else "The task stays done for that day.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    item.kind == "steps" -> Text(CheckText.STEPS_SEND_BACK, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(REVIEW_NOTE_MAX); errors.clear("reviewNote") },
                    label = { Text(CheckText.WHAT_NEEDS_FIXING) },
                    enabled = !busy,
                    minLines = 2,
                    isError = errors["reviewNote"] != null,
                    supportingText = errors["reviewNote"]?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (listSteps) {
                    FieldLabel(CheckText.REDO_STEPS)
                    Column {
                        choices.forEach { step ->
                            val on = step.id in redo
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                    redo = if (on) redo - step.id else redo + step.id
                                    errors.clear("redoStepIds")
                                },
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, enabled = !busy)
                                Text(step.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    Hint(CheckText.REDO_STEPS_HELP)
                    FieldError(errors["redoStepIds"])
                }
                // Anything not about one field (someone already decided it, ...).
                errors.message?.takeIf { errors.fields.isEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(note, redo) }, enabled = !busy && note.isNotBlank()) {
                BusyLabel(CheckText.SEND_BACK, busy)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(CheckText.CANCEL) } },
    )
}

/** A check's photo over the whole screen; a tap anywhere closes it. */
@Composable
internal fun PhotoViewer(image: ImageBitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            Image(image, contentDescription = "Their photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/** Space under the To check list, before the tasks. */
@Composable
internal fun ToCheckHeading() {
    Column {
        Spacer(Modifier.height(4.dp))
        Text(CheckText.TO_CHECK, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
    }
}
