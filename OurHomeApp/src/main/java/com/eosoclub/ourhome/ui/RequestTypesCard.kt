package com.eosoclub.ourhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.DecisionModuleSettings
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.REQUEST_ROLES
import com.eosoclub.ourhome.data.RequestModuleSettings

// Settings → Request types (head only), as on the website
// (components/settings/request-types-card.tsx): which kinds of request the
// household can make and how each one works. Saved as a whole.

private const val LABEL_MAX = 40
private const val NOTE_MAX = 300

private val ROLE_NAMES = mapOf(
    "head" to "Head of House",
    "manager" to "Manager",
    "adult" to "Adult",
    "teen" to "Teen",
    "child" to "Child",
    "guest" to "Guest",
)

private val MEDIA_KINDS = listOf("movie" to "Movies", "tv" to "TV shows")

private fun <T> List<T>.toggled(value: T, on: Boolean): List<T> =
    if (on) (if (value in this) this else this + value) else this - value

/** [saved]: what the server has; the card's edits start from it again after each save. */
@Composable
internal fun RequestTypesCard(
    saved: RequestModuleSettings,
    members: List<Member>,
    busy: Boolean,
    errors: FormErrors,
    onSave: (RequestModuleSettings) -> Unit,
) {
    CollapsibleCard(
        "Request types",
        "Which kinds of request the household can make, and how each one works.",
        group = true,
    ) {
        key(saved) { RequestTypesForm(saved, members, busy, errors, onSave) }
    }
}

