package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.InventoryItem
import com.eosoclub.ourhome.data.NfcLookup
import com.eosoclub.ourhome.data.ShoppingList
import com.eosoclub.ourhome.nfc.NfcScans
import com.eosoclub.ourhome.nfc.TagFormat
import com.eosoclub.ourhome.nfc.TagRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val OPEN = "open"
private const val NOTIFY = "notify"

/** Drives the sheet shown when a tag is scanned (see NfcScans / HomeScreen). */
class ScanViewModel(private val api: ApiClient) : ViewModel() {
    sealed interface Phase {
        data object Closed : Phase
        data class Loading(val tagId: String) : Phase
        /** Lookup done: [lookup].item is null for a new (or unbound) tag. */
        data class Ready(val lookup: NfcLookup) : Phase
        data class Applied(val lookup: NfcLookup, val before: Double, val after: InventoryItem) : Phase
        data class Failed(val tagId: String, val message: String) : Phase

        val scannedTagId: String?
            get() = when (this) {
                Closed -> null
                is Loading -> tagId
                is Ready -> lookup.tagId
                is Applied -> lookup.tagId
                is Failed -> tagId
            }
    }

    data class UiState(
        val phase: Phase = Phase.Closed,
        /** How the scanned tag is currently written — decides the "Write tag" offer. */
        val format: TagFormat = TagFormat.OurHome,
        val busy: Boolean = false,
        val error: String? = null,
        /** Showing the setup form (new tag, or "Change item"). */
        val settingUp: Boolean = false,
        /** Waiting for the tag to be held to the phone again to write it. */
        val writing: Boolean = false,
        val writeMessage: String? = null,
        /** For "link to an existing item"; null until loaded. */
        val items: List<InventoryItem>? = null,
        /** For the quick-notification "Add to list" target; null until loaded. */
        val lists: List<ShoppingList>? = null,
    )

    val state = MutableStateFlow(UiState())

    init {
        // Writes happen in MainActivity's reader-mode callback; pick up the result.
        viewModelScope.launch {
            NfcScans.writeResult.filterNotNull().collect { result ->
                val current = state.value
                if (!current.writing || result.tagId != current.phase.scannedTagId) return@collect
                state.update {
                    it.copy(
                        writing = false,
                        writeMessage = result.message,
                        format = if (result.ok) TagFormat.OurHome else it.format,
                    )
                }
            }
        }
    }

    fun open(ref: TagRef) {
        NfcScans.cancelWrite()
        state.value = UiState(phase = Phase.Loading(ref.tagId), format = ref.format)
        viewModelScope.launch {
            try {
                val lookup = api.nfcLookup(ref.tagId)
                val needsSetup = lookup.item == null
                state.update { it.copy(phase = Phase.Ready(lookup), settingUp = needsSetup) }
                loadLists()
                if (needsSetup) loadItems()
            } catch (e: Exception) {
                state.update { it.copy(phase = Phase.Failed(ref.tagId, e.message ?: "Couldn't look up that tag")) }
            }
        }
    }

    fun close() {
        NfcScans.cancelWrite()
        state.value = UiState()
    }

    fun startSetup() {
        state.update { it.copy(settingUp = true, error = null) }
        loadItems()
    }

    fun cancelSetup() = state.update { it.copy(settingUp = false, error = null) }

    private fun loadItems() {
        if (state.value.items != null) return
        viewModelScope.launch {
            runCatching { api.inventory() }.onSuccess { list ->
                state.update { it.copy(items = list.sortedBy { i -> i.name.lowercase() }) }
            }
        }
    }

    private fun loadLists() {
        if (state.value.lists != null) return
        viewModelScope.launch {
            runCatching { api.shoppingLists() }.onSuccess { lists -> state.update { it.copy(lists = lists) } }
        }
    }

    /** Arms MainActivity's reader mode to write this tag on the next tap. */
    fun startWrite(packageName: String) {
        val tagId = state.value.phase.scannedTagId ?: return
        NfcScans.armWrite(tagId, packageName)
        state.update { it.copy(writing = true, writeMessage = null) }
    }

    fun cancelWrite() {
        NfcScans.cancelWrite()
        state.update { it.copy(writing = false) }
    }

    /** Applies a signed amount to the tag's item. */
    fun apply(amount: Double) {
        val ready = state.value.phase as? Phase.Ready ?: return
        val before = ready.lookup.item?.quantity ?: return
        runBusy {
            val after = api.nfcScan(ready.lookup.tagId, amount)
            state.update { it.copy(phase = Phase.Applied(ready.lookup, before, after)) }
        }
    }

    fun saveScanSettings(scanAction: String, shoppingListId: String?) {
        val tagId = (state.value.phase as? Phase.Ready)?.lookup?.tagId ?: return
        runBusy {
            val lookup = api.nfcTagSettings(tagId, scanAction, shoppingListId)
            state.update { it.copy(phase = Phase.Ready(lookup)) }
        }
    }

