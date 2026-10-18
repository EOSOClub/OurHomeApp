package com.eosoclub.ourhome.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eosoclub.ourhome.data.LookedAfter
import com.eosoclub.ourhome.data.setupStepNote
import com.eosoclub.ourhome.data.setupStepTitle
import com.eosoclub.ourhome.data.setupSummary

// How far a supervised child's phone has got with Set up this phone, from its
// check-ins (web components/look-after/setup-progress.tsx): their own
// password, the app signed in, then each step their supervisors require, in
// the app's order. Their phone checks in after each step, so this fills in as
// they go. Shown open on Set up a child's last step, and folded under each
// name on People you look after.

/** The list itself; nothing from an older server. */
@Composable
internal fun SetupSteps(person: LookedAfter) {
    val setup = person.setup ?: return
    val phone = person.phone
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupStepRow(
            done = setup.passwordChosen,
            label = "Chose their own password",
            detail = if (setup.passwordChosen) null else "They do this the first time they sign in.",
        )
        SetupStepRow(
            done = phone != null,
            label = "Signed in to the OurHome app",
            detail = phone?.let { listOfNotNull(it.deviceName, "checked in ${relativeTime(it.lastSeenAt)}").joinToString(", ") }
                ?: "Waiting for their phone to check in.",
        )
        setup.steps.forEach { step ->
            SetupStepRow(
                done = step.done == true,
                label = setupStepTitle(step.step),
                detail = setupStepNote(step, hasPhone = phone != null),
            )
        }
    }
}

@Composable
private fun SetupStepRow(done: Boolean, label: String, detail: String?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (done) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        } else {
            // An empty ring the size of the tick (the core icon set has no outline circle).
            Box(Modifier.size(20.dp).semantics { contentDescription = "Not done yet" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(16.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Folded: "Set up this phone: 3 of 5 done", tapped open to the list. Nothing from an older server. */
@Composable
internal fun SetupProgress(person: LookedAfter) {
    val summary = person.setupSummary() ?: return
    var open by rememberSaveable(person.id) { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { open = !open }
                .padding(vertical = 6.dp),
        ) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Hide the steps" else "Show the steps",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) { SetupSteps(person) }
        }
    }
}
