package com.eosoclub.ourhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.MemberAccount
import com.eosoclub.ourhome.data.Permission
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.SessionUser
import com.eosoclub.ourhome.data.assignableRoles
import com.eosoclub.ourhome.data.can
import com.eosoclub.ourhome.data.canManageMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The web's Members page (head + managers): rename the household (head), add
 * members with a temporary password, edit their name / username / email,
 * change roles, reset passwords, hand over Head of House (head) and remove
 * people. The per-page permissions grid stays on the website.
 */
class MembersViewModel(private val session: SessionManager) : ViewModel() {
    private val api = session.api

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val householdName: String? = null,
        val members: List<MemberAccount>? = null,
        /** A save is in flight. */
        val busy: Boolean = false,
        /** Just added: shown with their sign-in name until dismissed. */
        val added: MemberAccount? = null,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true, error = null) }
        try {
            coroutineScope {
                val name = async { runCatching { api.profile().householdName }.getOrNull() }
                val members = api.memberAccounts()
                state.update { it.copy(members = members, householdName = name.await() ?: it.householdName) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(error = e.message ?: "Couldn't load the household") }
        } finally {
            state.update { it.copy(loading = false) }
        }
    }

    /**
     * Runs one change, then re-reads the list; [onDone] (close the dialog, …)
     * only when it worked. [touchesMe]: the change may alter the signed-in
     * user (their own name, or handing over headship), so re-read them too.
     */
    private fun act(success: String?, touchesMe: Boolean = false, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            try {
                block()
                onDone()
                success?.let { _messages.send(it) }
                runCatching { api.memberAccounts() }.onSuccess { m -> state.update { it.copy(members = m) } }
                if (touchesMe) session.refreshUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.message ?: "Something went wrong")
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun rename(name: String, onDone: () -> Unit) = act(null, onDone = onDone) {
        val household = api.renameHousehold(name)
        state.update { it.copy(householdName = household.name) }
        _messages.send("Household renamed to “${household.name}”")
    }

    fun add(name: String, username: String, email: String?, role: String, password: String, onDone: () -> Unit) =
        act(null, onDone = onDone) {
            val member = api.createMember(name, username, email, role, password)
            state.update { it.copy(added = member) }
        }

    fun dismissAdded() = state.update { it.copy(added = null) }

    fun save(member: MemberAccount, name: String?, username: String?, email: String?, isSelf: Boolean, onDone: () -> Unit) =
        act("Saved ${member.name}'s profile", touchesMe = isSelf, onDone = onDone) {
            api.updateMember(member.id, name, username, email)
        }

    fun setRole(member: MemberAccount, role: String) =
        act("Changed ${member.name}'s role to ${ROLE_LABELS[role] ?: role}") { api.setMemberRole(member.id, role) }

    fun resetPassword(member: MemberAccount, password: String, onDone: () -> Unit) =
        act("Password reset for ${member.name}", onDone = onDone) { api.resetMemberPassword(member.id, password) }

    fun makeHead(member: MemberAccount, onDone: () -> Unit) =
        act("${member.name} is now the Head of House", touchesMe = true, onDone = onDone) { api.transferHead(member.id) }

    fun remove(member: MemberAccount, onDone: () -> Unit) =
        act("Removed ${member.name} from the household", onDone = onDone) { api.removeMember(member.id) }
}

/** A readable temporary password (12 letters and digits), as the web generates. */
private fun generatePassword(): String = UUID.randomUUID().toString().replace("-", "").take(12)

private const val NAME_MAX = 80

