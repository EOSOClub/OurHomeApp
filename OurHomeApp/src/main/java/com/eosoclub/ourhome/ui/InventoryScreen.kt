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
import com.eosoclub.ourhome.data.PlaceGroups
import com.eosoclub.ourhome.data.Places
import com.eosoclub.ourhome.data.groupByPlace
import com.eosoclub.ourhome.data.placeChoice
import com.eosoclub.ourhome.data.placeLabel
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.rememberLazyListState
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
        /** Floors and rooms; null until loaded (or from an older server). */
        val places: Places? = null,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** "By room" instead of one list; kept here so it survives switching tabs. */
    val byRoom = MutableStateFlow(false)

    /** A room a scanned location tag asked for (Tasks off); the list shows it once, then clears this. */
    val focusRoom = MutableStateFlow<String?>(null)

    fun openRoom(roomId: String) {
        byRoom.value = true
        focusRoom.value = roomId
        refresh()
    }

    // In-flight adjustments per item. While any are pending, the optimistic
    // quantity wins over a (possibly stale) server response.
    private val pending = mutableMapOf<String, Int>()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        // Rooms change rarely; a failure (older server) keeps the last.
        launch { runCatching { api.places() }.onSuccess { p -> state.update { it.copy(places = p) } } }
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

    /** The open editor's last save failure: why, and which fields to mark. */
    val form = FormErrors()

    fun startEdit(item: InventoryItem) {
        form.clear()
        state.update { it.copy(editing = item) }
    }

    fun cancelEdit() {
        form.clear()
        state.update { it.copy(editing = null) }
    }

    /** [place]: a picker value, or null to leave it (no rooms to pick from). */
    fun save(item: InventoryItem, name: String, unit: String?, quantity: Double, lowThreshold: Double, reorderDays: Int?, place: String?) =
        viewModelScope.launch {
            state.update { it.copy(saving = true) }
            form.clear()
            try {
                val updated = api.updateInventoryItem(item.id, name, unit, quantity, lowThreshold, reorderDays, place)
                replace(item.id) { updated }
                state.update { it.copy(editing = null) }
            } catch (e: Exception) {
                // Shown in the editor, which sits over the snackbar.
                form.fail(e, "Couldn't save ${item.name}")
            } finally {
                state.update { it.copy(saving = false) }
            }
        }

    fun delete(item: InventoryItem) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            api.deleteInventoryItem(item.id)
            state.update { s -> s.copy(items = s.items.filterNot { it.id == item.id }, editing = null) }
            _messages.send("Deleted ${item.name}")
        } catch (e: Exception) {
            form.fail(e, "Couldn't delete ${item.name}")
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
    val places = state.places?.takeUnless { it.isEmpty }
    val byRoomChosen by vm.byRoom.collectAsStateWithLifecycle()
    val byRoom = byRoomChosen && places != null
    val focusRoom by vm.focusRoom.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    state.editing?.let { item ->
        InventoryItemEditor(
            item = item,
            places = places,
            saving = state.saving,
            errors = vm.form,
            canSave = access.canEdit(item.createdById, userId),
            onDismiss = vm::cancelEdit,
            onSave = { name, unit, qty, low, days, place -> vm.save(item, name, unit, qty, low, days, place) },
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
    val groups = if (byRoom && places != null) groupByPlace(visible, places) else null

    // A scanned location tag (Tasks off): scroll to its room once items and rooms are in.
    LaunchedEffect(focusRoom, state.loading, places) {
        val roomId = focusRoom ?: return@LaunchedEffect
        if (state.loading || places == null) return@LaunchedEffect
        lowOnly = false
        val all = groupByPlace(
            state.items.sortedWith(compareByDescending<InventoryItem> { it.isLow }.thenBy { it.name.lowercase() }),
            places,
        )
        // Rows above the groups: the scan card and the filter row.
        val index = stockRoomRowIndex(all, roomId, leading = 2)
        if (index != null) listState.animateScrollToItem(index)
        else showMessage("Nothing is kept in ${places.roomLabel(roomId) ?: "that room"} yet.")
        vm.focusRoom.value = null
    }

    PullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (LoadState(state.loading, state.error, state.items.isEmpty(), "No inventory items yet.", vm::refresh)) {
            return@PullToRefreshBox
        }
        val row: @Composable (InventoryItem) -> Unit = { item ->
            InventoryRow(
                item,
                showPlace = !byRoom,
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
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "scan") { ScanCard(onOpenScanHistory) }
            item(key = "filter") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !lowOnly, onClick = { lowOnly = false }, label = { Text("All (${state.items.size})") })
                    FilterChip(selected = lowOnly, onClick = { lowOnly = true }, label = { Text("Low ($lowCount)") })
                    // Rooms are set up in Settings → Rooms & floors.
                    if (places != null) {
                        Spacer(Modifier.weight(1f))
                        FilterChip(selected = byRoom, onClick = { vm.byRoom.value = !byRoom }, label = { Text("By room") })
                    }
                }
            }
            if (groups != null) {
                groups.floors.forEach { g ->
                    item(key = "floor:${g.floor?.id ?: "none"}") {
                        RoomHeading(g.floor?.name ?: "Other rooms", g.tasks.size + g.rooms.sumOf { it.tasks.size }, floor = true)
                    }
                    items(g.tasks, key = { it.id }) { row(it) }
                    g.rooms.forEach { r ->
                        item(key = "room:${r.room.id}") { RoomHeading(r.room.name, r.tasks.size, floor = false) }
                        items(r.tasks, key = { it.id }) { row(it) }
                    }
                }
                if (groups.house.isNotEmpty()) {
                    item(key = "house") { RoomHeading("No room set", groups.house.size, floor = true) }
                    items(groups.house, key = { it.id }) { row(it) }
                }
            } else {
                items(visible, key = { it.id }) { row(it) }
            }
        }
    }
}

