package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.Dashboard
import com.eosoclub.ourhome.data.DashboardTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class DashboardViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(val data: Dashboard? = null, val loading: Boolean = true, val error: String? = null)

    val state = MutableStateFlow(UiState())

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.update {
            try {
                it.copy(data = api.dashboard(), loading = false, error = null)
            } catch (e: Exception) {
                it.copy(loading = false, error = e.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(
    api: ApiClient,
    userName: String?,
    onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { DashboardViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    val data = state.data
    PullToRefreshBox(
        isRefreshing = state.loading && data != null,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (LoadState(state.loading, state.error, data == null, "", vm::refresh) || data == null) return@PullToRefreshBox
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "Hi${userName?.let { ", ${it.substringBefore(' ')}" } ?: ""}",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item { StatGrid(data, onOpenTab) }

            taskSection("Overdue", data.overdue, onOpenTab)
            taskSection("Next 7 days", data.upcoming, onOpenTab)

            if (data.upcomingBills.isNotEmpty()) {
                item { SectionTitle("Unpaid bills") }
                items(data.upcomingBills, key = { "bill-${it.id}" }) { bill ->
                    val due = bill.dueDate?.let { dueLabel(it) }
                    ListCard(
                        title = bill.name,
                        subtitle = due?.first,
                        subtitleIsError = due?.second == true,
                        trailing = formatMoney(bill.amount, bill.currency),
                        onClick = { onOpenTab(Tab.Bills) },
                    )
                }
            }

            if (data.upcomingEvents.isNotEmpty()) {
                item { SectionTitle("Calendar") }
                items(data.upcomingEvents, key = { "event-${it.eventId}-${it.start}" }) { event ->
                    val day = dueLabel(event.start, prefix = "").first.trim().replaceFirstChar(Char::uppercase)
                    val time = if (event.allDay) "All day" else java.time.Instant.parse(event.start)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("h:mm a"))
                    ListCard(title = event.title, subtitle = listOfNotNull("$day · $time", event.location).joinToString(" · "))
                }
            }

            if (data.recentActivity.isNotEmpty()) {
                item { SectionTitle("Recent activity") }
                items(data.recentActivity, key = { "act-${it.id}" }) { entry ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            listOfNotNull(entry.actor?.name, entry.message).joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            relativeTime(entry.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.taskSection(title: String, tasks: List<DashboardTask>, onOpenTab: (Tab) -> Unit) {
    if (tasks.isEmpty()) return
    item { SectionTitle(title) }
    items(tasks, key = { "$title-${it.id}" }) { task ->
        val due = task.dueDate?.let { dueLabel(it) }
        ListCard(
            title = task.title,
            subtitle = listOfNotNull(due?.first, task.assignee?.name).joinToString(" · ").ifEmpty { null },
            subtitleIsError = due?.second == true,
            onClick = { onOpenTab(Tab.Tasks) },
        )
    }
}

@Composable
private fun StatGrid(data: Dashboard, onOpenTab: (Tab) -> Unit) {
    val c = data.counts
    val error = MaterialTheme.colorScheme.error
    val stats = listOf(
        Stat("Overdue", c.overdue, Tab.Tasks, if (c.overdue > 0) error else null),
        Stat("Open tasks", c.pending, Tab.Tasks),
        Stat("To buy", c.openShopping, Tab.Shopping),
        Stat("Low stock", c.lowInventory, Tab.Inventory, if (c.lowInventory > 0) error else null),
        Stat("Bills due", c.billsDue, Tab.Bills, if (c.billsDue > 0) error else null),
        Stat("Recurring", c.recurring, Tab.Tasks),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { stat ->
                    Card(onClick = { onOpenTab(stat.tab) }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                stat.value.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = stat.accent ?: MaterialTheme.colorScheme.onSurface,
                            )
                            Text(stat.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private data class Stat(val label: String, val value: Int, val tab: Tab, val accent: Color? = null)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ListCard(
    title: String,
    subtitle: String? = null,
    subtitleIsError: Boolean = false,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    if (onClick != null) Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
    else Card(colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
}