@Composable
private fun RequestTypesForm(
    saved: RequestModuleSettings,
    members: List<Member>,
    busy: Boolean,
    errors: FormErrors,
    onSave: (RequestModuleSettings) -> Unit,
) {
    var media by remember { mutableStateOf(saved.media) }
    var maintenance by remember { mutableStateOf(saved.maintenance) }
    var purchase by remember { mutableStateOf(saved.purchase) }
    var permission by remember { mutableStateOf(saved.permission) }
    // "Only the people I pick" is on while the list has anyone in it; empty means everyone.
    var mediaChosen by remember { mutableStateOf(saved.media.handlerIds.isNotEmpty()) }
    var maintenanceChosen by remember { mutableStateOf(saved.maintenance.assigneeIds.isNotEmpty()) }
    var purchaseChosen by remember { mutableStateOf(saved.purchase.handlerIds.isNotEmpty()) }
    var permissionChosen by remember { mutableStateOf(saved.permission.handlerIds.isNotEmpty()) }
    val missingDeciders = (purchase.enabled && purchaseChosen && purchase.handlerIds.isEmpty()) ||
        (permission.enabled && permissionChosen && permission.handlerIds.isEmpty())

    val handlerIds = if (mediaChosen) media.handlerIds else emptyList()
    val assigneeIds = if (maintenanceChosen) maintenance.assigneeIds else emptyList()
    val askable = if (maintenanceChosen) members.filter { it.id in maintenance.assigneeIds } else members
    // The default person has to be someone who can be asked.
    val defaultAssigneeId = maintenance.defaultAssigneeId?.takeIf { id -> askable.any { it.id == id } }
    val missingHandlers = media.enabled && mediaChosen && handlerIds.isEmpty()
    val missingAssignees = maintenance.enabled && maintenanceChosen && assigneeIds.isEmpty()
    val noMediaKinds = media.enabled && media.mediaTypes.isEmpty()

    errors.message?.let { FieldError(it) }

    val mediaProblem = missingHandlers || noMediaKinds
    SectionCard(
        "Media",
        "Movies and TV shows to get. Whoever approves them marks each one added.",
        badge = { StateBadge(media.enabled, mediaProblem) },
        foldable = true,
        openWhen = mediaProblem,
    ) {
        CheckRow("On", media.enabled) { media = media.copy(enabled = it) }
        if (media.enabled) {
            CommonFields(
                defaultLabel = "Media",
                label = media.label,
                note = media.note,
                askRoles = media.askRoles,
                onLabel = { media = media.copy(label = it) },
                onNote = { media = media.copy(note = it) },
                onRoles = { media = media.copy(askRoles = it) },
            )
            FieldLabel("What can be asked for")
            FlowRow {
                MEDIA_KINDS.forEach { (kind, name) ->
                    CheckRow(name, kind in media.mediaTypes) { on ->
                        media = media.copy(mediaTypes = MEDIA_KINDS.map { it.first }.filter { k -> k in media.mediaTypes.toggled(kind, on) })
                    }
                }
            }
            FieldError(if (noMediaKinds) "Pick at least one, or turn Media off." else null)
            DropdownField(
                "Who marks them added",
                listOf("switch" to "Everyone with Requests “Approve”", "chosen" to "Only the people I pick"),
                if (mediaChosen) "chosen" else "switch",
                { mediaChosen = it == "chosen" },
            )
            if (mediaChosen) {
                PeoplePicker(members, media.handlerIds) { media = media.copy(handlerIds = it) }
                FieldError(if (missingHandlers) "Pick at least one person." else null)
            } else {
                Hint("Set in Household → Permissions. Out of the box: you and Managers.")
            }
            CheckRow("They can decline a request, with a reason", media.allowDecline) { media = media.copy(allowDecline = it) }
        }
    }

    // The server's word on the default person counts too, so a failed save shows where.
    val maintenanceProblem = missingAssignees || errors["maintenance.defaultAssigneeId"] != null
    SectionCard(
        "Maintenance",
        "Something to fix or do around the house. The person asked accepts it, then marks it done.",
        badge = { StateBadge(maintenance.enabled, maintenanceProblem) },
        foldable = true,
        openWhen = maintenanceProblem,
    ) {
        CheckRow("On", maintenance.enabled) { maintenance = maintenance.copy(enabled = it) }
        if (maintenance.enabled) {
            CommonFields(
                defaultLabel = "Maintenance",
                label = maintenance.label,
                note = maintenance.note,
                askRoles = maintenance.askRoles,
                onLabel = { maintenance = maintenance.copy(label = it) },
                onNote = { maintenance = maintenance.copy(note = it) },
                onRoles = { maintenance = maintenance.copy(askRoles = it) },
            )
            DropdownField(
                "Who can be asked",
                listOf("anyone" to "Anyone in the household", "chosen" to "Only the people I pick"),
                if (maintenanceChosen) "chosen" else "anyone",
                { maintenanceChosen = it == "chosen" },
            )
            if (maintenanceChosen) {
                PeoplePicker(members, maintenance.assigneeIds) { maintenance = maintenance.copy(assigneeIds = it) }
                FieldError(if (missingAssignees) "Pick at least one person." else null)
            }
            DropdownField(
                "Picked to start with",
                listOf("" to "Nobody, they choose") + askable.map { it.id to it.name },
                defaultAssigneeId.orEmpty(),
                { maintenance = maintenance.copy(defaultAssigneeId = it.ifEmpty { null }) },
                error = errors["maintenance.defaultAssigneeId"],
            )
            Hint("They can still pick someone else. Never filled in for that person's own requests.")
            CheckRow("Accepting needs a done-by date", maintenance.requireDueDate) {
                maintenance = maintenance.copy(requireDueDate = it)
            }
            CheckRow("The person asked can decline, with a reason", maintenance.allowDecline) {
                maintenance = maintenance.copy(allowDecline = it)
            }
        }
    }

    DecisionSection(
        title = "Purchase",
        description = "Something someone would like bought. Whoever decides says yes or no, and can put it on a shopping list.",
        members = members,
        value = purchase,
        chosen = purchaseChosen,
        onChange = { purchase = it },
        onChosenChange = { purchaseChosen = it },
    )

    DecisionSection(
        title = "Permission",
        description = "Asking to do something, like a sleepover or a new app. Whoever decides says yes or no.",
        members = members,
        value = permission,
        chosen = permissionChosen,
        onChange = { permission = it },
        onChosenChange = { permissionChosen = it },
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(
            onClick = {
                onSave(
                    RequestModuleSettings(
                        // Unticking both kinds is only allowed with Media off: keep what it had then.
                        media = media.copy(
                            handlerIds = handlerIds,
                            mediaTypes = media.mediaTypes.ifEmpty { saved.media.mediaTypes },
                        ),
                        maintenance = maintenance.copy(assigneeIds = assigneeIds, defaultAssigneeId = defaultAssigneeId),
                        purchase = purchase.copy(handlerIds = if (purchaseChosen) purchase.handlerIds else emptyList()),
                        permission = permission.copy(handlerIds = if (permissionChosen) permission.handlerIds else emptyList()),
                    ),
                )
            },
            enabled = !busy && !missingHandlers && !missingAssignees && !noMediaKinds && !missingDeciders,
        ) { Text("Save") }
    }
}

