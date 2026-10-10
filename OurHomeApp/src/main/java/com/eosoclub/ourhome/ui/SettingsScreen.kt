package com.eosoclub.ourhome.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.CategoryAdmin
import com.eosoclub.ourhome.data.Features
import com.eosoclub.ourhome.data.Floor
import com.eosoclub.ourhome.data.Integration
import com.eosoclub.ourhome.data.InventoryItem
import com.eosoclub.ourhome.data.NfcTag
import com.eosoclub.ourhome.data.PaperlessStatus
import com.eosoclub.ourhome.data.Places
import com.eosoclub.ourhome.data.PointsSettings
import com.eosoclub.ourhome.data.Room
import com.eosoclub.ourhome.data.moveId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The web's Settings page (head + managers): points rate and calendar (head),
 * rooms & floors, categories, Paperless bill import, Home Assistant tokens,
 * NFC tag mappings, and the household export (head). Each card loads on its
 * own, so one failing (an older server, a turned-off feature) leaves the rest.
 */
class SettingsViewModel(private val api: ApiClient, private val isHead: Boolean) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        /** The first card that failed to load, if any. */
        val error: String? = null,
        val points: PointsSettings? = null,
        val places: Places? = null,
        val categories: List<CategoryAdmin>? = null,
        val paperless: PaperlessStatus? = null,
        val integrations: List<Integration>? = null,
        val tags: List<NfcTag>? = null,
        val items: List<InventoryItem> = emptyList(),
        /** A save or delete is in flight. */
        val busy: Boolean = false,
        val syncing: Boolean = false,
        /** A just-created Home Assistant token; shown once until dismissed. */
        val newToken: String? = null,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh(features: Features) = viewModelScope.launch {
        state.update { it.copy(loading = true, error = null) }
        val failed: (UiState, String?) -> UiState = { s, e -> if (s.error == null) s.copy(error = e ?: "Couldn't load") else s }
        coroutineScope {
            if (isHead) launch { state.load({ api.pointsSettings() }, { s, v -> s.copy(points = v) }, failed) }
            if (features.tasks) launch { state.load({ api.places() }, { s, v -> s.copy(places = v) }, failed) }
            launch { state.load({ api.allCategories() }, { s, v -> s.copy(categories = v) }, failed) }
            if (features.bills) launch { state.load({ api.paperlessStatus() }, { s, v -> s.copy(paperless = v) }, failed) }
            launch { state.load({ api.integrations() }, { s, v -> s.copy(integrations = v) }, failed) }
            if (features.inventory) {
                launch { state.load({ api.nfcTags() }, { s, v -> s.copy(tags = v) }, failed) }
                launch { state.load({ api.inventory() }, { s, v -> s.copy(items = v.sortedBy { it.name.lowercase() }) }, failed) }
            }
        }
        state.update { it.copy(loading = false) }
    }

    /** Runs one change; [onDone] (close the dialog, …) only when it worked. */
    private fun act(success: String?, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch { run(success, onDone, block) }
    }

    private suspend fun run(success: String?, onDone: () -> Unit, block: suspend () -> Unit) {
        state.update { it.copy(busy = true) }
        try {
            block()
            onDone()
            success?.let { _messages.send(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.send(e.message ?: "Something went wrong")
        } finally {
            state.update { it.copy(busy = false) }
        }
    }

    fun savePoints(minutesPerPoint: Double, timezone: String, weekStartsOn: Int) = act("Points settings saved") {
        val saved = api.savePointsSettings(minutesPerPoint, timezone, weekStartsOn)
        state.update { it.copy(points = saved) }
    }

    // Every places call answers with the whole new list.
    private fun places(success: String?, onDone: () -> Unit = {}, call: suspend () -> Places) = act(success, onDone) {
        val next = call()
        state.update { it.copy(places = next) }
    }

    fun saveFloor(id: String?, name: String, onDone: () -> Unit) = places(null, onDone) { api.saveFloor(id, name) }
    fun saveRoom(id: String?, name: String, floorId: String?, onDone: () -> Unit) =
        places(null, onDone) { api.saveRoom(id, name, floorId) }
    fun deletePlace(kind: String, id: String, onDone: () -> Unit) =
        places(if (kind == "floor") "Floor removed" else "Room removed", onDone) { api.deletePlace(kind, id) }
    fun movePlace(kind: String, ids: List<String>, id: String, direction: Int) =
        places(null) { api.reorderPlaces(kind, moveId(ids, id, direction)) }

    fun saveCategory(id: String?, name: String, kind: String, color: String, icon: String?, onDone: () -> Unit) =
        act(null, onDone) {
            api.saveCategory(id, name, kind, color, icon)
            val next = api.allCategories()
            state.update { it.copy(categories = next) }
        }

    fun deleteCategory(id: String, onDone: () -> Unit) = act("Category deleted", onDone) {
        api.deleteCategory(id)
        state.update { s -> s.copy(categories = s.categories?.filterNot { it.id == id }) }
    }

    fun syncPaperless() = viewModelScope.launch {
        state.update { it.copy(syncing = true) }
        try {
            val next = api.syncPaperless()
            state.update { it.copy(paperless = next) }
            _messages.send("Checked Paperless")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't check Paperless")
            // The failure is recorded as the last error: show it.
            runCatching { api.paperlessStatus() }.onSuccess { s -> state.update { it.copy(paperless = s) } }
        } finally {
            state.update { it.copy(syncing = false) }
        }
    }

    fun savePaperless(url: String, publicUrl: String, token: String?, onDone: () -> Unit) =
        act("Paperless connected. Bills changed from now on will be imported.", onDone) {
            val next = api.savePaperless(url, publicUrl, token)
            state.update { it.copy(paperless = next) }
        }

    fun disconnectPaperless() = act("Paperless disconnected") {
        val next = api.disconnectPaperless()
        state.update { it.copy(paperless = next) }
    }

    fun createToken(name: String, onDone: () -> Unit) = act(null, onDone) {
        val created = api.createIntegration(name)
        val next = api.integrations()
        state.update { it.copy(newToken = created.token, integrations = next) }
    }

    fun dismissToken() = state.update { it.copy(newToken = null) }

    fun revokeToken(id: String) = act("Token revoked") {
        api.revokeIntegration(id)
        state.update { s -> s.copy(integrations = s.integrations?.filterNot { it.id == id }) }
    }

    fun mapTag(tagId: String, label: String, itemId: String, represents: String, onDone: () -> Unit) =
        act(null, onDone) {
            api.registerNfcTag(tagId, label, itemId, represents)
            val next = api.nfcTags()
            state.update { it.copy(tags = next) }
        }

    fun deleteTag(id: String) = act("Tag removed") {
        api.deleteNfcTag(id)
        state.update { s -> s.copy(tags = s.tags?.filterNot { it.id == id }) }
    }

    fun export(resolver: ContentResolver, uri: Uri) = act("Export saved") {
        val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("Couldn't open that file")
        out.use { api.downloadExport(it) }
    }
}

private val CATEGORY_KINDS = listOf("task" to "Tasks", "shopping" to "Shopping", "inventory" to "Inventory", "general" to "General")

private val NFC_REPRESENTS = listOf(
    "location" to "Location",
    "action" to "Action",
    "consumable" to "Consumable",
    "maintenance_point" to "Maintenance point",
    "process" to "Process",
)

private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

// Quick picks for a category colour; any "#rrggbb" can be typed too.
private val SWATCHES = listOf(
    "#ef4444", "#f97316", "#f59e0b", "#eab308", "#84cc16", "#22c55e", "#14b8a6", "#06b6d4",
    "#38bdf8", "#3b82f6", "#6366f1", "#8b5cf6", "#a855f7", "#ec4899", "#f43f5e", "#64748b",
)

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

private fun hexColor(hex: String?): Color? =
    hex?.takeIf { HEX_COLOR.matches(it) }?.let { Color(0xFF000000 or it.substring(1).toLong(16)) }

@Composable
fun SettingsScreen(
    api: ApiClient,
    baseUrl: String,
    isHead: Boolean,
    features: Features,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { SettingsViewModel(api, isHead) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(features) { vm.refresh(features) }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.categories != null,
        onRefresh = { vm.refresh(features) },
        modifier = modifier.fillMaxSize(),
    ) {
        if (LoadState(state.loading, state.error, state.categories == null, "", { vm.refresh(features) })) {
            return@PullToRefreshBox
        }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            // Keyed on the saved values so the form resets to them after a save.
            state.points?.let { p -> key(p) { PointsCard(p, state.busy, vm::savePoints) } }
            // Rooms are where tasks are done: hidden with Tasks.
            if (features.tasks) state.places?.let { PlacesCard(it, state.busy, vm) }
            state.categories?.let { CategoriesCard(it, state.busy, vm) }
            // Paperless feeds Bills and NFC tags drive Inventory: hidden with them.
            if (features.bills) state.paperless?.let { PaperlessCard(it, state.busy, state.syncing, vm) }
            state.integrations?.let { HomeAssistantCard(it, state.newToken, "$baseUrl/api/webhooks/nfc", state.busy, vm) }
            if (features.inventory) state.tags?.let { NfcTagsCard(it, state.items, state.busy, vm) }
            if (isHead) ExportCard(state.busy, vm)
        }
    }
}

