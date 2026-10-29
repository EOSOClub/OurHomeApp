package com.eosoclub.ourhome.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.AppRelease
import com.eosoclub.ourhome.data.ChildSetupChoices
import com.eosoclub.ourhome.data.ChildSetupStep
import com.eosoclub.ourhome.data.LookedAfter
import com.eosoclub.ourhome.data.PhoneRule
import com.eosoclub.ourhome.data.PhoneRules
import com.eosoclub.ourhome.data.SetupLink
import com.eosoclub.ourhome.data.SetupLinkQr
import com.eosoclub.ourhome.data.Supervision
import com.eosoclub.ourhome.data.emailProblem
import com.eosoclub.ourhome.data.joinNames
import com.eosoclub.ourhome.data.passwordProblem
import com.eosoclub.ourhome.data.setupComplete
import com.eosoclub.ourhome.data.stepForFieldErrors
import com.eosoclub.ourhome.data.usernameProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Set up a child (web /members/add-child): the Head of House adds a child or
// teen, chooses who supervises them and, when the Head is one of them, what
// their phone must do; then gets the steps for installing the app on the
// child's phone (a setup link's QR code when the server has built the app),
// and watches that phone tick off Set up this phone. Nothing
// is made until the third step's button. The choices are a supervisor's (the
// server refuses them otherwise), so a Head who doesn't supervise them skips
// them and leaves the defaults to the supervisors. Opened from Household. The
// ViewModel outlives the screen, so leaving and coming back carries on where
// it was; Done and Set up another child start again.

/** While on the last step: their phone checks in after each step, so look often. */
private const val PROGRESS_REFRESH_MS = 5_000L

class AddChildViewModel(private val api: ApiClient, private val actorId: String) : ViewModel() {
    /** The account being typed in, and the choices for their phone. */
    data class Form(
        val name: String = "",
        val username: String = "",
        val email: String = "",
        val role: String = "child",
        val password: String = "",
        val supervisorIds: List<String> = emptyList(),
        val choices: ChildSetupChoices = ChildSetupChoices(),
    )