/**
 * Purchase or Permission: on, the common fields, and who decides - the
 * asker's supervisors first if ticked, then the Approve switch or chosen
 * people. Declining is built in.
 */
@Composable
private fun DecisionSection(
    title: String,
    description: String,
    members: List<Member>,
    value: DecisionModuleSettings,
    chosen: Boolean,
    onChange: (DecisionModuleSettings) -> Unit,
    onChosenChange: (Boolean) -> Unit,
) {
    val missing = value.enabled && chosen && value.handlerIds.isEmpty()
    SectionCard(
        title,
        description,
        badge = { StateBadge(value.enabled, missing) },
        foldable = true,
        openWhen = missing,
    ) {
        CheckRow("On", value.enabled) { onChange(value.copy(enabled = it)) }
        if (value.enabled) {
            CommonFields(
                defaultLabel = title,
                label = value.label,
                note = value.note,
                askRoles = value.askRoles,
                onLabel = { onChange(value.copy(label = it)) },
                onNote = { onChange(value.copy(note = it)) },
                onRoles = { onChange(value.copy(askRoles = it)) },
            )
            CheckRow("The asker's supervisors decide, when they have any", value.bySupervisors) {
                onChange(value.copy(bySupervisors = it))
            }
            Hint(
                "A child's or teen's supervisors are set on the Household tab. Only they decide that child's " +
                    "request; the head and managers don't, unless they're one of them.",
            )
            DropdownField(
                if (value.bySupervisors) "Who decides for everyone else" else "Who decides",
                listOf("switch" to "Everyone with Requests “Approve”", "chosen" to "Only the people I pick"),
                if (chosen) "chosen" else "switch",
                { onChosenChange(it == "chosen") },
            )
            if (chosen) {
                PeoplePicker(members, value.handlerIds) { onChange(value.copy(handlerIds = it)) }
                FieldError(if (missing) "Pick at least one person." else null)
                Hint("They can always say no, with a reason.")
            } else {
                Hint("Set in Household → Permissions. Out of the box: you and Managers. They can always say no, with a reason.")
            }
        }
    }
}

/** Name, note on the form and who may ask: what every request type has. */
@Composable
private fun CommonFields(
    defaultLabel: String,
    label: String?,
    note: String?,
    askRoles: List<String>,
    onLabel: (String) -> Unit,
    onNote: (String) -> Unit,
    onRoles: (List<String>) -> Unit,
) {
    OutlinedTextField(
        value = label.orEmpty(),
        onValueChange = { onLabel(it.take(LABEL_MAX)) },
        label = { Text("Name") },
        placeholder = { Text(defaultLabel) },
        supportingText = { Text("Shown on the Requests page. Leave empty for the usual name.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = note.orEmpty(),
        onValueChange = { onNote(it.take(NOTE_MAX)) },
        label = { Text("Note on the form (optional)") },
        supportingText = { Text("Shown when someone picks this type.") },
        modifier = Modifier.fillMaxWidth(),
    )
    FieldLabel("Who can ask")
    FlowRow {
        REQUEST_ROLES.forEach { role ->
            CheckRow(ROLE_NAMES[role] ?: role, role in askRoles) { on ->
                // In the server's order.
                onRoles(REQUEST_ROLES.filter { it in askRoles.toggled(role, on) })
            }
        }
    }
    Hint("They also need Requests “Add” in Household → Permissions.")
}

/** Beside a section's title, so a folded section still shows whether it's on, or that it needs fixing before Save. */
@Composable
private fun StateBadge(enabled: Boolean, problem: Boolean) {
    val (text, color) = when {
        problem -> "Needs fixing" to MaterialTheme.colorScheme.error
        enabled -> "On" to MaterialTheme.colorScheme.primary
        else -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** People to tick, for "Only the people I pick". */
@Composable
private fun PeoplePicker(members: List<Member>, selected: List<String>, onChange: (List<String>) -> Unit) {
    FlowRow {
        members.forEach { m -> CheckRow(m.name, m.id in selected) { on -> onChange(selected.toggled(m.id, on)) } }
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
