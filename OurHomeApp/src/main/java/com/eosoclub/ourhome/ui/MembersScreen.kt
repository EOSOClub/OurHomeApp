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
import androidx.compose.material3.Checkbox
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
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.AuthState
import com.eosoclub.ourhome.data.MemberAccount
import com.eosoclub.ourhome.data.Permission
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.SessionUser
import com.eosoclub.ourhome.data.Supervision
import com.eosoclub.ourhome.data.SupervisionPerson
import com.eosoclub.ourhome.data.assignableRoles
import com.eosoclub.ourhome.data.can
import com.eosoclub.ourhome.data.canBeSupervised
import com.eosoclub.ourhome.data.canManageMember
import com.eosoclub.ourhome.data.emailProblem
import com.eosoclub.ourhome.data.passwordProblem
import com.eosoclub.ourhome.data.usernameProblem
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
 * change roles, reset passwords, choose who supervises each child and teen
 * (head), hand over Head of House (head) and remove people. The per-page
 * permissions grid stays on the website.
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
        /** Who supervises each child and teen; only for the Head of House (null otherwise). */
        val supervision: Supervision? = null,
        /** Whose supervisors are being saved, and to what. */
        val savingSupervisors: Pair<String, List<String>>? = null,
        /** Why the last change failed, shown in the open editor (the snackbar is behind it). */
        val formError: String? = null,
        /** The fields the server said were wrong, each until it's edited. */
        val fieldErrors: Map<String, String> = emptyMap(),
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Only the Head of House chooses who supervises whom (the server checks it too). */
    private fun isHead() = (session.state.value as? AuthState.SignedIn)?.user?.role == "head"

    private suspend fun loadSupervision(): Supervision? =
        if (isHead()) runCatching { api.supervision() }.getOrNull() else null

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true, error = null) }
        try {
            coroutineScope {
                val name = async { runCatching { api.profile().householdName }.getOrNull() }
                val supervision = async { loadSupervision() }
                val members = api.memberAccounts()
                state.update {
                    it.copy(
                        members = members,
                        householdName = name.await() ?: it.householdName,
                        supervision = supervision.await(),
                    )
                }
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
            state.update { it.copy(busy = true, formError = null, fieldErrors = emptyMap()) }
            try {
                block()
                onDone()
                success?.let { _messages.send(it) }
                runCatching { api.memberAccounts() }.onSuccess { m -> state.update { it.copy(members = m) } }
                // Someone added, removed or re-roled changes the supervision list.
                val supervision = loadSupervision()
                state.update { it.copy(supervision = supervision) }
                if (touchesMe) session.refreshUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Something went wrong"
                // The open editor shows why and marks the fields; the snackbar is for everything else.
                state.update { it.copy(formError = message, fieldErrors = (e as? ApiException)?.fieldErrors.orEmpty()) }
                _messages.send(message)
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    /** A field was edited: what the server said about it no longer applies. */
    fun clearFieldError(key: String) = state.update { it.copy(fieldErrors = it.fieldErrors - key) }

    /** An editor opened or closed: start without the last one's errors. */
    fun clearErrors() = state.update { it.copy(formError = null, fieldErrors = emptyMap()) }

    /** Replaces who supervises one child or teen (Head of House), saved with each tick. */
    fun setSupervisors(kid: MemberAccount, supervisorIds: List<String>) = viewModelScope.launch {
        state.update { it.copy(savingSupervisors = kid.id to supervisorIds, formError = null) }
        try {
            val next = api.setSupervisors(kid.id, supervisorIds)
            // Set before the saving state ends, so the boxes never flick back.
            state.update { it.copy(supervision = next, savingSupervisors = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Couldn't save who supervises ${kid.name}"
            state.update { it.copy(savingSupervisors = null, formError = message) }
            _messages.send(message)
        }
    }

    fun rename(name: String, onDone: () -> Unit) = act(null, onDone = onDone) {
        val household = api.renameHousehold(name)
        state.update { it.copy(householdName = household.name) }
        _messages.send("Household renamed to “${household.name}”")
    }

    /** [supervisorIds]: who supervises a new child or teen (the Head's picks; empty for anyone else). */
    fun add(
        name: String,
        username: String,
        email: String?,
        role: String,
        password: String,
        supervisorIds: List<String>,
        onDone: () -> Unit,
    ) = act(null, onDone = onDone) {
        val member = api.createMember(name, username, email, role, password, supervisorIds)
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
internal fun generatePassword(): String = UUID.randomUUID().toString().replace("-", "").take(12)

internal const val NAME_MAX = 80

@Composable
fun MembersScreen(
    session: SessionManager,
    user: SessionUser,
    showMessage: (String) -> Unit,
    /** Opens Set up a child (offered to the Head of House). */
    onSetUpChild: () -> Unit,
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
        if (loadState(state.loading, state.error, state.members == null, "") { vm.refresh() }) {
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
                Button(onClick = { vm.dismissAdded(); vm.clearErrors(); adding = true }) { Text("Add a member") }
            }
            // Only the Head of House chooses supervisors (state.supervision is loaded only for them).
            if (state.supervision != null) {
                Column {
                    Text(
                        "Adding a child or teen? Set up a child also walks you through their phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSetUpChild) { Text("Set up a child") }
                }
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
                    MemberRow(
                        m,
                        isSelf,
                        supervisedBy = state.supervision?.supervisedBy(m.id),
                        onClick = if (canOpen) ({ vm.clearErrors(); editingId = m.id }) else null,
                    )
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
        AddMemberDialog(user.role, state, vm, onClose = { adding = false })
    }
    editingId?.let { id ->
        val member = state.members?.firstOrNull { it.id == id }
        if (member != null) {
            MemberEditor(member, user, state, vm, onClose = { editingId = null })
        } else {
            LaunchedEffect(id) { editingId = null }
        }
    }
}

/** [supervisedBy]: "Supervised by …" for a child or teen, shown to the Head of House; null otherwise. */
@Composable
private fun MemberRow(m: MemberAccount, isSelf: Boolean, supervisedBy: String?, onClick: (() -> Unit)?) {
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
            supervisedBy?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/**
 * A temporary password field with Generate and Copy, shared by add and reset.
 * [error]: what's wrong with it (red, said under it); null shows the hint.
 */
@Composable
internal fun TempPasswordField(password: String, label: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = password,
        onValueChange = { onChange(it.take(128)) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = {
            Text(error ?: "At least $MIN_PASSWORD characters. They choose their own when they next sign in.")
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChange(generatePassword()) }) { Text("Generate") }
        CopyButton(password, sensitive = true, clipLabel = "Password", enabled = password.isNotEmpty())
    }
}

@Composable
private fun AddMemberDialog(actorRole: String?, state: MembersViewModel.UiState, vm: MembersViewModel, onClose: () -> Unit) {
    val roles = assignableRoles(actorRole)
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(if ("adult" in roles) "adult" else roles.first()) }
    var password by remember { mutableStateOf("") }
    var supervisorIds by remember { mutableStateOf(emptyList<String>()) }
    // Checked as they type, by the server's own rules; what the server said wins.
    val errors = state.fieldErrors
    val nameError = errors["name"]
    val usernameError = errors["username"] ?: usernameProblem(username)
    val emailError = errors["email"] ?: emailProblem(email)
    val passwordError = errors["password"] ?: passwordProblem(password)
    val roleError = errors["role"]
    val supervisorsError = errors["supervisorIds"]
    // Only a child or teen is supervised, and only the Head chooses who by.
    val supervised = canBeSupervised(role)
    val supervision = state.supervision

    EditorDialog(
        title = "Add a member",
        saving = state.busy,
        saveEnabled = name.isNotBlank() && username.isNotBlank() && password.isNotEmpty() &&
            listOf(nameError, usernameError, emailError, passwordError, roleError, supervisorsError).all { it == null },
        onDismiss = { vm.clearErrors(); onClose() },
        onSave = {
            vm.add(
                name.trim(),
                username.trim(),
                email.trim().ifEmpty { null },
                role,
                password,
                if (supervised && supervision != null) supervisorIds else emptyList(),
                onClose,
            )
        },
        saveLabel = "Add",
        error = state.formError,
    ) {
        Text(
            "They sign in with a username and the temporary password you set, then choose their own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(NAME_MAX); vm.clearFieldError("name") },
            label = { Text("Name") },
            placeholder = { Text("e.g. Jordan") },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        UsernameField(username, usernameError) { username = it; vm.clearFieldError("username") }
        EmailField(email, emailError, optional = true) { email = it; vm.clearFieldError("email") }
        DropdownField("Role", roles.map { it to (ROLE_LABELS[it] ?: it) }, role, { role = it; vm.clearFieldError("role") })
        roleError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (supervised) {
            FieldLabel("Who supervises them")
            if (supervision == null) {
                Text(
                    "The Head of House chooses who supervises them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SupervisorChecks(
                    supervision.supervisors,
                    checked = supervisorIds,
                    enabled = !state.busy,
                    onToggle = { id ->
                        supervisorIds = if (id in supervisorIds) supervisorIds - id else supervisorIds + id
                        vm.clearFieldError("supervisorIds")
                    },
                )
                Text(
                    supervisorsError ?: SUPERVISION_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (supervisorsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TempPasswordField(password, "Temporary password", passwordError) { password = it; vm.clearFieldError("password") }
    }
}

/** What every supervisor picker says: only the ticked adults get the controls (web: SUPERVISION_HINT). */
internal const val SUPERVISION_HINT =
    "Only the adults ticked here can change their phone rules and get alerts about their phone. Only the Head of " +
        "House can change this, so nobody can add themselves."

/** One tick box per adult who may supervise. */
@Composable
internal fun SupervisorChecks(
    supervisors: List<SupervisionPerson>,
    checked: List<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    if (supervisors.isEmpty()) {
        Text("Nobody in the household can supervise yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        supervisors.forEach { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onToggle(s.id) },
            ) {
                Checkbox(checked = s.id in checked, onCheckedChange = { onToggle(s.id) }, enabled = enabled)
                Text(s.name, modifier = Modifier.weight(1f))
                Text(
                    ROLE_LABELS[s.role] ?: s.role,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

/** [error]: what's wrong with it (red, said under it); null shows the hint. */
@Composable
internal fun UsernameField(value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(30)) },
        label = { Text("Username") },
        placeholder = { Text("e.g. jordan") },
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        supportingText = { Text(error ?: "3 to 30 letters, numbers or . _ - (no spaces).") },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** [error]: what's wrong with it (red, said under it). */
@Composable
internal fun EmailField(value: String, error: String?, optional: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(160)) },
        label = { Text(if (optional) "Email (optional)" else "Email") },
        placeholder = { Text("for password recovery") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One member: their sign-in details on top (Save), then whatever else the
 * signed-in user may do to them, as on the web's member row.
 */
@Composable
private fun MemberEditor(member: MemberAccount, user: SessionUser, state: MembersViewModel.UiState, vm: MembersViewModel, onClose: () -> Unit) {
    val busy = state.busy
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
    // Checked as they type, by the server's own rules; what the server said wins.
    val nameError = state.fieldErrors["name"]
    val usernameError = state.fieldErrors["username"] ?: usernameProblem(username)
    val emailError = state.fieldErrors["email"] ?: emailProblem(email)
    val passwordError = state.fieldErrors["password"] ?: passwordProblem(newPassword)
    // Who supervises a child or teen: the Head only (state.supervision is loaded only for them).
    val kid = state.supervision?.supervised?.firstOrNull { it.id == member.id }

    EditorDialog(
        title = member.name,
        saving = busy,
        saveEnabled = name.isNotBlank() && nameError == null && usernameError == null && emailError == null &&
            (nameChange != null || usernameChange != null || emailChange != null),
        onDismiss = { vm.clearErrors(); onClose() },
        onSave = { vm.save(member, nameChange, usernameChange, emailChange, isSelf, onClose) },
        deleteLabel = "Remove from household",
        onDelete = if (canRemove) ({ vm.remove(member, onClose) }) else null,
        error = state.formError,
    ) {
        FieldLabel("Profile")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(NAME_MAX); vm.clearFieldError("name") },
            label = { Text("Name") },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        UsernameField(username, usernameError) { username = it; vm.clearFieldError("username") }
        if (!isSelf) {
            EmailField(email, emailError, optional = false) { email = it; vm.clearFieldError("email") }
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

        if (kid != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val saving = state.savingSupervisors?.takeIf { it.first == member.id }
            // While saving, show the boxes as they're being saved.
            val checked = saving?.second ?: kid.supervisorIds
            FieldLabel("Who supervises them")
            SupervisorChecks(
                state.supervision.supervisors,
                checked = checked,
                enabled = saving == null,
                onToggle = { id -> vm.setSupervisors(member, if (id in checked) checked - id else checked + id) },
            )
            Text(SUPERVISION_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (canResetPassword) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            FieldLabel("Reset password")
            Text(
                "Signs ${member.name} out everywhere. They sign in with this one, then choose their own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TempPasswordField(newPassword, "New temporary password", passwordError) {
                newPassword = it
                vm.clearFieldError("password")
            }
            OutlinedButton(
                onClick = { vm.resetPassword(member, newPassword) { newPassword = "" } },
                enabled = newPassword.isNotEmpty() && passwordError == null && !busy,
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
