package com.eosoclub.ourhome.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.CategoryRef
import com.eosoclub.ourhome.data.LinkPreview
import com.eosoclub.ourhome.data.ShoppingItemFields
import com.eosoclub.ourhome.data.ProductLink
import com.eosoclub.ourhome.data.productLink
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import java.math.BigDecimal
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.eosoclub.ourhome.data.PageAccess
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
        /** The New item form is open, starting from this name (the add bar's text). */
        val creating: String? = null,
        /** The household's shopping categories, for the item forms. */
        val categories: List<CategoryRef> = emptyList(),
        val saving: Boolean = false,
        // The list editor: creating a new list, or renaming/deleting [editingList].
        val creatingList: Boolean = false,
        val editingList: ShoppingList? = null,
    ) {
        val selected: ShoppingList? get() = lists.firstOrNull { it.id == selectedId } ?: lists.firstOrNull()
    }

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load({ api.shoppingLists() }, { s, v -> s.copy(lists = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
        // Only the forms' Category choice needs these; without them it offers None.
        runCatching { api.categories("shopping") }.onSuccess { c -> state.update { it.copy(categories = c) } }
    }

    fun select(listId: String) = state.update { it.copy(selectedId = listId) }

    /** The open editor's last save failure (item or list editor): why, and which fields to mark. */
    val form = FormErrors()

    /** The add bar's last failure: its name field is marked, and the text stays to fix. */
    val addForm = FormErrors()

    /** [onAdded] runs only when it worked, so a refused name stays in the box to fix. */
    fun add(name: String, onAdded: () -> Unit) = viewModelScope.launch {
        val list = state.value.selected ?: return@launch
        addForm.clear()
        try {
            val item = api.addShoppingItem(list.id, name, 1)
            editItems(list.id) { it + item }
            onAdded()
        } catch (e: Exception) {
            addForm.fail(e, "Couldn't add item")
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

    fun startEdit(item: ShoppingItem) {
        form.clear()
        state.update { it.copy(editing = item) }
    }

    /** Opens the New item form ("More options" under the add bar), starting from [name]. */
    fun startCreate(name: String) {
        form.clear()
        state.update { it.copy(creating = name) }
    }

    fun cancelEdit() {
        form.clear()
        state.update { it.copy(editing = null, creating = null) }
    }

    /** [onCreated] runs only when it worked (it empties the add bar). */
    fun create(fields: ShoppingItemFields, onCreated: () -> Unit) = viewModelScope.launch {
        val list = state.value.selected ?: return@launch
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            val item = api.addShoppingItem(list.id, fields)
            editItems(list.id) { it + item }
            state.update { it.copy(creating = null) }
            onCreated()
        } catch (e: Exception) {
            // Shown in the form, which sits over the snackbar.
            form.fail(e, "Couldn't add item")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }

    /** "Fetch details" in the item forms. */
    suspend fun linkPreview(url: String): LinkPreview = api.shoppingLinkPreview(url)

    // Pictures already loaded, so scrolling the list back doesn't fetch them again.
    private val pictures = mutableMapOf<String, ImageBitmap>()

    /** The product picture a link's details found, or null if it can't be loaded. */
    suspend fun picture(url: String): ImageBitmap? = pictures[url] ?: api.remoteImage(url)?.let { bytes ->
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }?.also { pictures[url] = it }

    fun save(item: ShoppingItem, fields: ShoppingItemFields) =
        viewModelScope.launch {
            state.update { it.copy(saving = true) }
            form.clear()
            try {
                val updated = api.updateShoppingItem(item.id, fields)
                editItems(item.listId) { items -> items.map { if (it.id == item.id) updated else it } }
                state.update { it.copy(editing = null) }
            } catch (e: Exception) {
                // Shown in the editor, which sits over the snackbar.
                form.fail(e, "Couldn't save item")
            } finally {
                state.update { it.copy(saving = false) }
            }
        }

    fun delete(item: ShoppingItem) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            api.deleteShoppingItem(item.id)
            editItems(item.listId) { items -> items.filterNot { it.id == item.id } }
            state.update { it.copy(editing = null) }
        } catch (e: Exception) {
            form.fail(e, "Couldn't delete item")
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

    // --- Lists (the "Shopping lists" row of the head's permissions grid) ---

    fun startCreateList() {
        form.clear()
        state.update { it.copy(creatingList = true, editingList = null) }
    }

    fun startEditList(list: ShoppingList) {
        form.clear()
        state.update { it.copy(editingList = list, creatingList = false) }
    }

    fun closeListEditor() {
        form.clear()
        state.update { it.copy(creatingList = false, editingList = null) }
    }

    /** Creates the list and switches to it. */
    fun createList(name: String, kind: String) = listAction("Couldn't create list") {
        val list = api.createShoppingList(name, kind)
        state.update { it.copy(lists = it.lists + list, selectedId = list.id) }
    }

    fun renameList(list: ShoppingList, name: String) = listAction("Couldn't rename list") {
        val renamed = api.renameShoppingList(list.id, name)
        // Keep the items we already have; the reply may not carry them.
        state.update { s ->
            s.copy(lists = s.lists.map { if (it.id == list.id) it.copy(name = renamed.name) else it })
        }
    }

    fun deleteList(list: ShoppingList) = listAction("Couldn't delete list") {
        api.deleteShoppingList(list.id)
        state.update { s ->
            s.copy(
                lists = s.lists.filterNot { it.id == list.id },
                selectedId = s.selectedId.takeIf { it != list.id },
            )
        }
        _messages.send("Deleted “${list.name}”")
    }

    private fun listAction(failure: String, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            block()
            closeListEditor()
        } catch (e: Exception) {
            // Shown in the list editor, which sits over the snackbar.
            form.fail(e, failure)
        } finally {
            state.update { it.copy(saving = false) }
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
fun ShoppingScreen(
    api: ApiClient,
    /** Items on the lists ("Shopping items" in the head's permissions grid). */
    access: PageAccess,
    /** The lists themselves: new, rename, delete ("Shopping lists"). */
    listAccess: PageAccess,
    userId: String,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { ShoppingViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.creatingList || state.editingList != null) {
        val list = state.editingList
        ShoppingListEditor(
            list = list,
            saving = state.saving,
            errors = vm.form,
            canSave = list == null || listAccess.canEdit(list.createdById, userId),
            onDismiss = vm::closeListEditor,
            onSave = { name, kind -> if (list == null) vm.createList(name, kind) else vm.renameList(list, name) },
            onDelete = if (list != null && listAccess.canDelete(list.createdById, userId)) ({ vm.deleteList(list) }) else null,
        )
    }

    // Here, not in the add bar, so the New item form can empty it once the item is added.
    var addText by rememberSaveable { mutableStateOf("") }

    state.editing?.let { item ->
        ShoppingItemEditor(
            item = item,
            initialName = item.name,
            categories = state.categories,
            saving = state.saving,
            errors = vm.form,
            canSave = access.canEdit(item.createdById, userId),
            onDismiss = vm::cancelEdit,
            onSave = { fields -> vm.save(item, fields) },
            onDelete = if (access.canDelete(item.createdById, userId)) ({ vm.delete(item) }) else null,
            fetchPreview = vm::linkPreview,
            loadPicture = vm::picture,
        )
    }
    state.creating?.let { name ->
        ShoppingItemEditor(
            item = null,
            initialName = name,
            categories = state.categories,
            saving = state.saving,
            errors = vm.form,
            canSave = access.create,
            onDismiss = vm::cancelEdit,
            onSave = { fields -> vm.create(fields) { addText = "" } },
            onDelete = null,
            fetchPreview = vm::linkPreview,
            loadPicture = vm::picture,
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
        if (selected != null) {
            ListActions(
                list = selected,
                // With one list there are no tabs, so show its name here.
                showName = state.lists.size == 1,
                canCreate = listAccess.create,
                // The editor holds Delete too, so either opens it.
                canOpenEditor = listAccess.canEdit(selected.createdById, userId) ||
                    listAccess.canDelete(selected.createdById, userId),
                onCreate = vm::startCreateList,
                onEdit = { vm.startEditList(selected) },
            )
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
                selected == null -> Centered {
                    Text("No shopping lists yet.")
                    if (listAccess.create) {
                        TextButton(onClick = vm::startCreateList) { Text("Create a list") }
                    }
                }
                else -> ListContent(selected, vm, access, userId, addText, onAddText = { addText = it })
            }
        }
    }
}

@Composable
private fun ListContent(
    list: ShoppingList,
    vm: ShoppingViewModel,
    access: PageAccess,
    userId: String,
    addText: String,
    onAddText: (String) -> Unit,
) {
    val open = list.items.filter { !it.purchased }
    val bought = list.items.filter { it.purchased }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (access.create) item(key = "add") {
            AddItemRow(addText, onAddText, vm.addForm, onAdd = vm::add, onMoreOptions = { vm.startCreate(addText.trim()) })
        }
        items(open, key = { it.id }) { ItemRow(it, vm, access, userId) }
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
                    // Clears only the bought items this user may delete (server-side).
                    if (access.canDeleteAny) TextButton(onClick = { vm.clearBought() }) { Text("Clear bought") }
                }
            }
            items(bought, key = { it.id }) { ItemRow(it, vm, access, userId) }
        }
    }
}

/**
 * The text is cleared only once the item is added, so a refused name stays to fix (marked red).
 * "More options" opens the New item form with every field, starting from the typed name.
 */
@Composable
private fun AddItemRow(
    text: String,
    onText: (String) -> Unit,
    errors: FormErrors,
    onAdd: (String, () -> Unit) -> Unit,
    onMoreOptions: () -> Unit,
) {
    fun submit() {
        if (text.isNotBlank()) onAdd(text.trim()) { onText("") }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { onText(it); errors.clear("name") },
        placeholder = { Text("Add an item") },
        singleLine = true,
        isError = errors["name"] != null,
        supportingText = errors["name"]?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(onClick = ::submit, enabled = text.isNotBlank()) { Icon(Icons.Filled.Add, "Add item") }
        },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    TextButton(onClick = onMoreOptions, modifier = Modifier.padding(start = 4.dp)) { Text("More options") }
}

@Composable
private fun ItemRow(item: ShoppingItem, vm: ShoppingViewModel, access: PageAccess, userId: String) {
    // Ticking bought isn't an edit: any Shopping access will do (as on the server).
    val canToggle = access.any
    // The editor also holds Delete, so either opens it.
    val canOpenEditor = access.canEdit(item.createdById, userId) || access.canDelete(item.createdById, userId)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = canToggle) { vm.toggle(item) }.padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.purchased, onCheckedChange = { vm.toggle(item) }, enabled = canToggle)
        // The picture "Fetch details" found, as on the web's row; nothing until it loads.
        item.imageUrl?.let { url ->
            val picture by produceState<ImageBitmap?>(null, url) { value = vm.picture(url) }
            picture?.let {
                Image(
                    it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.padding(end = 12.dp).size(40.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                )
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                if (item.quantity > 1) "${item.name} ×${item.quantity}" else item.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.purchased) TextDecoration.LineThrough else null,
                color = if (item.purchased) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            // One line under the name, notes last so only they get cut short. Tapping a
            // cut-short line shows all of it (the row's own tap ticks the item off).
            itemDetails(item)?.let { details ->
                var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
                var cutShort by remember(details) { mutableStateOf(false) }
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) cutShort = it.hasVisualOverflow },
                    // Only when there's more to show: a disabled clickable still swallows the
                    // tap, and a short line's tap must tick the row like the rest of it.
                    modifier = if (cutShort || expanded) Modifier.clickable { expanded = !expanded } else Modifier,
                )
            }
            item.url?.takeIf { it.isNotBlank() }?.let { ItemLink(productLink(it)) }
        }
        if (canOpenEditor) {
            IconButton(onClick = { vm.startEdit(item) }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${item.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The item's product link: "View on Amazon" (and the product id) or the shop's address; opens in the browser. */
@Composable
private fun ItemLink(link: ProductLink) {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier.padding(top = 2.dp).clickable { runCatching { uriHandler.openUri(link.href) } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            link.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        link.asin?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "New list" and the selected list's edit button, above its items. */
@Composable
private fun ListActions(
    list: ShoppingList,
    showName: Boolean,
    canCreate: Boolean,
    canOpenEditor: Boolean,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
) {
    if (!showName && !canCreate && !canOpenEditor) return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (showName) list.name else "",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (canOpenEditor) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit list ${list.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (canCreate) {
            TextButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("New list")
            }
        }
    }
}

// The web's SHOPPING_LIST_KINDS / SHOPPING_LIST_KIND_LABELS.
private val SHOPPING_LIST_KINDS = listOf(
    "grocery" to "Grocery",
    "supplies" to "Supplies",
    "hardware" to "Hardware",
    "amazon" to "Amazon",
    "general" to "General",
)

/** New list ([list] null: name + kind) or rename/delete one (kind is fixed once made, as on the web). */
@Composable
private fun ShoppingListEditor(
    list: ShoppingList?,
    saving: Boolean,
    /** The last save failure: said at the top, and the fields the server named marked. */
    errors: FormErrors,
    /** False when the user may only delete this list, not rename it. */
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, kind: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(list?.name.orEmpty()) }
    var kind by remember { mutableStateOf(list?.kind ?: "grocery") }
    EditorDialog(
        title = when {
            list == null -> "New list"
            canSave -> "Edit list"
            else -> "List"
        },
        saving = saving,
        saveEnabled = canSave && name.isNotBlank() && name.trim().length <= 100,
        onDismiss = onDismiss,
        onSave = { onSave(name.trim(), kind) },
        saveLabel = if (list == null) "Create" else "Save",
        deleteLabel = "Delete list",
        onDelete = onDelete,
        error = errors.message,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100); errors.clear("name") },
            label = { Text("Name") },
            singleLine = true,
            enabled = canSave,
            isError = errors["name"] != null,
            supportingText = errors["name"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        if (list == null) {
            DropdownField("Kind", SHOPPING_LIST_KINDS, kind, onSelect = { kind = it; errors.clear("kind") }, error = errors["kind"])
        }
        if (list != null && onDelete != null) {
            Text(
                "Deleting a list removes everything on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SHOPPING_PRIORITIES = listOf("low", "medium", "high")

/** The New item form ([item] null) or an item's editor: every field of the web's forms. */
@Composable
private fun ShoppingItemEditor(
    item: ShoppingItem?,
    /** The item's name, or the add bar's text for a new one. */
    initialName: String,
    categories: List<CategoryRef>,
    saving: Boolean,
    /** The last save failure: said at the top, and the fields the server named marked. */
    errors: FormErrors,
    /** False when the user may only delete this item, not change it. */
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (ShoppingItemFields) -> Unit,
    onDelete: (() -> Unit)?,
    fetchPreview: suspend (String) -> LinkPreview,
    loadPicture: suspend (String) -> ImageBitmap?,
) {
    var name by remember { mutableStateOf(initialName) }
    var quantity by remember { mutableStateOf((item?.quantity ?: 1).toString()) }
    var priority by remember { mutableStateOf(item?.priority ?: "medium") }
    var categoryId by remember { mutableStateOf(item?.category?.id.orEmpty()) }
    var price by remember { mutableStateOf(item?.estimatedPrice?.let(::priceText).orEmpty()) }
    var notes by remember { mutableStateOf(item?.notes.orEmpty()) }
    var url by remember { mutableStateOf(item?.url.orEmpty()) }
    var imageUrl by remember { mutableStateOf(item?.imageUrl) }
    var recurring by remember { mutableStateOf(item?.recurring ?: false) }
    val qty = quantity.toIntOrNull()?.takeIf { it in 1..999 }
    val quantityError = errors["quantity"] ?: if (qty == null) "Enter a number from 1 to 999." else null
    // Blank is fine (no price); anything else must be a price the server takes.
    val priceValue = priceOrNull(price)
    val priceInvalid = price.isNotBlank() && priceValue == null
    val priceError = errors["estimatedPrice"] ?: if (priceInvalid) "Enter a price like 4.99." else null
    // The item's own category stays choosable even if the list didn't load.
    val categoryOptions = listOf("" to "None") +
        (categories + listOfNotNull(item?.category)).distinctBy { it.id }.map { it.id to it.name }

    EditorDialog(
        title = when {
            item == null -> "New item"
            canSave -> "Edit item"
            else -> "Item"
        },
        saving = saving,
        saveEnabled = canSave && name.isNotBlank() && qty != null && !priceInvalid,
        onDismiss = onDismiss,
        onSave = {
            val link = url.trim().ifEmpty { null }
            onSave(
                ShoppingItemFields(
                    name = name.trim(),
                    quantity = qty!!,
                    priority = priority,
                    categoryId = categoryId.ifEmpty { null },
                    estimatedPrice = priceValue,
                    notes = notes.trim().ifEmpty { null },
                    url = link,
                    // A cleared link drops its picture too, as on the web.
                    imageUrl = imageUrl.takeIf { link != null },
                    recurring = recurring,
                ),
            )
        },
        saveLabel = if (item == null) "Add" else "Save",
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
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it.filter(Char::isDigit).take(3); errors.clear("quantity") },
            label = { Text("Quantity") },
            singleLine = true,
            isError = quantityError != null,
            supportingText = quantityError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel("Priority")
        ChoiceChips(SHOPPING_PRIORITIES, priority) { priority = it; errors.clear("priority") }
        FieldError(errors["priority"])
        DropdownField(
            "Category",
            categoryOptions,
            categoryId,
            onSelect = { categoryId = it; errors.clear("categoryId") },
            error = errors["categoryId"],
        )
        OutlinedTextField(
            value = price,
            onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' }.take(10); errors.clear("estimatedPrice") },
            label = { Text("Est. price") },
            placeholder = { Text("0.00") },
            prefix = { Text("$") },
            singleLine = true,
            isError = priceError != null,
            supportingText = priceError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        ProductLinkField(
            url = url,
            imageUrl = imageUrl,
            error = errors["url"] ?: errors["imageUrl"],
            onUrlChange = {
                url = it
                imageUrl = null
                errors.clear("url")
                errors.clear("imageUrl")
            },
            fetchPreview = fetchPreview,
            // Fills only what's still empty, as on the web.
            onPreview = { preview ->
                preview.imageUrl?.let { imageUrl = it }
                if (preview.title != null && name.isBlank()) name = preview.title
                if (preview.price != null && price.isBlank()) price = priceText(preview.price)
            },
            loadPicture = loadPicture,
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; errors.clear("notes") },
            label = { Text("Notes") },
            minLines = 2,
            isError = errors["notes"] != null,
            supportingText = errors["notes"]?.let { { Text(it) } },
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
            Switch(checked = recurring, onCheckedChange = { recurring = it; errors.clear("recurring") })
        }
        FieldError(errors["recurring"])
    }
}

/** A row's line under the name: the price of one, Recurring, High priority, then the notes (last, so only they get cut short). */
internal fun itemDetails(item: ShoppingItem): String? = listOfNotNull(
    item.estimatedPrice?.let { "$" + String.format(Locale.US, "%.2f", it) },
    "Recurring".takeIf { item.recurring },
    "High priority".takeIf { item.priority == "high" },
    item.notes?.takeIf { it.isNotBlank() },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** A price as typed, without trailing zeros: 4.5, 12, 3.99. */
internal fun priceText(price: Double): String = BigDecimal(price.toString()).stripTrailingZeros().toPlainString()

/** The typed price the server takes (0 to 1,000,000), or null for blank or anything else. */
internal fun priceOrNull(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it >= 0 && it <= 1_000_000 }

/**
 * The web's LinkField: a product link, and "Fetch details" to read the page's
 * title, picture and price ([onPreview] decides what to use). A link the server
 * calls invalid is marked red; a page it couldn't read is just a note.
 */
@Composable
private fun ProductLinkField(
    url: String,
    imageUrl: String?,
    /** What the server said is wrong with the link when saving. */
    error: String?,
    onUrlChange: (String) -> Unit,
    fetchPreview: suspend (String) -> LinkPreview,
    onPreview: (LinkPreview) -> Unit,
    loadPicture: suspend (String) -> ImageBitmap?,
) {
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val shownError = error ?: fetchError

    fun fetch() = scope.launch {
        fetching = true
        note = null
        fetchError = null
        try {
            val preview = fetchPreview(url.trim())
            onPreview(preview)
            if (preview.title == null && preview.imageUrl == null && preview.price == null) {
                note = "Couldn't read that link - fill the details in manually."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val linkError = (e as? ApiException)?.fieldErrors?.get("url")
            if (linkError != null) fetchError = linkError else note = e.message ?: "Failed to fetch link details."
        } finally {
            fetching = false
        }
    }

    OutlinedTextField(
        value = url,
        onValueChange = {
            onUrlChange(it)
            note = null
            fetchError = null
        },
        label = { Text("Link") },
        placeholder = { Text("Paste an Amazon (or other) product link") },
        singleLine = true,
        isError = shownError != null,
        supportingText = (shownError ?: note)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { fetch() }, enabled = !fetching && url.isNotBlank()) {
            if (fetching) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
            Text("Fetch details")
        }
        if (imageUrl != null) {
            val picture by produceState<ImageBitmap?>(null, imageUrl) { value = loadPicture(imageUrl) }
            picture?.let {
                Image(
                    it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                )
            }
            Text(
                "Preview image attached",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
