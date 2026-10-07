package com.eosoclub.ourhome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.Notification
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared by the top-bar badge and the notifications screen. */
class NotificationsViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val items: List<Notification> = emptyList(),
        val unreadCount: Int = 0,
        val loading: Boolean = true,
        val error: String? = null,
    )

    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // The server regenerates reminders on every list fetch, so this also
    // brings overdue/low-stock/bill-due notices up to date.
    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.update {
            try {
                val list = api.notifications()
                it.copy(items = list.items, unreadCount = list.unreadCount, loading = false, error = null)
            } catch (e: Exception) {
                it.copy(loading = false, error = e.message)
            }
        }
    }

    fun markRead(n: Notification) {
        if (n.read) return
        state.update { s ->
            s.copy(
                items = s.items.map { if (it.id == n.id) it.copy(read = true) else it },
                unreadCount = (s.unreadCount - 1).coerceAtLeast(0),
            )
        }
        viewModelScope.launch {
            runCatching { api.markNotificationRead(n.id) }.onFailure { refresh() }
        }
    }

    fun markAllRead() = viewModelScope.launch {
        try {
            api.markAllNotificationsRead()
            state.update { s -> s.copy(items = s.items.map { it.copy(read = true) }, unreadCount = 0) }
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't mark notifications read")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(vm: NotificationsViewModel, showMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (LoadState(state.loading, state.error, state.items.isEmpty(), "You're all caught up.", vm::refresh)) {
            return@PullToRefreshBox
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = { it.id }) { n ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.markRead(n) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        n.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (n.read) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (n.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    n.body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(relativeTime(n.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}
