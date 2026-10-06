package com.eosoclub.ourhome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Full-screen editor shell: close + title + Save on top, the form scrolling
 * below, and an optional confirmed Delete at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorDialog(
    title: String,
    saving: Boolean,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    deleteLabel: String? = null,
    onDelete: (() -> Unit)? = null,
    saveLabel: String = "Save",
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    },
                    actions = {
                        TextButton(onClick = onSave, enabled = saveEnabled && !saving) {
                            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(saveLabel)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !saving,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) { Text(deleteLabel ?: "Delete") }
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${deleteLabel ?: "Delete"}?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** A read-only field that opens a date picker; the trailing button clears it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = date?.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("None") },
            leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The text field swallows taps, so an overlay opens the picker.
        Box(Modifier.matchParentSize().padding(end = 48.dp).clickable { picking = true })
        if (date != null) {
            IconButton(onClick = { onChange(null) }, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)) {
                Icon(Icons.Filled.Clear, contentDescription = "Clear $label")
            }
        }
    }
    if (picking) {
        // DatePicker works in UTC-midnight millis for the chosen calendar day.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
internal fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option.replace('_', ' ').replaceFirstChar(Char::uppercase)) },
            )
        }
    }
}

/** A read-only dropdown; [options] are (value, label) pairs, [value] "" shows the placeholder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    placeholder: String = "Choose…",
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (optionValue, optionLabel) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = { onSelect(optionValue); open = false })
            }
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The instant to store for a picked [date]. Matches the web forms: noon local
 * time, or — when editing — the original instant's time of day, so changing
 * only the date never shifts a time the user set on the web.
 */
internal fun dueInstant(date: LocalDate?, original: String?): Instant? {
    date ?: return null
    val zone = ZoneId.systemDefault()
    val time = original?.let { Instant.parse(it).atZone(zone).toLocalTime() } ?: LocalTime.NOON
    return date.atTime(time).atZone(zone).toInstant()
}
