package com.eosoclub.ourhome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import com.eosoclub.ourhome.BuildConfig
import com.eosoclub.ourhome.data.AppRelease
import com.eosoclub.ourhome.data.AppUpdate
import com.eosoclub.ourhome.data.ProfileOverview
import com.eosoclub.ourhome.data.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Mirror the web's validation (src/lib/validation/user.ts) so errors show
// before a round trip; the server stays the authority.
private val USERNAME_RE = Regex("^[a-zA-Z0-9._-]{3,30}$")
private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private const val MIN_PASSWORD = 8

private val ROLE_LABELS = mapOf(
    "head" to "Head of household",
    "manager" to "Manager",
    "member" to "Member",
    "guest" to "Guest",
)

class ProfileViewModel(private val session: SessionManager) : ViewModel() {
    data class UiState(
        val profile: ProfileOverview? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val savingProfile: Boolean = false,
        val changingPassword: Boolean = false,
        /** Bumped after a successful password change so the form clears. */
        val passwordFormKey: Int = 0,
        /** The app build the server offers (null = none, or couldn't ask). */
        val release: AppRelease? = null,
        /** 0..1 while an update downloads, else null. */
        val downloadProgress: Float? = null,
    )

    private val api = session.api
    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.update {
            try {
                it.copy(profile = api.profile(), loading = false, error = null)
            } catch (e: Exception) {
                it.copy(loading = false, error = e.message)
            }
        }
        // Separate, so an older server (no /api/app/info) still shows the profile.
        val release = runCatching { api.appRelease() }.getOrNull()
        state.update { it.copy(release = release) }
    }

    /** Downloads the offered update with this session, checks it, then opens Android's installer. */
    fun downloadAndInstall(context: Context) = viewModelScope.launch {
        val release = state.value.release ?: return@launch
        if (state.value.downloadProgress != null) return@launch
        state.update { it.copy(downloadProgress = 0f) }
        try {
            val apk = AppUpdate.download(context, api, release) { p -> state.update { it.copy(downloadProgress = p) } }
            AppUpdate.install(context, apk)
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't download the update")
        } finally {
            state.update { it.copy(downloadProgress = null) }
        }
    }

    fun saveProfile(name: String?, username: String?, email: String?) = viewModelScope.launch {
        state.update { it.copy(savingProfile = true) }
        try {
            val updated = api.updateProfile(name, username, email)
            state.update { it.copy(profile = updated) }
            session.applyProfile(updated)
            _messages.send("Profile updated")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't update your profile")
        } finally {
            state.update { it.copy(savingProfile = false) }
        }
    }

    fun changePassword(current: String, new: String, signOutOthers: Boolean) = viewModelScope.launch {
        state.update { it.copy(changingPassword = true) }
        try {
            api.changePassword(current, new, signOutOthers)
            // Lift the temporary-password flag if it was set; not fatal if it fails.
            runCatching { api.clearPasswordFlag() }
            session.clearMustChangePassword()
            state.update { it.copy(passwordFormKey = it.passwordFormKey + 1) }
            _messages.send(if (signOutOthers) "Password changed — other devices were signed out" else "Password changed")
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't change your password")
        } finally {
            state.update { it.copy(changingPassword = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    session: SessionManager,
    mustChangePassword: Boolean,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { ProfileViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.profile != null,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        val profile = state.profile
        if (LoadState(state.loading, state.error, profile == null, "", vm::refresh) || profile == null) {
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
            if (mustChangePassword) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "Your account was set up with a temporary password. Please choose your own below.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            AccountCard(profile)
            state.release?.let { release ->
                val context = LocalContext.current
                AppUpdateCard(release, state.downloadProgress) { vm.downloadAndInstall(context.applicationContext) }
            }
            // Keyed on the saved values so the form resets to them after a save.
            key(profile.name, profile.username, profile.email) {
                EditProfileCard(profile, saving = state.savingProfile, onSave = vm::saveProfile)
            }
            key(state.passwordFormKey) {
                ChangePasswordCard(changing = state.changingPassword, onChange = vm::changePassword)
            }
        }
    }
}

@Composable
private fun AccountCard(p: ProfileOverview) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.name, style = MaterialTheme.typography.headlineSmall)
            p.username?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                listOfNotNull(ROLE_LABELS[p.role] ?: p.role, p.householdName).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Member since ${formatDate(p.memberSince)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Tasks done", p.stats.tasksCompleted)
                Stat("Open tasks", p.stats.openAssignedTasks)
                Stat("Purchases", p.stats.purchasesLogged)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/**
 * The server's own build of this app (from the web deploy): up to date, or an
 * update to download and install. Android asks once to allow installs from Our
 * Home, then confirms each install; an update keeps the user signed in.
 */
@Composable
private fun AppUpdateCard(release: AppRelease, progress: Float?, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val sameApp = release.appId == context.packageName
    val update = remember(release) { AppUpdate.isUpdate(context, release) }
    // Re-checked on return from Settings, where installs are allowed.
    var canInstall by remember { mutableStateOf(AppUpdate.canInstall(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = AppUpdate.canInstall(context) }
    val size = "%.1f MB".format(release.sizeBytes / 1_048_576.0)

    SectionCard("Android app", "Installed: version ${BuildConfig.VERSION_NAME}") {
        when {
            !sameApp -> Text(
                "This server offers its app as ${release.appId}, a separate install from this one " +
                    "(${context.packageName}). Get it from Profile on the site.",
                style = MaterialTheme.typography.bodyMedium,
            )
            !update -> Text("You have the latest version.", style = MaterialTheme.typography.bodyMedium)
            progress != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            !canInstall -> {
                Text(
                    "Version ${release.versionName} is ready ($size). First allow Our Home to install it " +
                        "(once), then come back here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { AppUpdate.openInstallPermission(context) }) { Text("Allow installs from Our Home") }
            }
            else -> {
                Text(
                    "Version ${release.versionName} is ready ($size). It installs over this one, so you stay signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onUpdate) { Text("Download and install") }
            }
        }
    }
}

@Composable
private fun EditProfileCard(
    p: ProfileOverview,
    saving: Boolean,
    onSave: (name: String?, username: String?, email: String?) -> Unit,
) {
    var name by remember { mutableStateOf(p.name) }
    var username by remember { mutableStateOf(p.username.orEmpty()) }
    var email by remember { mutableStateOf(p.email) }

    val nameDirty = name.trim() != p.name
    val usernameDirty = username.trim() != p.username.orEmpty()
    val emailDirty = email.trim() != p.email
    val nameValid = name.trim().length in 1..80
    val usernameValid = USERNAME_RE.matches(username.trim())
    val emailValid = EMAIL_RE.matches(email.trim())
    val canSave = (nameDirty || usernameDirty || emailDirty) && nameValid && usernameValid && emailValid && !saving

    SectionCard("Edit profile", "Your display name, sign-in username, and recovery email.") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display name") },
            singleLine = true,
            isError = !nameValid,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            supportingText = { Text(if (usernameValid) "You sign in with this." else "3–30 letters, numbers, or . _ -") },
            singleLine = true,
            isError = !usernameValid,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            supportingText = { Text(if (emailValid) "Used for password recovery." else "Enter a valid email address.") },
            singleLine = true,
            isError = !emailValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onSave(
                    name.trim().takeIf { nameDirty },
                    username.trim().takeIf { usernameDirty },
                    email.trim().takeIf { emailDirty },
                )
            },
            enabled = canSave,
        ) { Text(if (saving) "Saving…" else "Save changes") }
    }
}

@Composable
private fun ChangePasswordCard(
    changing: Boolean,
    onChange: (current: String, new: String, signOutOthers: Boolean) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var signOutOthers by remember { mutableStateOf(true) }

    val tooShort = new.isNotEmpty() && new.length < MIN_PASSWORD
    val mismatch = confirm.isNotEmpty() && confirm != new
    val canChange = current.isNotEmpty() && new.length >= MIN_PASSWORD && confirm == new && !changing

    SectionCard("Change password", "Enter your current password, then choose a new one.") {
        PasswordField("Current password", current) { current = it }
        PasswordField(
            "New password",
            new,
            supporting = "At least $MIN_PASSWORD characters.",
            isError = tooShort,
        ) { new = it }
        PasswordField(
            "Confirm new password",
            confirm,
            supporting = if (mismatch) "Passwords do not match." else null,
            isError = mismatch,
        ) { confirm = it }
        Row(
            Modifier.fillMaxWidth().clickable { signOutOthers = !signOutOthers },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = signOutOthers, onCheckedChange = { signOutOthers = it })
            Text("Sign out of other devices", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { onChange(current, new, signOutOthers) }, enabled = canChange) {
            Text(if (changing) "Updating…" else "Update password")
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    supporting: String? = null,
    isError: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