    /** The member just added. [password]: the temporary one, kept to show on Installation. */
    data class Made(val id: String, val name: String, val username: String, val password: String, val supervisorIds: List<String>)

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        /** Who may supervise (Head of House only). */
        val supervision: Supervision? = null,
        /** The app this server built; null: none (or not known). */
        val release: AppRelease? = null,
        val step: ChildSetupStep = ChildSetupStep.Account,
        val form: Form = Form(),
        val made: Made? = null,
        /** Adding them is in flight. */
        val busy: Boolean = false,
        /** The fields the server said were wrong, each until it's edited. */
        val fieldErrors: Map<String, String> = emptyMap(),
        /** Their row from People you look after, for Progress; null until loaded or when not supervised. */
        val progress: LookedAfter? = null,
        val progressLoaded: Boolean = false,
        /** The setup link (QR code) for their phone, on Install; null until made. */
        val setupLink: SetupLink? = null,
        val setupLinkBusy: Boolean = false,
        val setupLinkError: String? = null,
    )

    val state = MutableStateFlow(UiState())

    fun load() = viewModelScope.launch {
        state.update { it.copy(loading = true, error = null) }
        try {
            coroutineScope {
                val releaseCall = async { runCatching { api.appRelease() }.getOrNull() }
                val supervision = api.supervision()
                val release = releaseCall.await()
                state.update { s ->
                    s.copy(
                        supervision = supervision,
                        release = release,
                        // The Head is ticked to start with, when they may supervise.
                        form = if (s.form == Form()) s.form.copy(supervisorIds = startingSupervisors(supervision)) else s.form,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(error = e.message ?: "Couldn't load who can supervise") }
        } finally {
            state.update { it.copy(loading = false) }
        }
    }

    private fun startingSupervisors(supervision: Supervision?): List<String> =
        listOf(actorId).filter { id -> supervision?.supervisors?.any { it.id == id } == true }

    /** Changes the form; what the server said about the fields in [keys] no longer applies. */
    fun edit(vararg keys: String, change: (Form) -> Form) = state.update {
        it.copy(form = change(it.form), fieldErrors = it.fieldErrors - keys.toSet())
    }

    fun goTo(step: ChildSetupStep) = state.update { it.copy(step = step) }

    /** Their phone's choices are a supervisor's to make. */
    fun choosing(s: UiState = state.value) = actorId in s.form.supervisorIds

    fun isSupervisor(made: Made) = actorId in made.supervisorIds

    /** Adds them; on success on to Install, otherwise back to the step with the field the server named. */
    fun create(locationOn: Boolean, showMessage: (String) -> Unit) = viewModelScope.launch {
        val s = state.value
        if (s.busy) return@launch
        val form = s.form
        state.update { it.copy(busy = true) }
        try {
            val member = api.createMember(
                form.name.trim(),
                form.username.trim(),
                form.email.trim().ifEmpty { null },
                form.role,
                form.password,
                form.supervisorIds,
                setup = if (choosing(s)) {
                    form.choices.copy(locationRequired = locationOn && form.choices.locationRequired)
                } else {
                    null
                },
            )
            state.update {
                it.copy(
                    made = Made(member.id, member.name, member.username ?: form.username.trim(), form.password, form.supervisorIds),
                    step = ChildSetupStep.Install,
                    fieldErrors = emptyMap(),
                    progress = null,
                    progressLoaded = false,
                    setupLink = null,
                    setupLinkError = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fields = (e as? ApiException)?.fieldErrors.orEmpty()
            state.update { it.copy(fieldErrors = fields, step = stepForFieldErrors(fields.keys) ?: it.step) }
            showMessage(e.message ?: "Couldn't add them")
        } finally {
            state.update { it.copy(busy = false) }
        }
    }

    /** The first setup link for them, unless one is made, being made, or failed (then the button tries again). */
    fun makeSetupLinkOnce() {
        val s = state.value
        if (s.setupLink == null && !s.setupLinkBusy && s.setupLinkError == null) makeSetupLink()
    }

    /** A new setup link for them; the server drops the last one. */
    fun makeSetupLink() {
        val made = state.value.made ?: return
        if (state.value.setupLinkBusy) return
        state.update { it.copy(setupLinkBusy = true, setupLinkError = null) }
        viewModelScope.launch {
            try {
                val link = api.createSetupLink(made.id)
                // Still the same child (Set up another child may have started meanwhile).
                state.update { if (it.made?.id == made.id) it.copy(setupLink = link) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    if (it.made?.id == made.id) it.copy(setupLinkError = e.message ?: "Couldn't make a setup link") else it
                }
            } finally {
                state.update { it.copy(setupLinkBusy = false) }
            }
        }
    }

    /** Their row from People you look after (the same list), for Progress. */
    fun loadProgress() = viewModelScope.launch {
        val made = state.value.made ?: return@launch
        state.load(
            fetch = { api.lookAfter() },
            onOk = { s, data -> s.copy(progress = data.people.firstOrNull { it.id == made.id }, progressLoaded = true) },
            // Keep what was shown; the next look tries again.
            onError = { s, _ -> s },
        )
    }

    /** A blank form for the next child, the Head ticked again. */
    fun startOver() = state.update {
        UiState(
            loading = false,
            supervision = it.supervision,
            release = it.release,
            form = Form(supervisorIds = startingSupervisors(it.supervision)),
        )
    }
}

@Composable
internal fun AddChildScreen(
    api: ApiClient,
    actorId: String,
    /** The address this app talks to, for when the server's app has none built in. */
    baseUrl: String,
    /** The household uses Location: "Location required" and People you look after exist. */
    locationOn: Boolean,
    showMessage: (String) -> Unit,
    onOpenLookAfter: () -> Unit,
    /** Back to Household. */
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel { AddChildViewModel(api, actorId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.supervision == null) vm.load() }

    // On Install, a setup link once (the server has the app to download); a new one only on request.
    LaunchedEffect(state.step, state.made?.id, state.release != null) {
        if (state.step == ChildSetupStep.Install && state.release != null) vm.makeSetupLinkOnce()
    }

    // On the last step, look again every few seconds while the screen shows.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.step, state.made?.id) {
        if (state.step != ChildSetupStep.Progress || state.made == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.loadProgress().join()
                delay(PROGRESS_REFRESH_MS)
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (loadState(state.loading, state.error, state.supervision == null, "") { vm.load() }) return@Box
        val supervision = state.supervision ?: return@Box
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Add a child or teen, choose who looks after them, then set up the OurHome app on their phone. " +
                    "Nothing is added until step 3.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Step ${state.step.ordinal + 1} of ${ChildSetupStep.entries.size}: ${state.step.label}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val made = state.made
                    when (state.step) {
                        ChildSetupStep.Account -> AccountStep(state, vm)
                        ChildSetupStep.Supervisors -> SupervisorsStep(state, vm, supervision)
                        ChildSetupStep.Phone -> PhoneStep(state, vm, supervision, locationOn, showMessage)
                        ChildSetupStep.Install -> if (made != null) {
                            InstallStep(
                                made,
                                address = state.release?.server?.ifEmpty { null } ?: baseUrl,
                                hasApp = state.release != null,
                                setupLink = state.setupLink,
                                setupLinkBusy = state.setupLinkBusy,
                                setupLinkError = state.setupLinkError,
                                onNewLink = vm::makeSetupLink,
                                watching = vm.isSupervisor(made),
                                supervisorNames = supervision.supervisors.filter { it.id in made.supervisorIds }.map { it.name },
                                locationOn = locationOn,
                                onNext = { vm.goTo(ChildSetupStep.Progress) },
                                onDone = { vm.startOver(); onDone() },
                                onAnother = vm::startOver,
                            )
                        }
                        ChildSetupStep.Progress -> if (made != null) {
                            ProgressStep(
                                made,
                                state,
                                locationOn = locationOn,
                                onBack = { vm.goTo(ChildSetupStep.Install) },
                                onOpenLookAfter = onOpenLookAfter,
                                onAnother = vm::startOver,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun kidName(form: AddChildViewModel.Form) = form.name.trim().ifEmpty { "them" }

@Composable
private fun AccountStep(state: AddChildViewModel.UiState, vm: AddChildViewModel) {
    val form = state.form
    // Checked as they type, by the server's own rules; what the server said wins.
    val errors = state.fieldErrors
    val nameError = errors["name"]
    val usernameError = errors["username"] ?: usernameProblem(form.username)
    val emailError = errors["email"] ?: emailProblem(form.email)
    val passwordError = errors["password"] ?: passwordProblem(form.password)
    val roleError = errors["role"]
    val ready = form.name.isNotBlank() && form.username.isNotBlank() && form.password.isNotEmpty() &&
        listOf(nameError, usernameError, emailError, passwordError, roleError).all { it == null }

    StepTitle("Their account", "They sign in with this username and the temporary password, then choose their own.")
    OutlinedTextField(
        value = form.name,
        onValueChange = { v -> vm.edit("name") { it.copy(name = v.take(NAME_MAX)) } },
        label = { Text("Name") },
        placeholder = { Text("e.g. Mia") },
        singleLine = true,
        isError = nameError != null,
        supportingText = nameError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    UsernameField(form.username, usernameError) { v -> vm.edit("username") { it.copy(username = v) } }
    EmailField(form.email, emailError, optional = true) { v -> vm.edit("email") { it.copy(email = v) } }
    FieldLabel("Role")
    ChoiceChips(listOf("child", "teen"), form.role) { v -> vm.edit("role") { it.copy(role = v) } }
    FieldError(roleError)
    TempPasswordField(form.password, "Temporary password", passwordError) { v -> vm.edit("password") { it.copy(password = v) } }
    Button(onClick = { vm.goTo(ChildSetupStep.Supervisors) }, enabled = ready) { Text("Next") }
}

@Composable
private fun SupervisorsStep(state: AddChildViewModel.UiState, vm: AddChildViewModel, supervision: Supervision) {
    val form = state.form
    val error = state.fieldErrors["supervisorIds"]
    StepTitle("Who supervises ${kidName(form)}", SUPERVISION_HINT)
    SupervisorChecks(
        supervision.supervisors,
        checked = form.supervisorIds,
        enabled = true,
        onToggle = { id ->
            vm.edit("supervisorIds") {
                it.copy(supervisorIds = if (id in it.supervisorIds) it.supervisorIds - id else it.supervisorIds + id)
            }
        },
    )
    when {
        error != null -> Hint(error, error = true)
        form.supervisorIds.isEmpty() -> Hint("Tick at least one: their phone is only set up for the adults who look after them.")
        !vm.choosing(state) -> Hint("You haven't ticked yourself, so their supervisors choose what their phone must do.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.goTo(ChildSetupStep.Account) }) { Text("Back") }
        Button(onClick = { vm.goTo(ChildSetupStep.Phone) }, enabled = form.supervisorIds.isNotEmpty()) { Text("Next") }
    }
}

private val RULE_HINTS = mapOf(
    PhoneRule.KeepRunning to "Stops OurHome being closed on their phone.",
    PhoneRule.PreventUninstall to "Stops OurHome being removed without the parent PIN. A parent sets the PIN on their phone.",
    PhoneRule.BatteryUnrestricted to "Stops battery saving from holding back rings and alerts.",
)

private fun PhoneRules.with(rule: PhoneRule, on: Boolean): PhoneRules = when (rule) {
    PhoneRule.KeepRunning -> copy(keepRunning = on)
    PhoneRule.PreventUninstall -> copy(preventUninstall = on)
    PhoneRule.BatteryUnrestricted -> copy(batteryUnrestricted = on)
}

@Composable
private fun PhoneStep(
    state: AddChildViewModel.UiState,
    vm: AddChildViewModel,
    supervision: Supervision,
    locationOn: Boolean,
    showMessage: (String) -> Unit,
) {
    val form = state.form
    val kid = kidName(form)
    val choices = form.choices
    StepTitle(
        "What $kid's phone must do",
        "Each one turned on is a step in Set up this phone in the app on their phone, and you're told if it gets " +
            "turned off later. You can change these any time on People you look after.",
    )
    if (vm.choosing(state)) {
        Text(
            "Their phone always has to allow notifications, so alerts reach them and a ring can be stopped.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (locationOn) {
            ChoiceSwitch(
                "Location required",
                "Their location sharing stays on, and you can check where they are on the Map without them allowing " +
                    "it. They always see when you check.",
                choices.locationRequired,
            ) { on -> vm.edit("setup") { it.copy(choices = it.choices.copy(locationRequired = on)) } }
        }
        ChoiceSwitch(
            "Share app usage",
            "Shows their supervisors how long they spend in each app. Their phone needs Usage access for it.",
            choices.shareAppUsage,
        ) { on -> vm.edit("setup") { it.copy(choices = it.choices.copy(shareAppUsage = on)) } }
        PhoneRule.entries.forEach { rule ->
            ChoiceSwitch("Require ${rule.label}", RULE_HINTS.getValue(rule), rule.of(choices.rules)) { on ->
                vm.edit("setup") { it.copy(choices = it.choices.copy(rules = it.choices.rules.with(rule, on))) }
            }
        }
    } else {
        val names = supervision.supervisors.filter { it.id in form.supervisorIds }.map { it.name }
        Hint(
            "Only the adults who supervise $kid choose these, and you haven't ticked yourself. Until " +
                "${joinNames(names, "or")} changes them on People you look after, their phone must allow " +
                "notifications, unrestricted battery, Prevent uninstall and Keep Our Home running. Their location " +
                "sharing stays their own choice, and their app usage isn't shared.",
        )
    }
    state.fieldErrors["setup"]?.let { Hint(it, error = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.goTo(ChildSetupStep.Supervisors) }, enabled = !state.busy) { Text("Back") }
        Button(onClick = { vm.create(locationOn, showMessage) }, enabled = !state.busy) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Add $kid")
            }
        }
    }
}

/** A choice for their phone: a label, what it does, and its switch (the whole row toggles it). */
@Composable
private fun ChoiceSwitch(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Light modules around the code, which phone cameras need. */
private const val QR_QUIET = 4

/**
 * A setup link's QR code, drawn from the server's modules: always dark on
 * white whatever the theme, so a phone camera reads it.
 */
@Composable
private fun QrCode(qr: SetupLinkQr, description: String) {
    val full = qr.size + QR_QUIET * 2
    Canvas(
        Modifier
            .size(176.dp)
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = description },
    ) {
        val cell = size.minDimension / full
        drawRect(Color.White)
        qr.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + QR_QUIET) * cell, (y + QR_QUIET) * cell),
                        // A hair over one cell, so no seams show between neighbours.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** A value to type on their phone, in a fixed-width font so 0/O and 1/l are told apart, with Copy. */
@Composable
private fun CopyRow(label: String, value: String, sensitive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        }
        CopyButton(value, sensitive = sensitive, clipLabel = label)
    }
}

/** One numbered instruction; [extra] goes under its text. */
@Composable
private fun Numbered(number: Int, text: String, extra: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$number.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            extra()
        }
    }
}

@Composable
private fun InstallStep(
    made: AddChildViewModel.Made,
    /** Where their phone signs in: the address built into the server's app, else this app's. */
    address: String,
    /** The server has built the app, so there's a setup link to download it. */
    hasApp: Boolean,
    setupLink: SetupLink?,
    setupLinkBusy: Boolean,
    setupLinkError: String?,
    onNewLink: () -> Unit,
    /** This adult supervises them, so can watch their phone on the next step. */
    watching: Boolean,
    supervisorNames: List<String>,
    locationOn: Boolean,
    onNext: () -> Unit,
    onDone: () -> Unit,
    onAnother: () -> Unit,
) {
    StepTitle(
        "Put OurHome on ${made.name}'s phone",
        "${made.name} is added. Do these on their phone, with them, because a parent sets the PIN in the last part.",
    )
    val lastStep = "Set up this phone opens by itself. Do each step on their phone. For Prevent uninstall you " +
        "choose the parent PIN, so keep it to yourself."
    if (hasApp) {
        // A setup link: their phone downloads the app without signing in, then opens it with their username in.
        Numbered(1, "On their phone, scan this code with the camera, or open the link.") {
            when {
                setupLink != null -> {
                    QrCode(setupLink.qr, "QR code for setting up ${made.name}'s phone")
                    CopyRow("Setup link", setupLink.url, sensitive = false)
                    Hint(
                        "It works for 24 hours, until you make a new one, or until they choose their own " +
                            "password. It only downloads the app: their account still needs the password.",
                    )
                }
                setupLinkError != null -> Hint(setupLinkError, error = true)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Hint("Making a setup link…")
                }
            }
            TextButton(onClick = onNewLink, enabled = !setupLinkBusy) {
                Text(if (setupLink != null) "Make a new link" else "Try again")
            }
        }
        Numbered(
            2,
            "Tap Download for Android on the page that opens, then open the downloaded file to install it. " +
                "Android asks once to allow installs from the browser.",
        )
        Numbered(
            3,
            "Back on that page, tap Open Our Home: their username, ${made.username}, is filled in. They sign in " +
                "with the temporary password and choose their own straight away.",
        ) {
            CopyRow("Temporary password", made.password, sensitive = true)
        }
        Numbered(4, lastStep)
        Hint(
            "No camera? Open $address in the browser on their phone, sign in as ${made.username}, and tap " +
                "Download for Android on their Profile.",
        )
    } else {
        Numbered(1, "Open this address in the browser on their phone and sign in:") {
            CopyRow("Address", address, sensitive = false)
            CopyRow("Username", made.username, sensitive = false)
            CopyRow("Temporary password", made.password, sensitive = true)
            Hint("They choose their own password straight away.")
        }
        Numbered(
            2,
            "Get the OurHome app: this server hasn't built it yet, so there's no download on the Profile page. " +
                "Ask whoever runs your server.",
        )
        Numbered(3, "Open the downloaded file to install it. Android asks once to allow installs from the browser.")
        Numbered(4, "Open Our Home and sign in with their new password. If it asks for the server, type the address above.")
        Numbered(5, lastStep)
    }
    if (!watching) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                "You don't supervise ${made.name}, so you won't see their phone here. ${joinNames(supervisorNames, "and")} " +
                    if (locationOn) "can follow how far it got on People you look after." else "are told if anything is turned off later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (watching) {
            Button(onClick = onNext) { Text("Next: watch their phone") }
        } else {
            Button(onClick = onDone) { Text("Done") }
        }
        TextButton(onClick = onAnother) { Text("Set up another child") }
    }
}

