package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.PointAward
import com.eosoclub.ourhome.data.PointsSummary
import com.eosoclub.ourhome.data.centi
import com.eosoclub.ourhome.data.formatPoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Points per member for a day/week/month/year (the web's /points page). */
class PointsViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val period: String = "week",
        /** A date inside the shown period ("YYYY-MM-DD"); null = the current one. */
        val date: String? = null,
        val summary: PointsSummary? = null,
        val awards: List<PointAward> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    val state = MutableStateFlow(UiState())

    fun load() = viewModelScope.launch {
        val s = state.value
        state.update { it.copy(loading = true) }
        try {
            val summary = api.pointsSummary(s.period, s.date)
            val awards = api.pointAwards(s.period, s.date)
            state.update { it.copy(summary = summary, awards = awards, loading = false, error = null) }
        } catch (e: Exception) {
            state.update { it.copy(loading = false, error = e.message ?: "Couldn't load points") }
        }
    }

    fun setPeriod(period: String) {
        state.update { it.copy(period = period, date = null) }
        load()
    }

    /** Step to the previous/next period; back to "current" once it's reached. */
    fun shift(direction: Int) {
        val summary = state.value.summary ?: return
        val start = LocalDate.parse(summary.startDate)
        val target = if (direction < 0) start.minusDays(1) else start.plusDays(summary.days.toLong())
        state.update { it.copy(date = if (target.isAfter(LocalDate.now())) null else target.toString()) }
        load()
    }

    fun void(award: PointAward, reason: String, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            api.voidAward(award.id, reason)
            onDone("Points voided")
            load()
        } catch (e: Exception) {
            onDone(e.message ?: "Couldn't void")
        }
    }
}

private val PERIODS = listOf("day" to "Day", "week" to "Week", "month" to "Month", "year" to "Year")

private fun periodTitle(s: PointsSummary): String {
    val start = LocalDate.parse(s.startDate)
    return when (s.period) {
        "day" -> start.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        "week" -> "Week of " + start.format(DateTimeFormatter.ofPattern("MMM d"))
        "month" -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        else -> start.year.toString()
    }
}

private fun pts(value: Double) = formatPoints(value.centi())

@Composable
fun PointsScreen(
    api: ApiClient,
    userId: String,
    isHead: Boolean,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { PointsViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    var voiding by remember { mutableStateOf<PointAward?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Earned by finishing tasks. Checked steps are queued until their task is completed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { ChoiceChips(PERIODS.map { it.second }, PERIODS.first { it.first == state.period }.second) { label -> vm.setPeriod(PERIODS.first { it.second == label }.first) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous") }
                Text(
                    state.summary?.let(::periodTitle) ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.loading) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                IconButton(onClick = { vm.shift(1) }, enabled = state.date != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                }
            }
        }
        state.error?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error) } }
        state.summary?.let { s ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("Household", pts(s.householdTotal), Modifier.weight(1f))
                    StatBox("Per person", pts(s.averagePerPerson), Modifier.weight(1f))
                    StatBox("Per person/day", pts(s.averagePerPersonPerDay), Modifier.weight(1f))
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Header("Member", Modifier.weight(1f))
                            Header("Points", Modifier.weight(0.6f))
                            Header("Per day", Modifier.weight(0.6f))
                            Header("Queued", Modifier.weight(0.6f))
                        }
                        s.members.forEach { m ->
                            HorizontalDivider()
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    m.name + if (m.userId == userId) " (you)" else "",
                                    fontWeight = if (m.userId == userId) FontWeight.SemiBold else null,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(pts(m.points), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.6f))
                                Text(pts(m.perDay), modifier = Modifier.weight(0.6f))
                                Text(
                                    if (m.queued > 0) pts(m.queued) else "—",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Text("History", style = MaterialTheme.typography.titleMedium) }
        if (state.awards.isEmpty() && !state.loading) {
            item { Text("No points in this period yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.awards, key = { it.id }) { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${a.userName ?: "Former member"} · ${a.taskTitle}" + (a.stepTitle?.let { " — $it" } ?: ""),
                        textDecoration = if (a.voided) TextDecoration.LineThrough else null,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        relativeTime(a.awardedAt) + if (a.voided) " · voided${a.voidReason?.let { ": $it" } ?: ""}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    pts(a.points),
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (a.voided) TextDecoration.LineThrough else null,
                )
                if (isHead && !a.voided) TextButton(onClick = { voiding = a }) { Text("Void") }
            }
        }
    }

    voiding?.let { a ->
        var reason by remember(a.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { voiding = null },
            title = { Text("Void these points?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pts(a.points)} pts for ${a.userName ?: "a former member"} (“${a.taskTitle}”). It stays in the history and stops counting.")
                    OutlinedTextField(value = reason, onValueChange = { reason = it.take(200) }, label = { Text("Reason") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    vm.void(a, reason.trim(), showMessage)
                    voiding = null
                }) { Text("Void") }
            },
            dismissButton = { TextButton(onClick = { voiding = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
