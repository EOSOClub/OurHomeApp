package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ActivityEntry
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.Member
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Filters on the Activity page; keys match the web's lib/activityAreas.ts. */
private val AREAS = listOf(
    "tasks" to "Tasks",
    "shopping" to "Shopping",
    "inventory" to "Stock",
    "bills" to "Bills",
    "calendar" to "Calendar",
    "requests" to "Requests",
    "household" to "Household",
)

class ActivityViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val items: List<ActivityEntry> = emptyList(),
        val nextBefore: String? = null,
        val area: String? = null,
        val userId: String? = null,
        val members: List<Member> = emptyList(),
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
    )

    val state = MutableStateFlow(UiState())
    private var job: Job? = null

    /** Reloads the first page for the current filters (a new filter cancels the old load). */
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            state.update { it.copy(loading = true) }
            val s = state.value
            state.update {
                try {
                    val page = api.activity(s.area, s.userId)
                    it.copy(items = page.items, nextBefore = page.nextBefore, loading = false, error = null)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    it.copy(loading = false, error = e.message)
                }
            }
        }
        if (state.value.members.isEmpty()) viewModelScope.launch {
            runCatching { api.householdMembers() }.onSuccess { m -> state.update { it.copy(members = m) } }
        }
    }

    fun setArea(area: String?) {
        state.update { it.copy(area = area, items = emptyList(), nextBefore = null) }
        refresh()
    }

    fun setUser(userId: String?) {
        state.update { it.copy(userId = userId, items = emptyList(), nextBefore = null) }
        refresh()
    }

    fun loadMore() {
        val s = state.value
        val before = s.nextBefore ?: return
        if (s.loadingMore || s.loading) return
        viewModelScope.launch {
            state.update { it.copy(loadingMore = true) }
            try {
                val page = api.activity(s.area, s.userId, before)
                // Ignore a page that arrives after the filters changed.
                state.update {
                    if (it.area != s.area || it.userId != s.userId) it.copy(loadingMore = false)
                    else it.copy(items = it.items + page.items, nextBefore = page.nextBefore, loadingMore = false)
                }
            } catch (e: Exception) {
                state.update { it.copy(loadingMore = false, error = e.message) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityScreen(api: ApiClient, userId: String, modifier: Modifier = Modifier) {
    val vm = viewModel { ActivityViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(selected = state.area == null, onClick = { vm.setArea(null) }, label = { Text("All") }) }
            items(AREAS) { (key, label) ->
                FilterChip(selected = state.area == key, onClick = { vm.setArea(key) }, label = { Text(label) })
            }
        }
        if (state.members.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip(selected = state.userId == null, onClick = { vm.setUser(null) }, label = { Text("Everyone") }) }
                items(state.members, key = { it.id }) { m ->
                    FilterChip(
                        selected = state.userId == m.id,
                        onClick = { vm.setUser(m.id) },
                        label = { Text(if (m.id == userId) "Me" else m.name) },
                    )
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = state.loading && state.items.isNotEmpty(),
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            val empty = if (state.area != null || state.userId != null) "Nothing here — try another filter." else "No activity yet."
            if (LoadState(state.loading, state.error, state.items.isEmpty(), empty, vm::refresh)) return@PullToRefreshBox
            val groups = state.items.groupBy { localDate(it.createdAt) }
            LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
                groups.forEach { (day, entries) ->
                    item(key = "day-$day") {
                        Text(
                            dayLabel(day).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(entries, key = { it.id }) { entry -> ActivityRow(entry) }
                }
                if (state.nextBefore != null) {
                    item(key = "more") {
                        LaunchedEffect(state.nextBefore) { vm.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (state.loadingMore) CircularProgressIndicator()
                            else TextButton(onClick = vm::loadMore) { Text("Load older") }
                        }
                    }
                }
            }
        }
    }
}

private val clock = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun ActivityRow(entry: ActivityEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            Instant.parse(entry.createdAt).atZone(ZoneId.systemDefault()).format(clock),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp).padding(top = 2.dp),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(entry.actor?.name ?: "System") }
                append(" ")
                append(entry.message)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    }
}
