package com.eosoclub.ourhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import com.eosoclub.ourhome.data.PageAccess
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.Bill
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

class BillsViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val bills: List<Bill> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val busy: Set<String> = emptySet(),
        val editing: Bill? = null,
        val saving: Boolean = false,
    )

    val state = MutableStateFlow(UiState())

    /** The open editor's last save failure: why, and which fields to mark. */
    val form = FormErrors()

    /** The pay dialog's last failure, shown in it (it stays open until the payment is recorded). */
    val payForm = FormErrors()

    fun startEdit(bill: Bill) {
        form.clear()
        state.update { it.copy(editing = bill) }
    }

    fun cancelEdit() {
        form.clear()
        state.update { it.copy(editing = null) }
    }

    fun save(bill: Bill, name: String, amount: Double, dueDate: Instant?, autoPay: Boolean, notes: String?) =
        viewModelScope.launch {
            state.update { it.copy(saving = true) }
            form.clear()
            try {
                api.updateBill(bill.id, name, amount, dueDate, autoPay, notes)
                state.update { it.copy(editing = null) }
                refresh()
            } catch (e: Exception) {
                // Shown in the editor, which sits over the snackbar.
                form.fail(e, "Couldn't save bill")
            } finally {
                state.update { it.copy(saving = false) }
            }
        }

    fun delete(bill: Bill) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        form.clear()
        try {
            api.deleteBill(bill.id)
            state.update { s -> s.copy(bills = s.bills.filterNot { it.id == bill.id }, editing = null) }
            _messages.send("Deleted ${bill.name}")
        } catch (e: Exception) {
            form.fail(e, "Couldn't delete bill")
        } finally {
            state.update { it.copy(saving = false) }
        }
    }
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load({ api.bills() }, { s, v -> s.copy(bills = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
    }

    /** [amount] null pays the remaining balance. [onDone] (close the dialog) only when it worked. */
    fun pay(bill: Bill, amount: Double?, onDone: () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + bill.id) }
        payForm.clear()
        try {
            api.payBill(bill.id, amount)
            onDone()
            // Reload: a recurring bill may roll forward to its next due date.
            refresh().join()
            _messages.send("Recorded ${formatMoney(amount ?: bill.remaining, bill.currency)} for ${bill.name}")
        } catch (e: Exception) {
            // Shown in the pay dialog, which stays open with the amount to fix.
            payForm.fail(e, "Couldn't record payment")
        } finally {
            state.update { it.copy(busy = it.busy - bill.id) }
        }
    }
}

@Composable
fun BillsScreen(
    api: ApiClient,
    access: PageAccess,
    userId: String,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { BillsViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The editor also holds Delete, so either opens it.
    val editHandler = { bill: Bill ->
        if (access.canEdit(bill.createdById, userId) || access.canDelete(bill.createdById, userId)) {
            { vm.startEdit(bill) }
        } else {
            null
        }
    }

    state.editing?.let { bill ->
        BillEditor(
            bill = bill,
            saving = state.saving,
            errors = vm.form,
            canSave = access.canEdit(bill.createdById, userId),
            onDismiss = vm::cancelEdit,
            onSave = { name, amount, due, autoPay, notes -> vm.save(bill, name, amount, due, autoPay, notes) },
            onDelete = if (access.canDelete(bill.createdById, userId)) ({ vm.delete(bill) }) else null,
        )
    }
    var paying by remember { mutableStateOf<Bill?>(null) }
    var showAllPaid by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    val unpaid = state.bills.filter { it.status != "paid" }
        .sortedWith(compareBy(nullsLast()) { it.dueDate?.let(Instant::parse) })
    val paid = state.bills.filter { it.status == "paid" }
        .sortedWith(compareByDescending(nullsFirst()) { it.dueDate?.let(Instant::parse) })
    val unpaidTotal = unpaid.sumOf { it.remaining }

    PullToRefreshBox(
        isRefreshing = state.loading && state.bills.isNotEmpty(),
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (loadState(state.loading, state.error, state.bills.isEmpty(), "No bills yet.", vm::refresh)) {
            return@PullToRefreshBox
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "summary") {
                Text(
                    if (unpaid.isEmpty()) "All bills are paid."
                    else "${unpaid.size} unpaid · ${formatMoney(unpaidTotal, unpaid.first().currency)} outstanding",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(unpaid, key = { it.id }) { bill ->
                BillCard(
                    bill,
                    unpaid = true,
                    busy = bill.id in state.busy,
                    // Recording a payment adds a record: Bills "Add".
                    onPay = if (access.create) ({ vm.payForm.clear(); paying = bill }) else null,
                    onEdit = editHandler(bill),
                )
            }
            if (paid.isNotEmpty()) {
                item(key = "paid-header") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Paid", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (paid.size > 5) {
                            TextButton(onClick = { showAllPaid = !showAllPaid }) {
                                Text(if (showAllPaid) "Show fewer" else "Show all ${paid.size}")
                            }
                        }
                    }
                }
                items(if (showAllPaid) paid else paid.take(5), key = { it.id }) { bill ->
                    BillCard(
                        bill,
                        unpaid = false,
                        busy = false,
                        onPay = null,
                        onEdit = editHandler(bill),
                    )
                }
            }
        }
    }

    paying?.let { bill ->
        PayDialog(
            bill = bill,
            busy = bill.id in state.busy,
            errors = vm.payForm,
            onDismiss = { paying = null },
            onPay = { amount -> vm.pay(bill, amount) { paying = null } },
        )
    }
}

