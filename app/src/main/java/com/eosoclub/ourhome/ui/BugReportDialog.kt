package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.eosoclub.ourhome.BuildConfig
import com.eosoclub.ourhome.data.ApiClient
import kotlinx.coroutines.launch

/**
 * "Report a bug" form (⋮ menu). [where] is the screen the user was on; it is
 * sent with the app version so reports can be matched to a build. The server
 * notifies the head and emails support.
 */
@Composable
fun BugReportDialog(
    api: ApiClient,
    where: String,
    onDismiss: () -> Unit,
    showMessage: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    EditorDialog(
        title = "Report a bug",
        saving = sending,
        saveEnabled = title.trim().length >= 3 && description.isNotBlank(),
        saveLabel = "Send",
        onDismiss = onDismiss,
        onSave = {
            sending = true
            scope.launch {
                try {
                    api.submitBugReport(title.trim(), description.trim(), "App: $where", BuildConfig.VERSION_NAME)
                    showMessage("Thanks — your bug report was sent.")
                    onDismiss()
                } catch (e: Exception) {
                    showMessage(e.message ?: "Couldn't send your bug report")
                } finally {
                    sending = false
                }
            }
        },
    ) {
        Text(
            "Tell us what went wrong. We'll include the screen you were on ($where) and the app version.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(150) },
            label = { Text("What's wrong?") },
            placeholder = { Text("e.g. Bills tab won't load") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(5000) },
            label = { Text("Details") },
            placeholder = { Text("What did you do, what did you expect, and what happened instead?") },
            minLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
