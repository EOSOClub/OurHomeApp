package com.eosoclub.ourhome.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
internal fun loadState(
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

/**
 * Opens one of Android's settings pages. Not every phone has every page
 * (Android's own docs say so for battery, usage access and NFC), and starting
 * one that isn't there closed the app; this says so instead.
 */
internal fun Context.openSettingsPage(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "This phone has no such settings page", Toast.LENGTH_LONG).show()
    }
}

private val shortDate = DateTimeFormatter.ofPattern("MMM d")
// The household's zone, not the phone's (see HouseholdZone).
private val zone: ZoneId get() = HouseholdZone.id

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

/**
 * The day an event is listed under: "Today" / "Tomorrow" / "Oct 9". One that
 * began before today is still going on (the server lists those too), so it
 * reads "Until Oct 12", never "Overdue" as a task's due date would; without
 * an end, just the day it began.
 */
internal fun eventDayLabel(start: LocalDate, end: LocalDate?, today: LocalDate): String {
    fun day(date: LocalDate, capital: Boolean) = when (date) {
        today -> if (capital) "Today" else "today"
        today.plusDays(1) -> if (capital) "Tomorrow" else "tomorrow"
        else -> date.format(shortDate)
    }
    return when {
        !start.isBefore(today) -> day(start, capital = true)
        end != null && !end.isBefore(today) -> "Until ${day(end, capital = false)}"
        else -> start.format(shortDate)
    }
}

internal fun formatDate(iso: String): String = localDate(iso).format(shortDate)

/**
 * What a number box keeps of what was typed: digits and one decimal point,
 * with a comma read as the point (many keyboards have only the comma). The
 * comma used to be dropped without a word, so "4,99" was saved as 499.
 */
internal fun decimalInput(text: String, decimals: Boolean = true): String {
    val kept = text.replace(',', '.').filter { it.isDigit() || (decimals && it == '.') }
    val point = kept.indexOf('.')
    return if (point < 0) kept else kept.take(point + 1) + kept.drop(point + 1).replace(".", "")
}

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

/**
 * Loads into a screen's state: fetches first, then applies the result (or the
 * error) in one update. Never fetch inside `update {}` - it re-runs its lambda
 * whenever the state changed meanwhile (a tick during a refresh), re-sending
 * the request each time. Cancellation propagates instead of showing as an error.
 */
internal suspend fun <S, T> kotlinx.coroutines.flow.MutableStateFlow<S>.load(
    fetch: suspend () -> T,
    onOk: (S, T) -> S,
    onError: (S, String?) -> S,
) {
    val result = try {
        Result.success(fetch())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
    while (true) {
        val current = value
        val next = result.fold({ onOk(current, it) }, { onError(current, it.message) })
        if (compareAndSet(current, next)) return
    }
}
