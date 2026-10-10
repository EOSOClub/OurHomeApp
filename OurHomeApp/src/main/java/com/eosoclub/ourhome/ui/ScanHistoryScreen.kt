package com.eosoclub.ourhome.ui

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.NfcScan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ScanHistoryViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(val scans: List<NfcScan> = emptyList(), val loading: Boolean = true, val error: String? = null)

    val state = MutableStateFlow(UiState())

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load({ api.nfcScans() }, { s, v -> s.copy(scans = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
    }
}

/** What was scanned, when, and by whom — from this app and from Home Assistant. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHistoryScreen(api: ApiClient, modifier: Modifier = Modifier) {
    val vm = viewModel { ScanHistoryViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    PullToRefreshBox(
        isRefreshing = state.loading && state.scans.isNotEmpty(),
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (LoadState(state.loading, state.error, state.scans.isEmpty(), "No tags scanned yet.", vm::refresh)) {
            return@PullToRefreshBox
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.scans, key = { it.id }) { scan ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(scan.headline(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            scan.actorName ?: "Unknown",
                            if (scan.source == "nfc") "this app" else "Home Assistant",
                            relativeTime(scan.createdAt),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private fun NfcScan.headline(): String {
    val item = itemName ?: "Unknown item"
    if (kind == "register") return "Tag set up for $item"
    val delta = amount?.let { (if (it > 0) "+" else "−") + formatQuantity(kotlin.math.abs(it)) }.orEmpty()
    val result = resultQuantity?.let { " → ${formatQuantity(it)}" }.orEmpty()
    return "$item $delta$result".trim()
}