@Composable
fun MembersScreen(
    session: SessionManager,
    user: SessionUser,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { MembersViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    // By id, so the editor follows the list as it reloads (and closes if they're gone).
    var editingId by remember { mutableStateOf<String?>(null) }
    val canRename = can(user.role, Permission.HouseholdManage)

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.members != null,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (LoadState(state.loading, state.error, state.members == null, "", { vm.refresh() })) {
            return@PullToRefreshBox
        }
        val members = state.members.orEmpty()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.householdName ?: "Your household",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (canRename && state.householdName != null) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename household")
                        }
                    }
                }
                Text(
                    "Add people to your household, set what they can do, and reset passwords. " +
                        "There is one Head of House at a time. What each role may add, edit and delete is set on the website.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (assignableRoles(user.role).isNotEmpty()) {
                Button(onClick = { vm.dismissAdded(); adding = true }) { Text("Add a member") }
            }
            state.added?.let { added ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Added ${added.name}. They sign in with username “${added.username.orEmpty()}” " +
                                "and the temporary password you set, then must change it.",
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton(onClick = vm::dismissAdded, modifier = Modifier.align(Alignment.End)) { Text("Done") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                members.forEachIndexed { i, m ->
                    if (i > 0) HorizontalDivider()
                    val isSelf = m.id == user.id
                    // Anyone you manage, and yourself (your name, username, email).
                    val canOpen = isSelf || canManageMember(user.role, m.role)
                    MemberRow(m, isSelf, onClick = if (canOpen) ({ editingId = m.id }) else null)
                }
            }
        }
    }

    if (renaming) {
        state.householdName?.let { current ->
            RenameDialog(current, state.busy, onSave = { vm.rename(it) { renaming = false } }, onClose = { renaming = false })
        }
    }
    if (adding) {
        AddMemberDialog(user.role, state.busy, vm, onClose = { adding = false })
    }
    editingId?.let { id ->
        val member = state.members?.firstOrNull { it.id == id }
        if (member != null) {
            MemberEditor(member, user, state.busy, vm, onClose = { editingId = null })
        } else {
            LaunchedEffect(id) { editingId = null }
        }
    }
}

