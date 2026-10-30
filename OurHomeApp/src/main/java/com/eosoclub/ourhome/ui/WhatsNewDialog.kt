package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.ChangelogEntry
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RELEASE_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

/** "2026-10-29" as "October 29, 2026"; the text as sent if it isn't a date. */
private fun releaseDate(date: String): String =
    runCatching { LocalDate.parse(date).format(RELEASE_DATE) }.getOrDefault(date)

/**
 * "What's new" after an update: [entries] newest first, the same list the
 * website shows. Closing it either way calls [onClose], which marks it seen
 * for this member on every device.
 */
@Composable
fun WhatsNewDialog(entries: List<ChangelogEntry>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What's new") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            releaseDate(entry.date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        entry.changes.forEach { change ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium)
                                Text(change, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Got it") } },
    )
}
