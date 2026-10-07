package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    // A scrollable column so pull-to-refresh still works on empty/error states.
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { item { Column(horizontalAlignment = Alignment.CenterHorizontally) { content() } } }
}

/** Standard first-load / failed-load / empty states; returns true if one was shown. */
@Composable
internal fun LoadState(
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    emptyText: String,
    onRetry: () -> Unit,
): Boolean {
    when {
        loading && isEmpty -> Centered { CircularProgressIndicator() }
        error != null && isEmpty -> Centered {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        isEmpty -> Centered { Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else -> return false
    }
    return true
}

private val shortDate = DateTimeFormatter.ofPattern("MMM d")
private val zone: ZoneId get() = ZoneId.systemDefault()

internal fun localDate(iso: String): LocalDate = Instant.parse(iso).atZone(zone).toLocalDate()

/** "Due today" / "Due Oct 9" / "Overdue · Oct 3", plus whether it is overdue. */
internal fun dueLabel(iso: String, prefix: String = "Due"): Pair<String, Boolean> {
    val date = localDate(iso)
    val today = LocalDate.now(zone)
    return when {
        date.isBefore(today) -> "Overdue · ${date.format(shortDate)}" to true
        date == today -> "$prefix today" to false
        date == today.plusDays(1) -> "$prefix tomorrow" to false
        else -> "$prefix ${date.format(shortDate)}" to false
    }
}

internal fun formatDate(iso: String): String = localDate(iso).format(shortDate)

/** "just now" / "5m ago" / "3h ago" / "2d ago" / "Oct 3". */
internal fun relativeTime(iso: String): String {
    val mins = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        mins < 60 * 24 * 7 -> "${mins / (60 * 24)}d ago"
        else -> formatDate(iso)
    }
}

internal fun formatMoney(amount: Double, currency: String?): String {
    val format = NumberFormat.getCurrencyInstance()
    runCatching { format.currency = Currency.getInstance(currency ?: "USD") }
    return format.format(amount)
}

/** Drops a trailing ".0" so whole quantities read as "3", not "3.0". */
internal fun formatQuantity(q: Double): String =
    if (q % 1.0 == 0.0) q.toLong().toString() else "%.2f".format(q).trimEnd('0').trimEnd('.')