// --- Task points (head) -------------------------------------------------------

@Composable
private fun PointsCard(saved: PointsSettings, busy: Boolean, onSave: (Double, String, Int) -> Unit) {
    var rate by remember { mutableStateOf(formatQuantity(saved.minutesPerPoint)) }
    var timezone by remember { mutableStateOf(saved.timezone) }
    var weekStartsOn by remember { mutableStateOf(saved.weekStartsOn) }
    val minutes = rate.toDoubleOrNull()?.takeIf { it > 0 }
    val phoneZone = ZoneId.systemDefault().id

    SectionCard("Task points", "The time→points rate, and the calendar points stats and task cycles use.") {
        OutlinedTextField(
            value = rate,
            onValueChange = { rate = it },
            label = { Text("Minutes per point") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                Text(
                    (minutes?.let { "45 min of work = ${formatQuantity(Math.round(45 / it * 100) / 100.0)} pts. " } ?: "") +
                        "Only new calculations use it; existing tasks keep their points.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = timezone,
            onValueChange = { timezone = it },
            label = { Text("Time zone") },
            placeholder = { Text("America/Chicago") },
            singleLine = true,
            supportingText = { Text("Days, weeks and task cycles start at midnight here.") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (timezone.trim() != phoneZone) {
            TextButton(onClick = { timezone = phoneZone }) { Text("Use this phone's ($phoneZone)") }
        }
        DropdownField(
            "Weeks start on",
            WEEKDAYS.mapIndexed { i, d -> i.toString() to d },
            weekStartsOn.toString(),
            { weekStartsOn = it.toInt() },
        )
        Button(
            onClick = { minutes?.let { onSave(it, timezone.trim(), weekStartsOn) } },
            enabled = minutes != null && timezone.isNotBlank() && !busy,
            modifier = Modifier.align(Alignment.End),
        ) { Text("Save") }
    }
}

// --- Rooms & floors -------------------------------------------------------------

private sealed interface PlaceEdit {
    data class FloorEdit(val floor: Floor?) : PlaceEdit
    data class RoomEdit(val room: Room?, val floorId: String?) : PlaceEdit
}

@Composable
private fun PlacesCard(places: Places, busy: Boolean, vm: SettingsViewModel) {
    var editing by remember { mutableStateOf<PlaceEdit?>(null) }
    val floorIds = places.floors.map { it.id }.toSet()
    // A room whose floor is gone counts as "not on a floor", as on the web.
    fun roomsOn(floorId: String?) = places.rooms.filter { (it.floorId?.takeIf { id -> id in floorIds }) == floorId }
    val loose = roomsOn(null)

    SectionCard(
        "Rooms & floors",
        "Where tasks are done. Tasks can belong to a room or a whole floor; the Tasks tab groups them in this order. Tap one to rename or remove it.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = PlaceEdit.FloorEdit(null) }) { Text("Add floor") }
            Button(onClick = { editing = PlaceEdit.RoomEdit(null, places.floors.firstOrNull()?.id) }) { Text("Add room") }
        }
        if (places.isEmpty) {
            Text(
                "No rooms yet. Add rooms (Kitchen, Garage…), and floors if your home has more than one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val floorOrder = places.floors.map { it.id }
        places.floors.forEach { f ->
            Bordered {
                ListRow(
                    title = f.name,
                    bold = true,
                    onClick = { editing = PlaceEdit.FloorEdit(f) },
                ) {
                    MoveButtons(f.name, floorOrder, f.id, busy) { d -> vm.movePlace("floor", floorOrder, f.id, d) }
                    IconButton(onClick = { editing = PlaceEdit.RoomEdit(null, f.id) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add a room to ${f.name}")
                    }
                }
                RoomRows(roomsOn(f.id), busy, vm) { editing = PlaceEdit.RoomEdit(it, it.floorId) }
            }
        }
        if (loose.isNotEmpty()) {
            Bordered {
                if (places.floors.isNotEmpty()) {
                    Text(
                        "NOT ON A FLOOR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    )
                }
                RoomRows(loose, busy, vm) { editing = PlaceEdit.RoomEdit(it, null) }
            }
        }
    }

    editing?.let { edit ->
        PlaceEditor(edit, places.floors, busy, vm, onClose = { editing = null })
    }
}

@Composable
private fun RoomRows(rooms: List<Room>, busy: Boolean, vm: SettingsViewModel, onEdit: (Room) -> Unit) {
    val order = rooms.map { it.id }
    rooms.forEach { r ->
        HorizontalDivider()
        ListRow(title = r.name, indent = true, onClick = { onEdit(r) }) {
            MoveButtons(r.name, order, r.id, busy) { d -> vm.movePlace("room", order, r.id, d) }
        }
    }
}

@Composable
private fun PlaceEditor(edit: PlaceEdit, floors: List<Floor>, busy: Boolean, vm: SettingsViewModel, onClose: () -> Unit) {
    val existingName = when (edit) {
        is PlaceEdit.FloorEdit -> edit.floor?.name
        is PlaceEdit.RoomEdit -> edit.room?.name
    }
    val existingId = when (edit) {
        is PlaceEdit.FloorEdit -> edit.floor?.id
        is PlaceEdit.RoomEdit -> edit.room?.id
    }
    val noun = if (edit is PlaceEdit.FloorEdit) "floor" else "room"
    var name by remember { mutableStateOf(existingName ?: "") }
    var floorId by remember { mutableStateOf((edit as? PlaceEdit.RoomEdit)?.floorId ?: "") }

    EditorDialog(
        title = if (existingId != null) "Edit $noun" else "New $noun",
        saving = busy,
        saveEnabled = name.isNotBlank(),
        onDismiss = onClose,
        onSave = {
            if (edit is PlaceEdit.FloorEdit) vm.saveFloor(existingId, name.trim(), onClose)
            else vm.saveRoom(existingId, name.trim(), floorId.ifEmpty { null }, onClose)
        },
        deleteLabel = "Remove $noun",
        onDelete = existingId?.let { id -> { vm.deletePlace(noun, id, onClose) } },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Name") },
            placeholder = { Text(if (edit is PlaceEdit.FloorEdit) "Upstairs" else "Kitchen") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (edit is PlaceEdit.RoomEdit && floors.isNotEmpty()) {
            DropdownField("Floor", listOf("" to "Not on a floor") + floors.map { it.id to it.name }, floorId, { floorId = it })
        }
        if (existingId != null) {
            Text(
                if (edit is PlaceEdit.FloorEdit) {
                    "Removing a floor keeps its rooms (on no floor), and its whole-floor tasks move to the whole house."
                } else {
                    "Removing a room moves its tasks to its floor (or the whole house). No tasks are deleted."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Categories -------------------------------------------------------------------

@Composable
private fun CategoriesCard(categories: List<CategoryAdmin>, busy: Boolean, vm: SettingsViewModel) {
    // null = closed; a category with a blank id = new.
    var editing by remember { mutableStateOf<CategoryAdmin?>(null) }

    SectionCard("Categories", "Labels with a colour for tasks, shopping, and inventory. Tap one to edit it.") {
        Button(onClick = { editing = CategoryAdmin(id = "", name = "", kind = "task", color = "#38bdf8") }) { Text("New category") }
        if (categories.isEmpty()) {
            Text("No categories yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CATEGORY_KINDS.forEach { (kind, label) ->
            val group = categories.filter { it.kind == kind }
            if (group.isNotEmpty()) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Bordered {
                    group.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider()
                        ListRow(
                            title = listOfNotNull(c.icon?.takeIf { it.isNotBlank() }, c.name).joinToString(" "),
                            leading = { ColorDot(hexColor(c.color)) },
                            onClick = { editing = c },
                        )
                    }
                }
            }
        }
    }

    editing?.let { c -> key(c.id) { CategoryEditor(c, busy, vm, onClose = { editing = null }) } }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditor(category: CategoryAdmin, busy: Boolean, vm: SettingsViewModel, onClose: () -> Unit) {
    val isNew = category.id.isEmpty()
    var name by remember { mutableStateOf(category.name) }
    var kind by remember { mutableStateOf(category.kind) }
    var color by remember { mutableStateOf(category.color ?: "#38bdf8") }
    var icon by remember { mutableStateOf(category.icon ?: "") }
    val colorOk = HEX_COLOR.matches(color.trim())

    EditorDialog(
        title = if (isNew) "New category" else "Edit category",
        saving = busy,
        saveEnabled = name.isNotBlank() && colorOk,
        onDismiss = onClose,
        onSave = {
            vm.saveCategory(category.id.ifEmpty { null }, name.trim(), kind, color.trim().lowercase(), icon.trim().ifEmpty { null }, onClose)
        },
        deleteLabel = "Delete category",
        onDelete = if (isNew) null else ({ vm.deleteCategory(category.id, onClose) }),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Name") },
            placeholder = { Text("e.g. Cleaning") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownField("Used for", CATEGORY_KINDS, kind, { kind = it })
        OutlinedTextField(
            value = icon,
            onValueChange = { icon = it.take(40) },
            label = { Text("Icon / emoji (optional)") },
            placeholder = { Text("e.g. 🧹") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel("Colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SWATCHES.forEach { hex ->
                val selected = color.trim().equals(hex, ignoreCase = true)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(hexColor(hex)!!)
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable { color = hex },
                )
            }
        }
        OutlinedTextField(
            value = color,
            onValueChange = { color = it.take(7) },
            label = { Text("Hex") },
            singleLine = true,
            isError = !colorOk,
            supportingText = { if (!colorOk) Text("Use a hex colour like #38bdf8") },
            leadingIcon = { ColorDot(hexColor(color.trim())) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --- Paperless bill import ---------------------------------------------------------

private val OUTCOME_LABELS = mapOf(
    "created" to "new",
    "updated" to "updated",
    "linked" to "payments recorded",
    "duplicate" to "already imported",
    "skipped" to "skipped",
)

@Composable
private fun PaperlessCard(status: PaperlessStatus, busy: Boolean, syncing: Boolean, vm: SettingsViewModel) {
    var editing by remember { mutableStateOf(false) }
    val conn = status.connection
    val uriHandler = LocalUriHandler.current

    SectionCard(
        "Paperless bill import",
        "Documents tagged bill or bill-payment in Paperless-ngx become bills and payments here, checked every 15 minutes. " +
            "Payments are matched to an unpaid bill; one with no bill to pay is listed below instead.",
    ) {
        when {
            conn == null && !status.canEdit -> Text(
                "Not connected. Your Head of House can connect your household's Paperless here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            editing || conn == null -> PaperlessForm(
                status,
                busy,
                onSave = { url, publicUrl, token -> vm.savePaperless(url, publicUrl, token) { editing = false } },
                onCancel = if (conn != null) ({ editing = false }) else null,
            )
            conn != null -> Bordered {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Connected to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(conn.url, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (conn.source == "server") {
                        Text(
                            "From the server's settings. Connect your own to replace it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status.canEdit) {
                        Row {
                            TextButton(onClick = { editing = true }) {
                                Text(if (conn.source == "server") "Connect your own" else "Change")
                            }
                            if (conn.source == "household") {
                                TextButton(onClick = vm::disconnectPaperless, enabled = !busy) { Text("Disconnect") }
                            }
                        }
                    }
                }
            }
        }

        if (status.configured) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (status.lastError != null) "Last check failed" else "Connected",
                        fontWeight = FontWeight.SemiBold,
                        color = if (status.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        status.lastRunAt?.let { "Last checked ${relativeTime(it)}" } ?: "Not checked yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.since?.let {
                        Text(
                            "Importing documents changed since ${formatDate(it)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = vm::syncPaperless, enabled = !syncing) {
                    if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Check now")
                }
            }
            status.lastError?.let { err ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
            status.lastResult?.let { result ->
                val counts = result.imported.filterValues { it > 0 }.map { (k, n) -> "$n ${OUTCOME_LABELS[k] ?: k}" }
                Text(
                    "Last check: ${result.checked} document(s) changed" + counts.joinToString("") { " · $it" } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.skipped.isNotEmpty()) {
                    Text("SKIPPED RECENTLY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Bordered {
                        result.skipped.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider()
                            // Only web links open; anything else isn't a Paperless page.
                            val link = s.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                            ListRow(title = s.title, subtitle = s.reason, onClick = link?.let { url -> { uriHandler.openUri(url) } })
                        }
                    }
                    Text(
                        "Fix the document in Paperless (add the Amount, finish the review, or add the bill first) and it is picked up on the next check.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Head only. The token is never shown again once saved; blank keeps it. */
@Composable
private fun PaperlessForm(
    status: PaperlessStatus,
    busy: Boolean,
    onSave: (String, String, String?) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val own = status.connection?.takeIf { it.source == "household" }
    var url by remember { mutableStateOf(own?.url ?: "") }
    var publicUrl by remember { mutableStateOf(own?.publicUrl ?: "") }
    var token by remember { mutableStateOf("") }
    val canSave = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url.trim()) &&
        (token.isNotBlank() || own != null) && !busy

    Bordered {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (own != null) "Change your Paperless" else "Connect your Paperless", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Paperless address") },
                placeholder = { Text(if (status.privateNetworkAllowed) "http://paperless:8000" else "https://paperless.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text(
                        if (status.privateNetworkAllowed) "How this server reaches it: a Docker or home-network address works."
                        else "Must be a public internet address. To use one on the server's own network, ask the server admin to allow it.",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = publicUrl,
                onValueChange = { publicUrl = it },
                label = { Text("Address you open it at (optional)") },
                placeholder = { Text("https://paperless.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("For \"Open in Paperless\" links on skipped documents.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API token") },
                placeholder = { if (own != null) Text("Leave blank to keep the saved token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = {
                    Text(
                        "Of a read-only Paperless user that can see the bill / bill-payment tags and the Amount field. " +
                            "Stored encrypted. Saving checks the connection first.",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onCancel?.let { TextButton(onClick = it) { Text("Cancel") } }
                Button(onClick = { onSave(url.trim(), publicUrl.trim(), token.trim().ifEmpty { null }) }, enabled = canSave) {
                    Text(if (own != null) "Save" else "Connect")
                }
            }
        }
    }
}

// --- Home Assistant ------------------------------------------------------------------

@Composable
private fun HomeAssistantCard(
    integrations: List<Integration>,
    newToken: String?,
    webhookUrl: String,
    busy: Boolean,
    vm: SettingsViewModel,
) {
    var name by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf<Integration?>(null) }

    SectionCard(
        "Home Assistant connection",
        "Create a token, then have Home Assistant POST scans to the address below with an Authorization: Bearer header.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                webhookUrl,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            CopyButton(webhookUrl, sensitive = false)
        }
        newToken?.let { token ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Copy this token now — it won't be shown again:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row {
                        CopyButton(token, sensitive = true)
                        TextButton(onClick = vm::dismissToken) { Text("Done") }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                label = { Text("Connection name") },
                placeholder = { Text("e.g. Home Assistant (LAN)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.createToken(name.trim()) { name = "" } }, enabled = name.isNotBlank() && !busy) { Text("Create") }
        }
        if (integrations.isEmpty()) {
            Text(
                "No connections yet. Create a token to let Home Assistant post scans here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Bordered {
                integrations.forEachIndexed { i, integration ->
                    if (i > 0) HorizontalDivider()
                    ListRow(
                        title = integration.name,
                        subtitle = (if (integration.active) "Active" else "Inactive") + " · created ${formatDate(integration.createdAt)}",
                    ) {
                        IconButton(onClick = { revoking = integration }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, contentDescription = "Revoke ${integration.name}")
                        }
                    }
                }
            }
        }
    }

    revoking?.let { integration ->
        ConfirmDialog(
            title = "Revoke token?",
            text = "Revoke “${integration.name}”? This breaks every automation using it.",
            confirmLabel = "Revoke",
            onConfirm = { vm.revokeToken(integration.id) },
            onDismiss = { revoking = null },
        )
    }
}

/**
 * Copies [text]. A [sensitive] clip (a token) is hidden from Android's
 * clipboard preview, so it isn't shown on screen or to keyboard suggestions.
 */
@Composable
private fun CopyButton(text: String, sensitive: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(text) { mutableStateOf(false) }
    TextButton(onClick = {
        val clip = ClipData.newPlainText(if (sensitive) "Token" else "Address", text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        scope.launch {
            clipboard.setClipEntry(ClipEntry(clip))
            copied = true
        }
    }) { Text(if (copied) "Copied" else "Copy") }
}

// --- NFC tags ------------------------------------------------------------------------

@Composable
private fun NfcTagsCard(tags: List<NfcTag>, items: List<InventoryItem>, busy: Boolean, vm: SettingsViewModel) {
    var tagId by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var itemId by remember(items) { mutableStateOf(items.firstOrNull()?.id ?: "") }
    var represents by remember { mutableStateOf("consumable") }
    var deleting by remember { mutableStateOf<NfcTag?>(null) }

    SectionCard(
        "NFC tags",
        "Bind a tag id (e.g. one Home Assistant scans) to an inventory item. To set up a physical tag, scan it with this phone instead.",
    ) {
        if (items.isEmpty()) {
            Text(
                "Add inventory items first, then map tags to them here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = tagId,
                onValueChange = { tagId = it },
                label = { Text("Tag id") },
                placeholder = { Text("e.g. pantry_coffee") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(120) },
                label = { Text("Label") },
                placeholder = { Text("e.g. Coffee shelf") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField("Inventory item", items.map { it.id to it.name }, itemId, { itemId = it })
            DropdownField("Represents", NFC_REPRESENTS, represents, { represents = it })
            Button(
                onClick = {
                    vm.mapTag(tagId.trim(), label.trim(), itemId, represents) {
                        tagId = ""
                        label = ""
                    }
                },
                enabled = tagId.isNotBlank() && label.isNotBlank() && itemId.isNotEmpty() && !busy,
                modifier = Modifier.align(Alignment.End),
            ) { Text("Map tag") }
        }
        if (tags.isEmpty()) {
            Text("No tags mapped yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Bordered {
                tags.forEachIndexed { i, tag ->
                    if (i > 0) HorizontalDivider()
                    ListRow(
                        title = tag.label,
                        subtitle = "${tag.tagId} → ${tag.item?.name ?: "unbound"} · " +
                            (NFC_REPRESENTS.firstOrNull { it.first == tag.represents }?.second ?: tag.represents),
                    ) {
                        IconButton(onClick = { deleting = tag }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${tag.label}")
                        }
                    }
                }
            }
        }
    }

    deleting?.let { tag ->
        ConfirmDialog(
            title = "Remove tag?",
            text = "Remove tag “${tag.label}”? Scans for it will be ignored.",
            confirmLabel = "Remove",
            onConfirm = { vm.deleteTag(tag.id) },
            onDismiss = { deleting = null },
        )
    }
}

// --- Export (head) ----------------------------------------------------------------------

@Composable
private fun ExportCard(busy: Boolean, vm: SettingsViewModel) {
    val resolver = LocalContext.current.contentResolver
    // The system "save as" screen: the user picks where the file goes.
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export(resolver, it) }
    }
    SectionCard(
        "Export household data",
        "A copy of everything your household has stored here — members, tasks and points, bills, shopping, stock, " +
            "requests, calendar and activity — as one JSON file. Passwords and connection tokens are never included.",
    ) {
        OutlinedButton(onClick = { saveAs.launch("ourhome-export-${LocalDate.now()}.json") }, enabled = !busy) {
            Text("Download export")
        }
    }
}

// --- Shared bits ------------------------------------------------------------------------

@Composable
private fun Bordered(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    ) { content() }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String? = null,
    bold: Boolean = false,
    indent: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = if (indent) 28.dp else 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                title,
                fontWeight = if (bold) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun MoveButtons(label: String, ids: List<String>, id: String, busy: Boolean, onMove: (Int) -> Unit) {
    val i = ids.indexOf(id)
    IconButton(onClick = { onMove(-1) }, enabled = i > 0 && !busy) {
        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $label up")
    }
    IconButton(onClick = { onMove(1) }, enabled = i < ids.size - 1 && !busy) {
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $label down")
    }
}

@Composable
private fun ColorDot(color: Color?) {
    Box(
        Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color ?: Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