@Composable
private fun ProgressStep(
    made: AddChildViewModel.Made,
    state: AddChildViewModel.UiState,
    locationOn: Boolean,
    onBack: () -> Unit,
    onOpenLookAfter: () -> Unit,
    onAnother: () -> Unit,
) {
    val person = state.progress
    val complete = person?.setupComplete() == true
    StepTitle(
        "${made.name}'s phone",
        "This fills in as their phone checks in, a few seconds after each step. You can leave this screen: " +
            if (locationOn) "it's also under their name on People you look after." else "coming back here shows it again.",
    )
    when {
        !state.progressLoaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Hint("Loading…")
        }
        person == null -> Hint("You no longer supervise ${made.name}, so their phone isn't shown here.")
        person.setup == null -> Hint("This server is too old to show their phone's progress. Update OurHome on the server.")
        else -> SetupSteps(person)
    }
    if (complete) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Text(
                "${made.name}'s phone is set up.",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (locationOn) {
            if (complete) {
                Button(onClick = onOpenLookAfter) { Text("People you look after") }
            } else {
                OutlinedButton(onClick = onOpenLookAfter) { Text("People you look after") }
            }
        }
        TextButton(onClick = onBack) { Text("Install steps") }
        TextButton(onClick = onAnother) { Text("Set up another child") }
    }
}
