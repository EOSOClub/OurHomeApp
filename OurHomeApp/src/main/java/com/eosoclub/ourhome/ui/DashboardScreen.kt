package com.eosoclub.ourhome.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.AccessMatrix
import com.eosoclub.ourhome.data.Features
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.Dashboard
import com.eosoclub.ourhome.data.DashboardPoints
import com.eosoclub.ourhome.data.DashboardTask
import com.eosoclub.ourhome.data.DoneToday
import com.eosoclub.ourhome.data.HouseholdRequest
import com.eosoclub.ourhome.data.centi
import com.eosoclub.ourhome.data.formatPoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(val data: Dashboard? = null, val loading: Boolean = true, val error: String? = null)

    val state = MutableStateFlow(UiState())

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load({ api.dashboard() }, { s, v -> s.copy(data = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
    }
}

/** A request on the "Needs you" card, with what the user has to do about it. */
internal data class WaitingRequest(val request: HouseholdRequest, val action: String, val urgent: Boolean)

/**
 * "My day first": what is on the user (their tasks, requests waiting on them),
 * their points, then a short household glance and what's coming up. The
 * activity log is its own page now (⋮ → Activity, or the link at the bottom).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(
    api: ApiClient,
    userName: String?,
    access: AccessMatrix,
    features: Features,
    waitingRequests: List<WaitingRequest>,
    onOpenTab: (Tab) -> Unit,
    /** null when Points is turned off. */
    onOpenPoints: (() -> Unit)?,
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
        // A server from before the redesign has no "me": show its household lists instead.
        val me = data.me
        val today = me?.today ?: data.overdue
        val later = me?.later ?: data.upcoming
        val openToAnyone = me?.openToAnyone.orEmpty()

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Cards for features the server admin turned off aren't shown at all.
            item { Greeting(userName, data.doneToday.takeIf { features.tasks }) }
            item { QuickActions(access, features, onOpenTab) }
            if (features.tasks || features.requests) {
                item {
                    NeedsYouCard(
                        today = today,
                        requests = waitingRequests,
                        openToAnyone = openToAnyone,
                        later = later,
                        onOpenTab = onOpenTab,
                    )
                }
            }
            if (onOpenPoints != null) me?.points?.let { points -> item { PointsCard(points, onOpenPoints) } }
            if (features.tasks || features.inventory || features.shopping || features.bills) {
                item { HouseholdGlance(data, features, onOpenTab) }
            }
            if (features.calendar || features.bills) item { ComingUpCard(data, features, onOpenTab) }
        }
    }
}