/**
 * The LazyColumn index of [roomId]'s heading in the "By room" list, or null
 * when nothing is kept there. Mirrors the list: [leading] rows, then per
 * floor its heading, whole-floor items and rooms (heading + items).
 */
private fun stockRoomRowIndex(groups: PlaceGroups<InventoryItem>, roomId: String, leading: Int): Int? {
    var index = leading
    for (g in groups.floors) {
        index += 1 + g.tasks.size
        for (r in g.rooms) {
            if (r.room.id == roomId) return index
            index += 1 + r.tasks.size
        }
    }
    return null
}

/** A floor or room heading in the "By room" list. */
@Composable
private fun RoomHeading(title: String, count: Int, floor: Boolean) {
    Text(
        "$title ($count)",
        style = if (floor) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
        color = if (floor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = if (floor) 4.dp else 16.dp, top = if (floor) 12.dp else 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun InventoryRow(item: InventoryItem, showPlace: Boolean, onAdjust: ((Double) -> Unit)?, onEdit: (() -> Unit)?) {
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
                    // Under its room's heading already in "By room".
                    item.placeLabel().takeIf { showPlace },
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
    /** Null (none set up, or not loaded) hides the "Kept in" picker. */
    places: Places?,
    saving: Boolean,
    /** The last save failure: said at the top, and the fields the server named marked. */
    errors: FormErrors,
    /** False when the user may only delete this item, not change it. */
    canSave: Boolean,
    onDismiss: () -> Unit,
    /** [place] is null when there was no picker (the item's place is left alone). */
    onSave: (name: String, unit: String?, quantity: Double, lowThreshold: Double, reorderDays: Int?, place: String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var place by remember { mutableStateOf(item.placeChoice()) }
    var name by remember { mutableStateOf(item.name) }
    var unit by remember { mutableStateOf(item.unit.orEmpty()) }
    var quantity by remember { mutableStateOf(formatQuantity(item.quantity)) }
    var low by remember { mutableStateOf(formatQuantity(item.lowThreshold)) }
    var days by remember { mutableStateOf(item.reorderIntervalDays?.toString().orEmpty()) }

    val qty = quantity.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val lowValue = low.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val daysValue = days.toIntOrNull()
    val daysValid = days.isBlank() || daysValue in 1..3650
    // Why each is red: the server's word first, then what's checked here.
    val quantityError = errors["quantity"] ?: if (qty == null) "Enter 0 or more." else null
    val lowError = errors["lowThreshold"] ?: if (lowValue == null) "Enter 0 or more." else null
    val daysError = errors["reorderIntervalDays"] ?: if (!daysValid) "Enter 1 to 3650 days, or leave it blank." else null
    val placeError = PLACE_KEYS.firstNotNullOfOrNull { errors[it] }

    EditorDialog(
        title = if (canSave) "Edit item" else "Item",
        saving = saving,
        saveEnabled = canSave && name.isNotBlank() && qty != null && lowValue != null && daysValid,
        onDismiss = onDismiss,
        onSave = { onSave(name.trim(), unit.trim().ifEmpty { null }, qty!!, lowValue!!, daysValue, place.takeIf { places != null }) },
        deleteLabel = "Delete item",
        onDelete = onDelete,
        error = errors.message,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; errors.clear("name") },
            label = { Text("Name") },
            singleLine = true,
            isError = errors["name"] != null,
            supportingText = errors["name"]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = unit,
            onValueChange = { unit = it; errors.clear("unit") },
            label = { Text("Unit") },
            placeholder = { Text("e.g. rolls, lbs") },
            singleLine = true,
            isError = errors["unit"] != null,
            supportingText = errors["unit"]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it; errors.clear("quantity") },
            label = { Text("Quantity on hand") },
            singleLine = true,
            isError = quantityError != null,
            supportingText = quantityError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = low,
            onValueChange = { low = it; errors.clear("lowThreshold") },
            label = { Text("Low-stock threshold") },
            supportingText = { Text(lowError ?: "Flagged as low at or below this amount.") },
            singleLine = true,
            isError = lowError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = days,
            onValueChange = { days = it.filter(Char::isDigit).take(4); errors.clear("reorderIntervalDays") },
            label = { Text("Reorder every (days)") },
            supportingText = { Text(daysError ?: "Optional. Drives the “runs out” forecast.") },
            singleLine = true,
            isError = daysError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (places != null) {
            DropdownField(
                "Kept in",
                places.choices(none = "Not set"),
                place,
                { place = it; PLACE_KEYS.forEach(errors::clear) },
                error = placeError,
            )
        }
    }
}

/** The keys the server reports an item's place under. */
private val PLACE_KEYS = listOf("roomId", "floorId")