    fun setupExisting(itemId: String, scanAction: String, shoppingListId: String?) = setup { tagId ->
        api.nfcSetup(tagId, itemId = itemId, scanAction = scanAction, shoppingListId = shoppingListId)
    }

    fun setupNew(
        name: String,
        unit: String?,
        quantity: Double,
        lowThreshold: Double,
        scanAction: String,
        shoppingListId: String?,
    ) = setup { tagId ->
        api.nfcSetup(
            tagId,
            newItemName = name,
            newItemUnit = unit,
            newItemQuantity = quantity,
            newItemLowThreshold = lowThreshold,
            scanAction = scanAction,
            shoppingListId = shoppingListId,
        )
    }

    private fun setup(call: suspend (String) -> NfcLookup) {
        val tagId = (state.value.phase as? Phase.Ready)?.lookup?.tagId ?: return
        runBusy {
            val lookup = call(tagId)
            state.update { it.copy(phase = Phase.Ready(lookup), settingUp = false) }
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: "Something went wrong") }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(vm: ScanViewModel, canWrite: Boolean, onFinished: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.phase == ScanViewModel.Phase.Closed) return
    val close = {
        vm.close()
        onFinished()
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tag scanned", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (val phase = state.phase) {
                is ScanViewModel.Phase.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is ScanViewModel.Phase.Failed -> {
                    Text(phase.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.open(TagRef(phase.tagId, state.format)) }) { Text("Try again") }
                }
                is ScanViewModel.Phase.Applied -> AppliedContent(phase, onDone = close)
                is ScanViewModel.Phase.Ready -> {
                    when {
                        !canWrite && phase.lookup.item == null ->
                            Text("This tag isn't set up yet. Ask someone who manages inventory to set it up.")
                        state.settingUp && canWrite -> SetupContent(phase.lookup, state, vm)
                        else -> BoundContent(phase.lookup, canWrite, state, vm)
                    }
                    if (canWrite && !state.settingUp && phase.lookup.item != null) WriteTagCard(state, vm)
                }
                ScanViewModel.Phase.Closed -> Unit
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.phase.scannedTagId?.let {
                Text("Tag $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ItemHeader(item: InventoryItem) {
    Text(item.name, style = MaterialTheme.typography.headlineSmall)
    Text(
        listOfNotNull(
            "${formatQuantity(item.quantity)} ${item.unit.orEmpty()}".trim() + " on hand",
            "Low".takeIf { item.isLow },
        ).joinToString(" · "),
        color = if (item.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BoundContent(lookup: NfcLookup, canWrite: Boolean, state: ScanViewModel.UiState, vm: ScanViewModel) {
    val item = lookup.item!!
    val busy = state.busy
    ItemHeader(item)
    if (!canWrite) {
        Text("You can view stock but not change it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = { vm.apply(-1.0) }, enabled = !busy && item.quantity >= 1, modifier = Modifier.weight(1f)) {
            Text("−1  Use one", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(onClick = { vm.apply(1.0) }, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text("+1  Add one", style = MaterialTheme.typography.titleMedium)
        }
    }
    var custom by remember { mutableStateOf("") }
    val amount = custom.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    OutlinedTextField(
        value = custom,
        onValueChange = { custom = it },
        label = { Text("Other amount") },
        placeholder = { Text("e.g. 2 or 0.5") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { vm.apply(-amount!!) }, enabled = !busy && amount != null, modifier = Modifier.weight(1f)) {
            Text("Use")
        }
        OutlinedButton(onClick = { vm.apply(amount!!) }, enabled = !busy && amount != null, modifier = Modifier.weight(1f)) {
            Text("Restock")
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    // Keyed on the saved values so the controls reset after a save.
    androidx.compose.runtime.key(lookup.tag?.scanAction, lookup.tag?.shoppingListId) {
        var action by remember { mutableStateOf(lookup.tag?.scanAction ?: OPEN) }
        var listId by remember { mutableStateOf(lookup.tag?.shoppingListId.orEmpty()) }
        ScanActionChooser(action, { action = it }, listId, { listId = it }, state.lists)
        val dirty = action != (lookup.tag?.scanAction ?: OPEN) || listId != lookup.tag?.shoppingListId.orEmpty()
        if (dirty) {
            Button(onClick = { vm.saveScanSettings(action, listId.ifEmpty { null }) }, enabled = !busy) {
                Text(if (busy) "Saving…" else "Save")
            }
        }
    }
    TextButton(onClick = { vm.startSetup() }, enabled = !busy) { Text("Change item for this tag") }
}

/** "When this tag is scanned with the app closed" — open the app, or a quick notification. */
@Composable
private fun ScanActionChooser(
    action: String,
    onAction: (String) -> Unit,
    listId: String,
    onListId: (String) -> Unit,
    lists: List<ShoppingList>?,
) {
    Text("When scanned with the app closed", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = action == OPEN, onClick = { onAction(OPEN) }, label = { Text("Open the app") })
        FilterChip(selected = action == NOTIFY, onClick = { onAction(NOTIFY) }, label = { Text("Quick notification") })
    }
    Text(
        if (action == NOTIFY) {
            "No app opens — you get a notification with −1, type an amount, or add to the shopping list. Each button asks you to unlock first."
        } else {
            "Opens this screen."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (action == NOTIFY && lists != null && lists.isNotEmpty()) {
        DropdownField(
            "“Add to list” uses",
            listOf("" to "First grocery list") + lists.map { it.id to it.name },
            listId,
            onListId,
        )
    }
}

/** Offers to (re)write the tag so scanning it always opens Our Home. */
@Composable
private fun WriteTagCard(state: ScanViewModel.UiState, vm: ScanViewModel) {
    val context = LocalContext.current
    if (state.format == TagFormat.OurHome && state.writeMessage == null && !state.writing) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                state.writing -> {
                    Text("Hold the tag to the back of your phone again…", style = MaterialTheme.typography.titleSmall)
                    Text("Keep it still until this changes.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.cancelWrite() }) { Text("Cancel") }
                }
                state.format == TagFormat.OurHome -> Text(state.writeMessage.orEmpty())
                else -> {
                    Text(
                        if (state.format == TagFormat.HomeAssistant) {
                            "This tag still opens Home Assistant."
                        } else {
                            "This tag isn't written for Our Home yet."
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Write it so scanning always opens Our Home — no app chooser. It keeps the same id, so the item link stays.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.writeMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { vm.startWrite(context.packageName) }) { Text("Write tag for Our Home") }
                }
            }
        }
    }
}

@Composable
private fun AppliedContent(phase: ScanViewModel.Phase.Applied, onDone: () -> Unit) {
    val item = phase.after
    val unit = item.unit?.let { " $it" }.orEmpty()
    Text(item.name, style = MaterialTheme.typography.headlineSmall)
    Text(
        "${formatQuantity(phase.before)} → ${formatQuantity(item.quantity)}$unit",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (item.isLow) Text("Now running low.", color = MaterialTheme.colorScheme.error)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun SetupContent(lookup: NfcLookup, state: ScanViewModel.UiState, vm: ScanViewModel) {
    var createNew by remember { mutableStateOf(true) }
    var action by remember { mutableStateOf(lookup.tag?.scanAction ?: OPEN) }
    var listId by remember { mutableStateOf(lookup.tag?.shoppingListId.orEmpty()) }
    Text(
        if (lookup.item == null) "New tag — what does it track?" else "Change what this tag tracks",
        style = MaterialTheme.typography.titleLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = createNew, onClick = { createNew = true }, label = { Text("New item") })
        FilterChip(selected = !createNew, onClick = { createNew = false }, label = { Text("Existing item") })
    }
    if (createNew) {
        var name by remember { mutableStateOf("") }
        var unit by remember { mutableStateOf("") }
        var quantity by remember { mutableStateOf("") }
        var low by remember { mutableStateOf("") }
        val qty = quantity.ifBlank { "0" }.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
        val lowValue = low.ifBlank { "0" }.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Item name") },
            placeholder = { Text("e.g. Coffee beans") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = unit,
            onValueChange = { unit = it },
            label = { Text("Unit (optional)") },
            placeholder = { Text("e.g. bags, rolls") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text("On hand now") },
                placeholder = { Text("0") },
                isError = qty == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = low,
                onValueChange = { low = it },
                label = { Text("Low at") },
                placeholder = { Text("0") },
                isError = lowValue == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        ScanActionChooser(action, { action = it }, listId, { listId = it }, state.lists)
        Button(
            onClick = { vm.setupNew(name.trim(), unit.trim().ifEmpty { null }, qty!!, lowValue!!, action, listId.ifEmpty { null }) },
            enabled = !state.busy && name.isNotBlank() && qty != null && lowValue != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "Saving…" else "Create item & link tag") }
    } else {
        val items = state.items
        var itemId by remember { mutableStateOf(lookup.item?.id.orEmpty()) }
        if (items == null) {
            Text("Loading items…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DropdownField(
                "Inventory item",
                items.map { it.id to it.name },
                itemId,
                { itemId = it },
                placeholder = "Choose an item…",
            )
            ScanActionChooser(action, { action = it }, listId, { listId = it }, state.lists)
            Button(
                onClick = { vm.setupExisting(itemId, action, listId.ifEmpty { null }) },
                enabled = !state.busy && itemId.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "Saving…" else "Link tag") }
        }
    }
    if (lookup.item != null) {
        TextButton(onClick = { vm.cancelSetup() }, enabled = !state.busy) { Text("Cancel") }
    }
}