@Composable
private fun Greeting(userName: String?, done: DoneToday?) {
    val hour = LocalTime.now().hour
    val hello = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    Column {
        Text(
            java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            hello + (userName?.let { ", ${it.substringBefore(' ')}" } ?: ""),
            style = MaterialTheme.typography.headlineSmall,
        )
        done?.let {
            Text(
                doneTodayText(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun doneTodayText(done: DoneToday): String {
    if (done.total == 0) return "Nothing ticked off yet today."
    val tasks = if (done.total == 1) "1 task" else "${done.total} tasks"
    val mine = when {
        done.mine == 0 -> ""
        done.mine == done.total -> " — all by you"
        else -> " — ${done.mine} by you"
    }
    return "🎉 $tasks done today$mine."
}

/** Shortcuts to the tabs this user may add on (the head's permissions grid). */
@Composable
private fun QuickActions(access: AccessMatrix, features: Features, onOpenTab: (Tab) -> Unit) {
    val actions = listOfNotNull(
        ("New task" to Tab.Tasks).takeIf { features.tasks && access.tasks.create },
        ("Add item" to Tab.Shopping).takeIf { features.shopping && access.shopping.create },
        ("Log payment" to Tab.Bills).takeIf { features.bills && access.bills.create },
        ("New request" to Tab.Requests).takeIf { features.requests && access.requests.create },
    )
    if (actions.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(actions) { (label, tab) ->
            AssistChip(onClick = { onOpenTab(tab) }, label = { Text(label) })
        }
    }
}

@Composable
private fun NeedsYouCard(
    today: List<DashboardTask>,
    requests: List<WaitingRequest>,
    openToAnyone: List<DashboardTask>,
    later: List<DashboardTask>,
    onOpenTab: (Tab) -> Unit,
) {
    DashCard("Needs you") {
        if (today.isEmpty() && requests.isEmpty() && openToAnyone.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("You're all caught up", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Nothing is due on you today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TaskRows(today, onOpenTab)
        if (requests.isNotEmpty()) {
            SubHeading("Requests")
            requests.forEach { w ->
                Row2(
                    title = requestTitle(w.request),
                    subtitle = w.request.requester.name?.let { "from $it" },
                    trailing = w.action,
                    trailingColor = if (w.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    onClick = { onOpenTab(Tab.Requests) },
                )
            }
        }
        if (openToAnyone.isNotEmpty()) {
            SubHeading("Open to anyone")
            TaskRows(openToAnyone, onOpenTab)
        }
        if (later.isNotEmpty()) {
            SubHeading("Later this week")
            TaskRows(later, onOpenTab, muted = true)
        }
    }
}

private fun requestTitle(r: HouseholdRequest): String = when (r.category) {
    "media" -> {
        val kind = if (r.mediaType == "tv") "TV" else "Movie"
        "$kind: ${r.title}" + (r.year?.let { " ($it)" } ?: "") + (r.season?.let { " · S$it" } ?: "")
    }
    else -> r.title
}

@Composable
private fun TaskRows(tasks: List<DashboardTask>, onOpenTab: (Tab) -> Unit, muted: Boolean = false) {
    tasks.forEach { task ->
        val due = task.dueDate?.let { dueLabel(it) }
        val urgent = !muted && (task.priority == "urgent" || task.priority == "high")
        Row2(
            title = task.title,
            subtitle = listOfNotNull(due?.first, task.place).joinToString(" · ").ifEmpty { null },
            subtitleIsError = due?.second == true,
            trailing = if (urgent) task.priority else null,
            trailingColor = if (task.priority == "urgent") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            muted = muted,
            onClick = { onOpenTab(Tab.Tasks) },
        )
    }
}

@Composable
private fun PointsCard(points: DashboardPoints, onOpenPoints: () -> Unit) {
    DashCard("This week", action = "Points" to onOpenPoints) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatPoints(points.week.centi()),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                " pts",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            (points.rank?.let { "#$it in the house" } ?: "No points yet this week") +
                (if (points.queued > 0) " · ${formatPoints(points.queued.centi())} waiting on unfinished tasks" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (points.leaders.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            points.leaders.forEachIndexed { i, m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${i + 1}.  ${m.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        formatPoints(m.points.centi()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HouseholdGlance(data: Dashboard, features: Features, onOpenTab: (Tab) -> Unit) {
    val c = data.counts
    val error = MaterialTheme.colorScheme.error
    val stats = listOfNotNull(
        Stat("Overdue tasks", c.overdue, Tab.Tasks, if (c.overdue > 0) error else null).takeIf { features.tasks },
        Stat("Low stock", c.lowInventory, Tab.Inventory, if (c.lowInventory > 0) error else null)
            .takeIf { features.inventory },
        Stat("To buy", c.openShopping, Tab.Shopping).takeIf { features.shopping },
        Stat("Bills due (7d)", c.billsDue, Tab.Bills, if (c.billsDue > 0) error else null).takeIf { features.bills },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SubHeading("Around the house")
        stats.chunked(2).forEach { row ->
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

/** Calendar events (next 7 days) and unpaid bills, in date order. */
@Composable
private fun ComingUpCard(data: Dashboard, features: Features, onOpenTab: (Tab) -> Unit) {
    data class Item(val at: Instant, val title: String, val subtitle: String, val late: Boolean, val trailing: String?, val tab: Tab?)
    val time = DateTimeFormatter.ofPattern("h:mm a")
    val items = (
        data.upcomingEvents.map { e ->
            val day = dueLabel(e.start, prefix = "").first.trim().replaceFirstChar(Char::uppercase)
            val at = Instant.parse(e.start)
            val clock = if (e.allDay) "All day" else at.atZone(ZoneId.systemDefault()).format(time)
            // The app has no Calendar tab yet; events aren't tappable.
            Item(at, e.title, listOfNotNull("$day · $clock", e.location).joinToString(" · "), false, null, null)
        } + data.upcomingBills.map { b ->
            val due = b.dueDate?.let { dueLabel(it) }
            Item(
                b.dueDate?.let(Instant::parse) ?: Instant.MAX,
                b.name,
                due?.first ?: "No due date",
                due?.second == true,
                formatMoney(b.amount, b.currency),
                Tab.Bills,
            )
        }
        ).sortedBy { it.at }.take(8)

    DashCard("Coming up") {
        if (items.isEmpty()) {
            Text(
                when {
                    features.calendar && features.bills -> "No events this week and no unpaid bills."
                    features.calendar -> "No events this week."
                    else -> "No unpaid bills."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items.forEach { item ->
            Row2(
                title = item.title,
                subtitle = item.subtitle,
                subtitleIsError = item.late,
                trailing = item.trailing,
                onClick = item.tab?.let { tab -> { onOpenTab(tab) } },
            )
        }
    }
}

@Composable
private fun DashCard(
    title: String,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** One tappable line inside a card: title, optional subtitle and right-hand label. */
@Composable
private fun Row2(
    title: String,
    subtitle: String? = null,
    subtitleIsError: Boolean = false,
    trailing: String? = null,
    trailingColor: Color = MaterialTheme.colorScheme.onSurface,
    muted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = trailingColor, modifier = Modifier.padding(start = 12.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