@Composable
private fun BillCard(bill: Bill, unpaid: Boolean, busy: Boolean, onPay: (() -> Unit)?, onEdit: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(formatMoney(bill.amount, bill.currency), style = MaterialTheme.typography.titleMedium)
            }
            val due = bill.dueDate?.let { if (unpaid) dueLabel(it) else "Due ${formatDate(it)}" to false }
            val meta = listOfNotNull(
                due?.first,
                "Autopay".takeIf { bill.autoPay },
                bill.recurrence?.let { "Repeats ${it.kind}" },
                bill.assignee?.name,
                bill.category,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (due?.second == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val partial = unpaid && bill.paidTotal > 0 && bill.amount > 0
            if (partial) {
                LinearProgressIndicator(
                    progress = { (bill.paidTotal / bill.amount).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Paid ${formatMoney(bill.paidTotal, bill.currency)} · ${formatMoney(bill.remaining, bill.currency)} left",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (onPay != null || onEdit != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
                    if (onPay != null) {
                        FilledTonalButton(onClick = onPay, enabled = !busy) {
                            Text(if (busy) "Recording…" else "Record payment")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BillEditor(
    bill: Bill,
    saving: Boolean,
    /** The last save failure: said at the top, and the fields the server named marked. */
    errors: FormErrors,
    /** False when the user may only delete this bill, not change it. */
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: Double, dueDate: Instant?, autoPay: Boolean, notes: String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(bill.name) }
    var amount by remember { mutableStateOf(String.format(Locale.US, "%.2f", bill.amount)) }
    var due by remember { mutableStateOf(bill.dueDate?.let(::localDate)) }
    var autoPay by remember { mutableStateOf(bill.autoPay) }
    var notes by remember { mutableStateOf(bill.notes.orEmpty()) }
    val amountValue = amount.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val amountError = errors["amount"] ?: if (amountValue == null) "Enter an amount of 0 or more." else null

    EditorDialog(
        title = if (canSave) "Edit bill" else "Bill",
        saving = saving,
        saveEnabled = canSave && name.isNotBlank() && amountValue != null,
        onDismiss = onDismiss,
        onSave = { onSave(name.trim(), amountValue!!, dueInstant(due, bill.dueDate), autoPay, notes.trim().ifEmpty { null }) },
        deleteLabel = "Delete bill",
        onDelete = onDelete,
        error = errors.message,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; errors.clear("name") },
            label = { Text("Name") },
            singleLine = true,
            isError = errors["name"] != null,
            supportingText = errors["name"]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it; errors.clear("amount") },
            label = { Text("Amount") },
            singleLine = true,
            isError = amountError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = when {
                amountError != null -> { { Text(amountError) } }
                bill.paidTotal > 0 -> { { Text("Already paid: ${formatMoney(bill.paidTotal, bill.currency)}") } }
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Due date", due, errors["dueDate"]) { due = it; errors.clear("dueDate") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Autopay", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = autoPay, onCheckedChange = { autoPay = it; errors.clear("autoPay") })
        }
        FieldError(errors["autoPay"])
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; errors.clear("notes") },
            label = { Text("Notes") },
            minLines = 2,
            isError = errors["notes"] != null,
            supportingText = errors["notes"]?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Stays open until the payment is recorded: a refusal is said here, with the amount marked to fix. */
@Composable
private fun PayDialog(bill: Bill, busy: Boolean, errors: FormErrors, onDismiss: () -> Unit, onPay: (Double?) -> Unit) {
    // Locale.US so the prefilled value always parses back with toDoubleOrNull.
    val full = String.format(Locale.US, "%.2f", bill.remaining)
    var text by remember { mutableStateOf(full) }
    val amount = text.replace(',', '.').toDoubleOrNull()
    val valid = amount != null && amount > 0
    val amountError = errors["amount"] ?: if (!valid) "Enter an amount above 0." else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay ${bill.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                errors.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Text("Remaining: ${formatMoney(bill.remaining, bill.currency)}")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; errors.clear("amount") },
                    label = { Text("Amount") },
                    singleLine = true,
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            // Paying the exact remainder sends no amount so the server settles it fully.
            TextButton(onClick = { onPay(if (text == full) null else amount) }, enabled = valid && !busy) {
                Text(if (busy) "Recording…" else "Record")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
