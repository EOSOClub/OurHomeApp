package com.eosoclub.ourhome.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.USAGE_CHART_DAYS
import com.eosoclub.ourhome.data.USAGE_TOP_APPS
import com.eosoclub.ourhome.data.UsageHistory
import com.eosoclub.ourhome.data.UsageTopApp
import com.eosoclub.ourhome.data.averageMinutes
import com.eosoclub.ourhome.data.chartDays
import com.eosoclub.ourhome.data.formatMinutes
import com.eosoclub.ourhome.data.usageDayLabel
import com.eosoclub.ourhome.data.weekdayLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Someone's app usage over the last week: a bar per day (tap one to pick it),
// then every app kept for the picked day. Shown to a supervisor on "People
// you look after" and to the child on their own Profile (web: usage-history.tsx).

/** Height of the tallest bar. */
private val BAR_HEIGHT = 96.dp

/** App icons by package name, loaded once each in [scope]; null = the server has none. */
internal class UsageIcons(private val api: ApiClient, private val scope: CoroutineScope) {
    val icons = MutableStateFlow<Map<String, ImageBitmap?>>(emptyMap())
    private val asked = mutableSetOf<String>()

    /** Loads [app]'s icon unless it was already asked for. */
    fun load(app: String) {
        if (!asked.add(app)) return
        scope.launch {
            val bitmap = runCatching {
                api.usageIcon(app)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
            icons.update { it + (app to bitmap) }
            // A failed load may try again on the next visit.
            if (bitmap == null) asked.remove(app)
        }
    }
}

/** An app's icon from their phone, or its first letter until the phone has sent it ([hasIcon]: the server has one). */
@Composable
internal fun AppIconBox(app: String, label: String, hasIcon: Boolean, icons: Map<String, ImageBitmap?>, loadIcon: (String) -> Unit) {
    if (hasIcon && app.isNotEmpty()) LaunchedEffect(app) { loadIcon(app) }
    val icon = icons[app]
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)))
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                label.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What goes at the end of an app's row (a supervisor's Block), if anything. */
internal typealias AppRowAction = @Composable (UsageTopApp) -> Unit

/** One app: its icon (or first letter until their phone sent it), name and time, then [action]'s part. */
@Composable
internal fun UsageAppRow(
    app: UsageTopApp,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    action: AppRowAction? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // With a button, its own touch area makes the row's height and end gap.
        modifier = Modifier.fillMaxWidth().padding(top = if (action == null) 4.dp else 0.dp, end = if (action == null) 8.dp else 0.dp),
    ) {
        AppIconBox(app.app, app.label, app.icon, icons, loadIcon)
        Text(
            app.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Text(
            formatMinutes(app.minutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke(app)
    }
}

/** The week's chart, then every app of the picked day (the newest until another is tapped). */
@Composable
internal fun UsageHistoryView(
    history: UsageHistory,
    icons: Map<String, ImageBitmap?>,
    loadIcon: (String) -> Unit,
    action: AppRowAction? = null,
) {
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val today = java.time.LocalDate.now().toString()
    // The picked day may have aged out since.
    val shown = history.days.firstOrNull { it.day == picked } ?: history.days.firstOrNull()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (shown == null) {
        Text("No app usage from the phone yet.", style = MaterialTheme.typography.bodySmall, color = muted)
        return
    }
    val chart = chartDays(history.days, today)
    val tallest = maxOf(60, chart.maxOf { it.minutes ?: 0 })
    val average = averageMinutes(chart)

    // No end padding here: each app row has its own, so the times line up with the top three's.
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "Last $USAGE_CHART_DAYS days" + (average?.let { " · about ${formatMinutes(it)} a day" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chart.forEach { c ->
                val selected = c.day == shown.day
                val label = "${usageDayLabel(c.day, today)}: ${c.minutes?.let(::formatMinutes) ?: "nothing from the phone"}"
                val minutes = c.minutes
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = minutes != null, onClickLabel = "Show this day") { picked = c.day }
                        .semantics(mergeDescendants = true) {
                            contentDescription = label
                            this.selected = selected
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(BAR_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                        val height = if (minutes == null || minutes == 0) 2.dp else (BAR_HEIGHT * (minutes.toFloat() / tallest)).coerceAtLeast(3.dp)
                        Box(
                            Modifier
                                .widthIn(max = 32.dp)
                                .fillMaxWidth()
                                .height(height)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    when {
                                        minutes == null -> MaterialTheme.colorScheme.outlineVariant
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                    },
                                ),
                        )
                    }
                    Text(
                        weekdayLabel(c.day),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else muted,
                    )
                }
            }
        }
        Text(
            "${usageDayLabel(shown.day, today)}: ${formatMinutes(shown.totalMinutes)}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (shown.apps.isEmpty()) {
            Text("No app was open for a full minute.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        shown.apps.forEach { app -> UsageAppRow(app, icons, loadIcon, action) }
        if (shown.apps.size >= USAGE_TOP_APPS) {
            Text(
                "Only the $USAGE_TOP_APPS most used apps are kept for each day.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
