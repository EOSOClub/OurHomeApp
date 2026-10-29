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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.SetupPrefill
import kotlinx.coroutines.launch

/**
 * [prefill]: what a setup link's Open Our Home button filled in (a new child's
 * phone); [onPrefillUsed] once it's in the fields.
 */
@Composable
fun LoginScreen(
    session: SessionManager,
    initialMessage: String?,
    prefill: SetupPrefill? = null,
    onPrefillUsed: () -> Unit = {},
) {
    var server by rememberSaveable { mutableStateOf(session.baseUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // No server baked in (no config.properties at build time): ask for it up front.
    var showServer by rememberSaveable { mutableStateOf(session.baseUrl.isBlank()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialMessage) }
    // Under the fields while set up from a link: what to type, or why its server wasn't used.
    var setupNote by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(prefill) {
        val p = prefill ?: return@LaunchedEffect
        username = p.username
        p.server?.let { server = it; showServer = true }
        if (p.note != null) showServer = true
        error = null
        setupNote = p.note ?: ("Your username is filled in. Sign in with the temporary password from the adult " +
            "setting up your phone, then choose your own.")
        onPrefillUsed()
    }

    fun submit() {
        if (server.isBlank()) {
            showServer = true
            error = "Enter your server address"
            return
        }
        if (loading || username.isBlank() || password.isEmpty()) return
        loading = true
        error = null
        scope.launch {
            try {
                session.signIn(server, username, password)
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed"
                loading = false
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
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 48.dp))
        Text(
            "Sign in with your household account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (showServer) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("Server") },
                placeholder = { Text("https://home.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        setupNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::submit, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Sign in")
        }
        TextButton(onClick = { showServer = !showServer }) {
            Text(if (showServer) "Hide server" else "Server: $server")
        }
        OfflineUnlockSection(showMessage = { error = it })
    }
}
