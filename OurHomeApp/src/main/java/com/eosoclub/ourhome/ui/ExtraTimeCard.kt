package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eosoclub.ourhome.data.MyExtraTime
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.buyOptions
import com.eosoclub.ourhome.data.formatMinutes
import com.eosoclub.ourhome.data.formatPoints
import com.eosoclub.ourhome.data.pointsLabel
import com.eosoclub.ourhome.data.rateLabel
import com.eosoclub.ourhome.notifications.PhoneCheckIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Profile: while an adult who looks after this member lets them spend points
 * on screen time (web pointsTimeService), their points to spend, what a point
 * buys, and buttons to buy more time on today's limits. A purchase checks in
 * at once, so this phone's limits grow straight away. Nothing shows otherwise.
 * The blocked screen's Get more time opens Profile here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtraTimeCard(session: SessionManager, showMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mine by remember { mutableStateOf<MyExtraTime?>(null) }
    var buying by remember { mutableStateOf<Int?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    // Again on return (from the blocked screen, or after a day changes).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(resumes) {
        runCatching { session.api.myExtraTime(LocalDate.now().toString()) }.onSuccess { mine = it }
    }
    val now = mine ?: return
    val rate = now.minutesPerPoint ?: return
    val cap = now.dailyCap ?: return
    val left = (cap - now.bought).coerceAtLeast(0)
    val options = if (now.available) buyOptions(now.balanceCenti, rate, cap, now.bought) else emptyList()

    SectionCard("More screen time", "Spend your task points on more time on today's limits.") {
        Text(
            "You have ${formatPoints(now.balanceCenti.coerceAtLeast(0))} points to spend. ${rateLabel(rate)}, up to " +
                "${formatMinutes(cap)} a day." +
                (if (now.bought > 0) " You've bought ${formatMinutes(now.bought)} today." else "") +
                (if (now.given > 0) " An adult gave you ${formatMinutes(now.given)} today." else ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            !now.hasLimits -> Note("You have no daily limits right now, so there's nothing to add time to.")
            left == 0 -> Note("You've bought all the time you can today.")
            options.isEmpty() -> Note("You don't have enough points for more time yet. Finishing tasks earns them.")
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    OutlinedButton(
                        enabled = buying == null,
                        onClick = {
                            buying = option.minutes
                            scope.launch {
                                try {
                                    mine = session.api.buyExtraTime(option.minutes, now.day.ifEmpty { LocalDate.now().toString() })
                                    // This phone learns the new minutes now, not at the next check-in.
                                    PhoneCheckIn.sendNow(context)
                                    showMessage("${formatMinutes(option.minutes)} more on your limits today")
                                } catch (e: Exception) {
                                    showMessage(e.message ?: "Couldn't buy it")
                                } finally {
                                    buying = null
                                }
                            }
                        },
                    ) { Text("+${formatMinutes(option.minutes)} · ${pointsLabel(option.costCenti)}") }
                }
            }
        }
        Note("Spending doesn't change the points you've earned in the stats. The adults who look after you are told.")
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