@Composable
private fun MemberRow(m: MemberAccount, isSelf: Boolean, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                m.name.take(1).uppercase(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                m.name + if (isSelf) " (you)" else "",
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                m.username?.let { "@$it" } ?: m.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                ROLE_LABELS[m.role] ?: m.role,
                style = MaterialTheme.typography.labelMedium,
                color = if (m.role == "head") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (m.mustChangePassword) {
                Text("Temp password", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, busy: Boolean, onSave: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Rename household") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(NAME_MAX) },
                label = { Text("Household name") },
                singleLine = true,
                supportingText = { Text("1-80 characters.") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(trimmed) }, enabled = trimmed.isNotEmpty() && trimmed != current && !busy) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** A temporary password field with Generate and Copy, shared by add and reset. */
@Composable
private fun TempPasswordField(password: String, label: String, onChange: (String) -> Unit) {
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD
    OutlinedTextField(
        value = password,
        onValueChange = { onChange(it.take(128)) },
        label = { Text(label) },
        singleLine = true,
        isError = tooShort,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = { Text("At least $MIN_PASSWORD characters. They choose their own when they next sign in.") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(generatePassword()) }) { Text("Generate") }
        CopyButton(password, sensitive = true, clipLabel = "Password", enabled = password.isNotEmpty())
    }
}

@Composable
private fun AddMemberDialog(actorRole: String?, busy: Boolean, vm: MembersViewModel, onClose: () -> Unit) {
    val roles = assignableRoles(actorRole)
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(if ("adult" in roles) "adult" else roles.first()) }
    var password by remember { mutableStateOf("") }
    val usernameOk = USERNAME_RE.matches(username.trim())
    val emailOk = email.isBlank() || EMAIL_RE.matches(email.trim())

    EditorDialog(
        title = "Add a member",
        saving = busy,
        saveEnabled = name.isNotBlank() && usernameOk && emailOk && password.length >= MIN_PASSWORD,
        onDismiss = onClose,
        onSave = { vm.add(name.trim(), username.trim(), email.trim().ifEmpty { null }, role, password, onClose) },
        saveLabel = "Add",
    ) {
        Text(
            "They sign in with a username and the temporary password you set, then choose their own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(NAME_MAX) },
            label = { Text("Name") },
            placeholder = { Text("e.g. Jordan") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        UsernameField(username, usernameOk) { username = it }
        EmailField(email, emailOk, optional = true) { email = it }
        DropdownField("Role", roles.map { it to (ROLE_LABELS[it] ?: it) }, role, { role = it })
        TempPasswordField(password, "Temporary password") { password = it }
    }
}

@Composable
private fun UsernameField(value: String, ok: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(30)) },
        label = { Text("Username") },
        placeholder = { Text("e.g. jordan") },
        singleLine = true,
        isError = value.isNotEmpty() && !ok,
        supportingText = { Text("3-30 letters, numbers, and . _ -") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmailField(value: String, ok: Boolean, optional: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(160)) },
        label = { Text(if (optional) "Email (optional)" else "Email") },
        placeholder = { Text("for password recovery") },
        singleLine = true,
        isError = !ok,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One member: their sign-in details on top (Save), then whatever else the
 * signed-in user may do to them, as on the web's member row.
 */
@Composable
private fun MemberEditor(member: MemberAccount, user: SessionUser, busy: Boolean, vm: MembersViewModel, onClose: () -> Unit) {
    val isSelf = member.id == user.id
    val isHead = member.role == "head"
    val manageable = canManageMember(user.role, member.role)
    val roles = assignableRoles(user.role)
    val canChangeRole = manageable && !isSelf && !isHead && roles.isNotEmpty()
    val canResetPassword = manageable && !isSelf
    val canRemove = manageable && !isSelf && !isHead
    val canTransfer = user.role == "head" && !isHead && !isSelf

    var name by remember { mutableStateOf(member.name) }
    var username by remember { mutableStateOf(member.username.orEmpty()) }
    var email by remember { mutableStateOf(member.email) }
    var newPassword by remember { mutableStateOf("") }
    var pendingRole by remember { mutableStateOf<String?>(null) }
    var confirmHead by remember { mutableStateOf(false) }

    // Only what changed is sent: an unchanged username or email isn't re-checked.
    val nameChange = name.trim().takeIf { it.isNotEmpty() && it != member.name }
    val usernameChange = username.trim().takeIf { it.isNotEmpty() && it != member.username.orEmpty() }
    // Your own email changes on Profile, which asks for your password first.
    val emailChange = email.trim().takeIf { !isSelf && it.isNotEmpty() && !it.equals(member.email, ignoreCase = true) }
    val usernameOk = username.isBlank() || USERNAME_RE.matches(username.trim())
    val emailOk = email.isBlank() || EMAIL_RE.matches(email.trim())

    EditorDialog(
        title = member.name,
        saving = busy,
        saveEnabled = name.isNotBlank() && usernameOk && emailOk &&
            (nameChange != null || usernameChange != null || emailChange != null),
        onDismiss = onClose,
        onSave = { vm.save(member, nameChange, usernameChange, emailChange, isSelf, onClose) },
        deleteLabel = "Remove from household",
        onDelete = if (canRemove) ({ vm.remove(member, onClose) }) else null,
    ) {
        FieldLabel("Profile")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(NAME_MAX) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        UsernameField(username, usernameOk) { username = it }
        if (!isSelf) {
            EmailField(email, emailOk, optional = false) { email = it }
        } else {
            Text(
                "To change your own email or password, use your Profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (canChangeRole) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DropdownField(
                "Role",
                roles.map { it to (ROLE_LABELS[it] ?: it) },
                member.role,
                { if (it != member.role) pendingRole = it },
            )
        } else if (isHead) {
            Text(
                "The Head of House can always do everything. Headship moves with “Make Head of House”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (canResetPassword) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            FieldLabel("Reset password")
            Text(
                "Signs ${member.name} out everywhere. They sign in with this one, then choose their own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TempPasswordField(newPassword, "New temporary password") { newPassword = it }
            OutlinedButton(
                onClick = { vm.resetPassword(member, newPassword) { newPassword = "" } },
                enabled = newPassword.length >= MIN_PASSWORD && !busy,
            ) { Text("Set password") }
        }

        if (canTransfer) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = { confirmHead = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Make Head of House")
            }
        }
    }

    pendingRole?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingRole = null },
            title = { Text("Change role?") },
            text = { Text("Change ${member.name}'s role to ${ROLE_LABELS[role] ?: role}?") },
            confirmButton = {
                TextButton(onClick = { pendingRole = null; vm.setRole(member, role) }) { Text("Change role") }
            },
            dismissButton = { TextButton(onClick = { pendingRole = null }) { Text("Cancel") } },
        )
    }
    if (confirmHead) {
        AlertDialog(
            onDismissRequest = { confirmHead = false },
            title = { Text("Transfer Head of House?") },
            text = { Text("Make ${member.name} the Head of House? You'll become a Manager.") },
            confirmButton = {
                TextButton(onClick = { confirmHead = false; vm.makeHead(member, onClose) }) { Text("Make Head of House") }
            },
            dismissButton = { TextButton(onClick = { confirmHead = false }) { Text("Cancel") } },
        )
    }
}
