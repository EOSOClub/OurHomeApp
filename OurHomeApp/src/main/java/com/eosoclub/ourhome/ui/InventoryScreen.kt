package com.eosoclub.ourhome.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.eosoclub.ourhome.nfc.NfcScans
import com.eosoclub.ourhome.nfc.NfcStatus
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import com.eosoclub.ourhome.data.PageAccess
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.InventoryItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InventoryViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val items: List<InventoryItem> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val editing: InventoryItem? = null,
        val saving: Boolean = false,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // In-flight adjustments per item. While any are pending, the optimistic
    // quantity wins over a (possibly stale) server response.
    private val pending = mutableMapOf<String, Int>()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load(
            { api.inventory() },
            { s, fresh ->
                // Items with an adjustment still in flight keep their optimistic
                // count; the adjustment's own response settles them.
                val shown = s.items.associateBy { it.id }
                val merged = fresh.map { item -> if ((pending[item.id] ?: 0) > 0) shown[item.id] ?: item else item }
                s.copy(items = merged, loading = false, error = null)
            },
            { s, e -> s.copy(loading = false, error = e) },
        )
    }

    fun adjust(item: InventoryItem, delta: Double) {
        val current = state.value.items.firstOrNull { it.id == item.id } ?: return
        if (current.quantity + delta < 0) return
        replace(item.id) { it.copy(quantity = it.quantity + delta) }
        pending[item.id] = (pending[item.id] ?: 0) + 1
        viewModelScope.launch {
            try {
                val updated = api.adjustInventory(item.id, delta)
                val stillPending = (pending[item.id] ?: 1) > 1
                replace(item.id) { local -> if (stillPending) updated.copy(quantity = local.quantity) else updated }
            } catch (e: Exception) {
                replace(item.id) { it.copy(quantity = it.quantity - delta) }
                _messages.send(e.message ?: "Couldn't update ${item.name}")
            } finally {
                pending[item.id] = (pending[item.id] ?: 1) - 1
            }
        }
    }

    fun startEdit(item: InventoryItem) = state.update { it.copy(editing = item) }

    fun cancelEdit() = state.update { it.copy(editing = null) }

    fun save(item: InventoryItem, name: String, unit: String?, quantity: Double, lowThreshold: Double, reorderDays: Int?) =
        viewModelScope.launch {
            state.update { it.copy(saving = true) }
            try {
                val updated = api.updateInventoryItem(item.id, name, unit, quantity, lowThreshold, reorderDays)
                replace(item.id) { updated }
                state.update { it.copy(editing = null) }
            } catch (e: Exception) {
                _messages.send(e.message ?: "Couldn't save ${item.name}")
            } finally {
                state.update { it.copy(saving = false) }
            }
        }

    fun delete(item: InventoryItem) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            api.deleteInventoryItem(item.id)
            state.update { s -> s.copy(items = s.items.filterNot { it.id == item.id }, editing = null) }
            _messages.send("Deleted ${item.name}")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't delete ${item.name}")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    private fun replace(id: String, transform: (InventoryItem) -> InventoryItem) =
        state.update { s -> s.copy(items = s.items.map { if (it.id == id) transform(it) else it }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    api: ApiClient,
    access: PageAccess,
    userId: String,
    showMessage: (String) -> Unit,
    onOpenScanHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { InventoryViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()

    state.editing?.let { item ->
        InventoryItemEditor(
            item = item,
            saving = state.saving,
            canSave = access.canEdit(item.createdById, userId),
            onDismiss = vm::cancelEdit,
            onSave = { name, unit, qty, low, days -> vm.save(item, name, unit, qty, low, days) },
            onDelete = if (access.canDelete(item.createdById, userId)) ({ vm.delete(item) }) else null,
        )
    }
    var lowOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    val lowCount = state.items.count { it.isLow }
    val visible = state.items
        .filter { !lowOnly || it.isLow }
        .sortedWith(compareByDescending<InventoryItem> { it.isLow }.thenBy { it.name.lowercase() })

    PullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (LoadState(state.loading, state.error, state.items.isEmpty(), "No inventory items yet.", vm::refresh)) {
            return@PullToRefreshBox
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "scan") { ScanCard(onOpenScanHistory) }
            item(key = "filter") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !lowOnly, onClick = { lowOnly = false }, label = { Text("All (${state.items.size})") })
                    FilterChip(selected = lowOnly, onClick = { lowOnly = true }, label = { Text("Low ($lowCount)") })
                }
            }
            items(visible, key = { it.id }) { item ->
                InventoryRow(
                    item,
                    // ± stock isn't an edit: any Inventory access will do (as on the server).
                    onAdjust = if (access.any) ({ delta -> vm.adjust(item, delta) }) else null,
                    // The editor also holds Delete, so either opens it.
                    onEdit = if (access.canEdit(item.createdById, userId) || access.canDelete(item.createdById, userId)) {
                        { vm.startEdit(item) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun InventoryRow(item: InventoryItem, onAdjust: ((Double) -> Unit)?, onEdit: (() -> Unit)?) {
    val colors = if (item.isLow) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    else CardDefaults.cardColors()
    Card(colors = colors, modifier = Modifier.fillMaxWidth().clickable(enabled = onEdit != null) { onEdit?.invoke() }) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                val meta = listOfNotNull(
                    "Low".takeIf { item.isLow },
                    item.predictedDepletionAt?.let { "Runs out ~${formatDate(it)}" },
                    item.category?.name,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isLow) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onAdjust != null) {
                FilledTonalIconButton(onClick = { onAdjust(-1.0) }, enabled = item.quantity >= 1) {
                    Text("−", style = MaterialTheme.typography.titleLarge)
                }
            }
            Column(Modifier.widthIn(min = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatQuantity(item.quantity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                item.unit?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            if (onAdjust != null) {
                FilledTonalIconButton(onClick = { onAdjust(1.0) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add one ${item.name}")
                }
            }
        }
    }
}

/** How to scan, NFC availability, and the way into scan history. */
@Composable
private fun ScanCard(onOpenScanHistory: () -> Unit) {
    val context = LocalContext.current
    // Re-checked when the user comes back from NFC settings.
    var status by remember { mutableStateOf(NfcScans.status(context)) }
    LifecycleResumeEffect(Unit) {
        status = NfcScans.status(context)
        onPauseOrDispose {}
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Scan a tag", style = MaterialTheme.typography.titleMedium)
            Text(
                when (status) {
                    NfcStatus.Ready -> "Hold a tag to the back of your phone while the app is open. New tags can be set up right here."
                    NfcStatus.Disabled -> "NFC is turned off. Turn it on to scan tags."
                    NfcStatus.Unsupported -> "This phone can't read NFC tags."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == NfcStatus.Disabled) {
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                        Text("Turn on NFC")
                    }
                }
                TextButton(onClick = onOpenScanHistory) { Text("Recent scans") }
            }
        }
    }
}

@Composable
private fun InventoryItemEditor(
    item: InventoryItem,
    saving: Boolean,
    /** False when the user may only delete this item, not change it. */
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, unit: String?, quantity: Double, lowThreshold: Double, reorderDays: Int?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(item.name) }
    var unit by remember { mutableStateOf(item.unit.orEmpty()) }
    var quantity by remember { mutableStateOf(formatQuantity(item.quantity)) }
    var low by remember { mutableStateOf(formatQuantity(item.lowThreshold)) }
    var days by remember { mutableStateOf(item.reorderIntervalDays?.toString().orEmpty()) }

    val qty = quantity.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val lowValue = low.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val daysValue = days.toIntOrNull()
    val daysValid = days.isBlank() || daysValue in 1..3650

    EditorDialog(
        title = if (canSave) "Edit item" else "Item",
        saving = saving,
        saveEnabled = canSave && name.isNotBlank() && qty != null && lowValue != null && daysValid,
        onDismiss = onDismiss,
        onSave = { onSave(name.trim(), unit.trim().ifEmpty { null }, qty!!, lowValue!!, daysValue) },
        deleteLabel = "Delete item",
        onDelete = onDelete,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = unit,
            onValueChange = { unit = it },
            label = { Text("Unit") },
            placeholder = { Text("e.g. rolls, lbs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text("Quantity on hand") },
            singleLine = true,
            isError = qty == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = low,
            onValueChange = { low = it },
            label = { Text("Low-stock threshold") },
            supportingText = { Text("Flagged as low at or below this amount.") },
            singleLine = true,
            isError = lowValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = days,
            onValueChange = { days = it.filter(Char::isDigit).take(4) },
            label = { Text("Reorder every (days)") },
            supportingText = { Text("Optional. Drives the “runs out” forecast.") },
            singleLine = true,
            isError = !daysValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
