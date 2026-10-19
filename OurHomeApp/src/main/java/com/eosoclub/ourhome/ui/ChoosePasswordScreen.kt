package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.SessionUser
import com.eosoclub.ourhome.data.passwordProblem
import kotlinx.coroutines.launch

/**
 * The temporary-password gate (web: ForcedPasswordChange on /change-password).
 * Shown instead of Home while the account still has the password an adult
 * set, so it is the first thing after signing in; choosing one lifts the flag
 * and Home opens (on a child's phone, straight into Set up this phone).
 */
@Composable
internal fun ChoosePasswordScreen(session: SessionManager, user: SessionUser) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val errors = remember { FormErrors() }
    val scope = rememberCoroutineScope()

    val mismatch = confirm.isNotEmpty() && confirm != new
    val canSave = current.isNotEmpty() && new.length >= MIN_PASSWORD && confirm == new && !saving
    // "Current password" is this form's temporary one.
    val currentError = errors["currentPassword"]?.let { "That temporary password is incorrect." }
    val newError = errors["newPassword"] ?: passwordProblem(new)
    // Anything the server said that isn't about one field.
    val otherError = errors.message?.takeIf { errors.fields.isEmpty() }

    fun save() {
        if (!canSave) return
        saving = true
        errors.clear()
        scope.launch {
            try {
                // Signs out any other device still on the temporary password, as the web does.
                session.api.changePassword(current, new, revokeOtherSessions = true)
                // The server lifts the flag itself after a change; this only confirms it.
                runCatching { session.api.clearPasswordFlag() }
                saving = false
                session.clearMustChangePassword()
            } catch (e: Exception) {
                errors.fail(e, "Couldn't set your password")
                saving = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 48.dp).size(40.dp),
        )
        Text("Choose your password", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Welcome, ${user.name ?: user.username}. You signed in with a temporary password - set your own to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = current,
            onValueChange = { current = it; errors.clear("currentPassword") },
            label = { Text("Temporary password") },
            supportingText = currentError?.let { { Text(it) } },
            isError = currentError != null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = new,
            onValueChange = { new = it; errors.clear("newPassword") },
            label = { Text("New password") },
            supportingText = { Text(newError ?: "At least $MIN_PASSWORD characters.") },
            isError = newError != null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm new password") },
            supportingText = if (mismatch) ({ Text("Passwords do not match.") }) else null,
            isError = mismatch,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        otherError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Set password & continue")
        }
        TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Sign out", modifier = Modifier.padding(start = 6.dp))
        }
    }

    if (confirmSignOut) {
        // Asks for the parent PIN while Prevent uninstall is on, as the menu's Sign out does.
        SignOutDialog(
            who = "${user.name ?: user.username}",
            onDismiss = { confirmSignOut = false },
            onSignOut = { confirmSignOut = false; scope.launch { session.signOut() } },
        )
    }
}
