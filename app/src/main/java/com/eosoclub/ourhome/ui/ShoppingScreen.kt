package com.eosoclub.ourhome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Switch
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.KeyboardType
import com.eosoclub.ourhome.data.Permission
import com.eosoclub.ourhome.data.can
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.ShoppingItem
import com.eosoclub.ourhome.data.ShoppingList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ShoppingViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val lists: List<ShoppingList> = emptyList(),
        val selectedId: String? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val editing: ShoppingItem? = null,
        val saving: Boolean = false,
    ) {
        val selected: ShoppingList? get() = lists.firstOrNull { it.id == selectedId } ?: lists.firstOrNull()
    }

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.update {
            try {
                it.copy(lists = api.shoppingLists(), loading = false, error = null)
            } catch (e: Exception) {
                it.copy(loading = false, error = e.message)
            }
        }
    }

    fun select(listId: String) = state.update { it.copy(selectedId = listId) }

    fun add(name: String) = viewModelScope.launch {
        val list = state.value.selected ?: return@launch
        try {
            val item = api.addShoppingItem(list.id, name, 1)
            editItems(list.id) { it + item }
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't add item")
        }
    }

    /** Optimistic check-off; reverts if the server refuses. */
    fun toggle(item: ShoppingItem) = viewModelScope.launch {
        val target = !item.purchased
        editItems(item.listId) { items -> items.map { if (it.id == item.id) it.copy(purchased = target) else it } }
        try {
            api.setPurchased(item.id, target)
        } catch (e: Exception) {
            editItems(item.listId) { items -> items.map { if (it.id == item.id) item else it } }
            _messages.send(e.message ?: "Couldn't update item")
        }
    }

    fun startEdit(item: ShoppingItem) = state.update { it.copy(editing = item) }

    fun cancelEdit() = state.update { it.copy(editing = null) }

    fun save(item: ShoppingItem, name: String, quantity: Int, priority: String, notes: String?, recurring: Boolean) =
        viewModelScope.launch {
            state.update { it.copy(saving = true) }
            try {
                val updated = api.updateShoppingItem(item.id, name, quantity, priority, notes, recurring)
                editItems(item.listId) { items -> items.map { if (it.id == item.id) updated else it } }
                state.update { it.copy(editing = null) }
            } catch (e: Exception) {
                _messages.send(e.message ?: "Couldn't save item")
            } finally {
                state.update { it.copy(saving = false) }
            }
        }

    fun delete(item: ShoppingItem) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        try {
            api.deleteShoppingItem(item.id)
            editItems(item.listId) { items -> items.filterNot { it.id == item.id } }
            state.update { it.copy(editing = null) }
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't delete item")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    /** Server deletes bought one-off items and un-checks recurring ones; reload to match. */
    fun clearBought() = viewModelScope.launch {
        val list = state.value.selected ?: return@launch
        try {
            api.clearBought(list.id)
            refresh()
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't clear bought items")
        }
    }

    private fun editItems(listId: String, transform: (List<ShoppingItem>) -> List<ShoppingItem>) =
        state.update { s ->
            s.copy(lists = s.lists.map { l ->
                if (l.id != listId) l
                else transform(l.items).let { items ->
                    l.copy(items = items, openCount = items.count { !it.purchased }, purchasedCount = items.count { it.purchased })
                }
            })
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(api: ApiClient, role: String?, showMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = viewModel { ShoppingViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val canWrite = can(role, Permission.ShoppingWrite)

    state.editing?.let { item ->
        ShoppingItemEditor(
            item = item,
            saving = state.saving,
            onDismiss = vm::cancelEdit,
            onSave = { name, qty, priority, notes, recurring -> vm.save(item, name, qty, priority, notes, recurring) },
            onDelete = { vm.delete(item) },
        )
    }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    Column(modifier.fillMaxSize()) {
        val selected = state.selected
        if (state.lists.size > 1 && selected != null) {
            PrimaryScrollableTabRow(selectedTabIndex = state.lists.indexOf(selected), edgePadding = 16.dp) {
                state.lists.forEach { list ->
                    Tab(
                        selected = list.id == selected.id,
                        onClick = { vm.select(list.id) },
                        text = { Text(if (list.openCount > 0) "${list.name} (${list.openCount})" else list.name) },
                    )
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = state.loading && state.lists.isNotEmpty(),
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.loading && state.lists.isEmpty() -> Centered { CircularProgressIndicator() }
                state.error != null && state.lists.isEmpty() -> Centered {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                }
                selected == null -> Centered { Text("No shopping lists yet. Create one on the website.") }
                else -> ListContent(selected, vm, canWrite)
            }
        }
    }
}

@Composable
private fun ListContent(list: ShoppingList, vm: ShoppingViewModel, canWrite: Boolean) {
    val open = list.items.filter { !it.purchased }
    val bought = list.items.filter { it.purchased }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (canWrite) item(key = "add") { AddItemRow(onAdd = vm::add) }
        items(open, key = { it.id }) { ItemRow(it, vm, canWrite) }
        if (bought.isNotEmpty()) {
            item(key = "bought-header") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Bought (${bought.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (canWrite) TextButton(onClick = { vm.clearBought() }) { Text("Clear bought") }
                }
            }
            items(bought, key = { it.id }) { ItemRow(it, vm, canWrite) }
        }
    }
}

@Composable
private fun AddItemRow(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text.trim())
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Add an item") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(onClick = ::submit, enabled = text.isNotBlank()) { Icon(Icons.Filled.Add, "Add item") }
        },
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    )
}

@Composable
private fun ItemRow(item: ShoppingItem, vm: ShoppingViewModel, canWrite: Boolean) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = canWrite) { vm.toggle(item) }.padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.purchased, onCheckedChange = { vm.toggle(item) }, enabled = canWrite)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.purchased) TextDecoration.LineThrough else null,
                color = if (item.purchased) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val meta = listOfNotNull(
                item.notes?.takeIf { it.isNotBlank() },
                "Recurring".takeIf { item.recurring },
                item.priority.takeIf { it == "high" }?.let { "High priority" },
            )
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (canWrite) {
            IconButton(onClick = { vm.startEdit(item) }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${item.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val SHOPPING_PRIORITIES = listOf("low", "medium", "high")

@Composable
private fun ShoppingItemEditor(
    item: ShoppingItem,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, quantity: Int, priority: String, notes: String?, recurring: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var quantity by remember { mutableStateOf(item.quantity.toString()) }
    var priority by remember { mutableStateOf(item.priority) }
    var notes by remember { mutableStateOf(item.notes.orEmpty()) }
    var recurring by remember { mutableStateOf(item.recurring) }
    val qty = quantity.toIntOrNull()?.takeIf { it in 1..999 }

    EditorDialog(
        title = "Edit item",
        saving = saving,
        saveEnabled = name.isNotBlank() && qty != null,
        onDismiss = onDismiss,
        onSave = { onSave(name.trim(), qty!!, priority, notes.trim().ifEmpty { null }, recurring) },
        deleteLabel = "Delete item",
        onDelete = onDelete,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it.filter(Char::isDigit).take(3) },
            label = { Text("Quantity") },
            singleLine = true,
            isError = qty == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel("Priority")
        ChoiceChips(SHOPPING_PRIORITIES, priority) { priority = it }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recurring", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "“Clear bought” un-checks it instead of removing it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = recurring, onCheckedChange = { recurring = it })
        }
    }
}
