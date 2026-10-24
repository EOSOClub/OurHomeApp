package com.eosoclub.ourhome.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import com.eosoclub.ourhome.BuildConfig
import com.eosoclub.ourhome.data.AppRelease
import com.eosoclub.ourhome.data.AppUpdate
import com.eosoclub.ourhome.data.Member
import com.eosoclub.ourhome.data.ProfileOverview
import com.eosoclub.ourhome.data.ProfileStyle
import com.eosoclub.ourhome.data.PublicProfile
import com.eosoclub.ourhome.data.emailProblem
import com.eosoclub.ourhome.data.passwordProblem
import com.eosoclub.ourhome.data.usernameProblem
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import com.eosoclub.ourhome.data.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Mirror the web's validation (src/lib/validation/user.ts) so errors show
// before a round trip; the server stays the authority. Usernames and emails
// are checked in data/FieldChecks.kt, in the server's words.
internal const val MIN_PASSWORD = 8

internal val ROLE_LABELS = mapOf(
    "head" to "Head of household",
    "manager" to "Manager",
    "adult" to "Adult",
    // The adult role's name on servers from before 2026-10-11.
    "member" to "Adult",
    "teen" to "Teen",
    "child" to "Child",
    "guest" to "Guest",
)

class ProfileViewModel(private val session: SessionManager) : ViewModel() {
    data class UiState(
        val profile: ProfileOverview? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val savingProfile: Boolean = false,
        val savingAbout: Boolean = false,
        /** Everyone's about-me (null = not loaded, or an older server). */
        val members: List<Member>? = null,
        val changingPassword: Boolean = false,
        /** Bumped after a successful password change so the form clears. */
        val passwordFormKey: Int = 0,
        /** The app build the server offers (null = none, or couldn't ask). */
        val release: AppRelease? = null,
        /** 0..1 while an update downloads, else null. */
        val downloadProgress: Float? = null,
        /** An adult shares this member's app usage, so Phone and sharing says so while closed. */
        val usageShared: Boolean = false,
        /** A live check is running on this member: Phone and sharing opens to show it (its notification opens Profile). */
        val checkOnMe: Boolean = false,
    )

    private val api = session.api
    val state = MutableStateFlow(UiState())
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load({ api.profile() }, { s, v -> s.copy(profile = v, loading = false, error = null) }, { s, e -> s.copy(loading = false, error = e) })
        // Separate, so an older server (no /api/app/info) still shows the profile.
        val release = runCatching { api.appRelease() }.getOrNull()
        // An older server lists id + name only (no role): no directory then.
        val members = runCatching { api.householdMembers() }.getOrNull()?.takeIf { list -> list.any { it.role != null } }
        val usageShared = runCatching { api.myUsageSharing().shared }.getOrNull()
        // Refused with Location off for the household: then there's no check.
        val checkOnMe = runCatching { api.liveChecks().onMe != null }.getOrDefault(false)
        state.update {
            it.copy(
                release = release,
                members = members ?: it.members,
                usageShared = usageShared ?: it.usageShared,
                checkOnMe = checkOnMe,
            )
        }
    }

    /** Downloads the offered update with this session, checks it, then opens Android's installer. */
    fun downloadAndInstall(context: Context) = viewModelScope.launch {
        val release = state.value.release ?: return@launch
        if (state.value.downloadProgress != null) return@launch
        state.update { it.copy(downloadProgress = 0f) }
        try {
            val apk = AppUpdate.download(context, api, release) { p -> state.update { it.copy(downloadProgress = p) } }
            AppUpdate.install(context, apk)
        } catch (e: Exception) {
            _messages.send(e.message ?: "Couldn't download the update")
        } finally {
            state.update { it.copy(downloadProgress = null) }
        }
    }

    // Each card's last failure: the fields the server named are marked with why
    // (the reason also goes to the snackbar, which these inline cards don't cover).
    val profileForm = FormErrors()
    val aboutForm = FormErrors()
    val passwordForm = FormErrors()

    fun saveProfile(name: String?, username: String?, email: String?, currentPassword: String?) = viewModelScope.launch {
        state.update { it.copy(savingProfile = true) }
        profileForm.clear()
        try {
            val updated = api.updateProfile(name, username, email, currentPassword)
            state.update { it.copy(profile = updated) }
            session.applyProfile(updated)
            _messages.send("Profile updated")
        } catch (e: Exception) {
            profileForm.fail(e, "Couldn't update your profile")
            _messages.send(e.message ?: "Couldn't update your profile")
        } finally {
            state.update { it.copy(savingProfile = false) }
        }
    }

    fun saveAboutMe(about: PublicProfile) = viewModelScope.launch {
        state.update { it.copy(savingAbout = true) }
        aboutForm.clear()
        try {
            val updated = api.updateAboutMe(about)
            state.update { s ->
                s.copy(
                    profile = updated,
                    // Show the change in the household list without a reload.
                    members = s.members?.map { m ->
                        if (m.id != updated.id) m else m.copy(
                            bio = about.bio, avatarEmoji = about.avatarEmoji,
                            profileColor = about.profileColor, birthday = about.birthday,
                        )
                    },
                )
            }
            _messages.send("About me saved")
        } catch (e: Exception) {
            aboutForm.fail(e, "Couldn't save your about me")
            _messages.send(e.message ?: "Couldn't save your about me")
        } finally {
            state.update { it.copy(savingAbout = false) }
        }
    }

    fun changePassword(current: String, new: String, signOutOthers: Boolean) = viewModelScope.launch {
        state.update { it.copy(changingPassword = true) }
        passwordForm.clear()
        try {
            api.changePassword(current, new, signOutOthers)
            // Lift the temporary-password flag if it was set; not fatal if it fails.
            runCatching { api.clearPasswordFlag() }
            session.clearMustChangePassword()
            state.update { it.copy(passwordFormKey = it.passwordFormKey + 1) }
            _messages.send(if (signOutOthers) "Password changed - other devices were signed out" else "Password changed")
        } catch (e: Exception) {
            passwordForm.fail(e, "Couldn't change your password")
            _messages.send(e.message ?: "Couldn't change your password")
        } finally {
            state.update { it.copy(changingPassword = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    session: SessionManager,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The Location feature is on for the household. */
    showLocation: Boolean = false,
) {
    val vm = viewModel { ProfileViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect(showMessage) }

    PullToRefreshBox(
        isRefreshing = state.loading && state.profile != null,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        val profile = state.profile
        if (profile == null) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadState(state.loading, state.error, true, "", vm::refresh)
                if (!state.loading) OfflineUnlockSection(showMessage)
            }
            return@PullToRefreshBox
        }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountCard(profile)
            // Upright: one list in this order. Sideways: short cards in pairs, then
            // Phone and sharing across the whole width with its sections side by side.
            CardRows(PROFILE_ROWS) {
                profile.profile?.let { about ->
                    CardSlot("about") {
                        AboutMeCard(profile.name, about, saving = state.savingAbout, errors = vm.aboutForm, onSave = vm::saveAboutMe)
                    }
                }
                state.members?.takeIf { it.size > 1 }?.let { CardSlot("household") { HouseholdCard(it, profile.id) } }
                CardSlot("phone") {
                    // The long phone blocks, closed so a newly set up phone isn't a wall of
                    // switches (each section shows only when it applies to this member).
                    CollapsibleCard(
                        "Phone and sharing",
                        phoneSubtitle(showLocation, state.usageShared),
                        group = true,
                        openWhen = showLocation && state.checkOnMe,
                    ) {
                        // Location and app usage beside Instant alerts when there's room.
                        TwoStacks(
                            first = {
                                if (showLocation) LocationCard(session, showMessage)
                                AppUsageCard(session)
                            },
                            second = { InstantAlertsCard(session, showMessage) },
                        )
                    }
                }
                state.release?.let { release ->
                    CardSlot("app") {
                        val context = LocalContext.current
                        AppUpdateCard(release, state.downloadProgress) { vm.downloadAndInstall(context.applicationContext) }
                    }
                }
                CardSlot("edit") {
                    EditProfileCard(profile, saving = state.savingProfile, errors = vm.profileForm, onSave = vm::saveProfile)
                }
                CardSlot("password") {
                    ChangePasswordCard(state.passwordFormKey, changing = state.changingPassword, errors = vm.passwordForm, onChange = vm::changePassword)
                }
            }
        }
    }
}

/** Profile's cards when sideways (owner's layout, 2026-10-22); see [CardRows]. */
private val PROFILE_ROWS = listOf(
    listOf("about", "edit"),
    listOf("household", "password"),
    listOf("app", "none"),
    listOf("phone"),
)

/**
 * What Phone and sharing holds, so a closed block still says what's inside,
 * app usage included when an adult shares it.
 */
internal fun phoneSubtitle(showLocation: Boolean, usageShared: Boolean): String {
    val parts = listOfNotNull(
        "location".takeIf { showLocation },
        "app usage".takeIf { usageShared },
        "alerts",
    )
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + " and keeping Our Home running."
}

@Composable
private fun AccountCard(p: ProfileOverview) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileAvatar(p.name, p.profile?.avatarEmoji, p.profile?.profileColor, size = 56.dp)
                Column {
                    Text(p.name, style = MaterialTheme.typography.headlineSmall)
                    p.username?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(
                listOfNotNull(ROLE_LABELS[p.role] ?: p.role, p.householdName).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Member since ${formatDate(p.memberSince)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Tasks done", p.stats.tasksCompleted)
                Stat("Open tasks", p.stats.openAssignedTasks)
                Stat("Purchases", p.stats.purchasesLogged)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** True inside a [CollapsibleCard] group: a [SectionCard] then sits under a divider instead of in a card of its own. */
private val LocalGroupedSection = staticCompositionLocalOf { false }

/**
 * Inside a [CollapsibleCard] group, [foldable] makes the title row fold the body
 * away, as [CollapsibleCard] does: closed to start, stays as left across a
 * rotation, opens by itself whenever [openWhen] turns true. [badge] sits beside
 * the title and shows while folded (an On / Off pill).
 */
@Composable
internal fun SectionCard(
    title: String,
    subtitle: String,
    badge: (@Composable () -> Unit)? = null,
    foldable: Boolean = false,
    openWhen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalGroupedSection.current && foldable) {
        var open by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(openWhen) { if (openWhen) open = true }
        Column {
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (open) "Close" else "Open") { open = !open }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        badge?.invoke()
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
        }
        return
    }
    if (LocalGroupedSection.current) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider()
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/**
 * A card whose body folds away under its title: tap the title row to open or
 * close it. For long blocks; short ones stay a plain [SectionCard]. Starts
 * closed unless [startOpen]; stays as left across a rotation. It opens by
 * itself whenever [openWhen] turns true (something inside needs seeing).
 * With [group], the [SectionCard]s inside sit under dividers in this one card.
 */
@Composable
internal fun CollapsibleCard(
    title: String,
    subtitle: String,
    startOpen: Boolean = false,
    group: Boolean = false,
    openWhen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    LaunchedEffect(openWhen) { if (openWhen) open = true }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Close" else "Open") { open = !open }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (group) 16.dp else 12.dp),
            ) {
                CompositionLocalProvider(LocalGroupedSection provides group) { content() }
            }
        }
    }
}

/**
 * Two stacks of cards, side by side on a phone or tablet turned sideways (and on
 * any screen 840dp wide or more), so short cards don't each take a full row; as
 * the web's Profile and Settings. Upright, [first] sits above [second]: the same
 * order as one list. One layout for both, so a turn only re-measures: the cards
 * keep their place in the composition, and with it whether they're open.
 */
@Composable
internal fun TwoStacks(
    spacing: Dp = 16.dp,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    val sideBySide = cardsSideBySide()
    Layout(
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing), content = first)
            Column(verticalArrangement = Arrangement.spacedBy(spacing), content = second)
        },
    ) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        if (sideBySide) {
            val half = (width - gap) / 2
            val each = constraints.copy(minWidth = half, maxWidth = half, minHeight = 0)
            val a = measurables[0].measure(each)
            // Nothing in the first stack: the second takes the whole width.
            val bWidth = if (a.height == 0) width else half
            val b = measurables[1].measure(constraints.copy(minWidth = bWidth, maxWidth = bWidth, minHeight = 0))
            layout(width, maxOf(a.height, b.height)) {
                a.place(0, 0)
                b.place(if (a.height == 0) 0 else half + gap, 0)
            }
        } else {
            val each = constraints.copy(minWidth = width, minHeight = 0)
            val (a, b) = measurables.map { it.measure(each) }
            // No gap under an empty stack.
            val between = if (a.height > 0 && b.height > 0) gap else 0
            layout(width, a.height + between + b.height) {
                a.place(0, 0)
                b.place(0, a.height + between)
            }
        }
    }
}

/** Room for cards side by side: turned sideways (600dp or more), or any window 840dp wide. */
@Composable
internal fun cardsSideBySide(): Boolean {
    val window = LocalWindowInfo.current.containerSize
    val widthDp = with(LocalDensity.current) { window.width.toDp() }
    // Sideways: the window is wider than it is tall.
    return widthDp >= 840.dp || (window.width > window.height && widthDp >= 600.dp)
}

/** Marks a card for [CardRows] by [id]. */
@Composable
internal fun CardSlot(id: String, content: @Composable () -> Unit) {
    Box(Modifier.layoutId(id)) { content() }
}

/**
 * Cards ([CardSlot]s) laid out in [rows] when there's room ([cardsSideBySide]):
 * a row of two ids puts them side by side, a row of one gives it the whole
 * width; ids not shown are skipped. Upright, the cards are one list in the
 * order they're written. One layout for both, so turning the phone only moves
 * them: each keeps its place in the composition, and with it whether it's open.
 */
@Composable
internal fun CardRows(rows: List<List<String>>, spacing: Dp = 16.dp, content: @Composable () -> Unit) {
    val sideBySide = cardsSideBySide()
    Layout(content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val full = constraints.copy(minWidth = width, maxWidth = width, minHeight = 0)
        val placed = mutableListOf<Triple<Placeable, Int, Int>>()
        var y = 0
        fun row(items: List<Pair<Measurable, Int>>, each: Constraints) {
            val ps = items.map { (m, x) -> m.measure(each) to x }
            val h = ps.maxOfOrNull { it.first.height } ?: 0
            if (h == 0) return
            ps.forEach { (p, x) -> placed += Triple(p, x, y) }
            y += h + gap
        }
        if (!sideBySide) {
            measurables.forEach { row(listOf(it to 0), full) }
        } else {
            val half = (width - gap) / 2
            val byId = measurables.associateBy { it.layoutId }
            val used = mutableSetOf<Any?>()
            rows.forEach { ids ->
                val present = ids.mapIndexedNotNull { col, id -> byId[id]?.let { it to col * (half + gap) } }
                used.addAll(ids)
                if (ids.size == 1) row(present, full)
                else row(present, constraints.copy(minWidth = half, maxWidth = half, minHeight = 0))
            }
            // Anything not named in rows: full width after them.
            measurables.filter { it.layoutId !in used }.forEach { row(listOf(it to 0), full) }
        }
        layout(width, (y - gap).coerceAtLeast(0)) {
            placed.forEach { (p, x, py) -> p.place(x, py) }
        }
    }
}

/**
 * The server's own build of this app (from the web deploy): up to date, or an
 * update to download and install. Android asks once to allow installs from Our
 * Home, then confirms each install; an update keeps the user signed in.
 */
@Composable
private fun AppUpdateCard(release: AppRelease, progress: Float?, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val sameApp = release.appId == context.packageName
    val update = remember(release) { AppUpdate.isUpdate(context, release) }
    // Re-checked on return from Settings, where installs are allowed.
    var canInstall by remember { mutableStateOf(AppUpdate.canInstall(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = AppUpdate.canInstall(context) }
    val size = "%.1f MB".format(release.sizeBytes / 1_048_576.0)

    SectionCard("Android app", "Installed: version ${BuildConfig.VERSION_NAME}") {
        when {
            !sameApp -> Text(
                "This server offers its app as ${release.appId}, a separate install from this one " +
                    "(${context.packageName}). Get it from Profile on the site.",
                style = MaterialTheme.typography.bodyMedium,
            )
            !update -> Text("You have the latest version.", style = MaterialTheme.typography.bodyMedium)
            progress != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            !canInstall -> {
                Text(
                    "Version ${release.versionName} is ready ($size). First allow Our Home to install it " +
                        "(once), then come back here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { AppUpdate.openInstallPermission(context) }) { Text("Allow installs from Our Home") }
            }
            else -> {
                Text(
                    "Version ${release.versionName} is ready ($size). It installs over this one, so you stay signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onUpdate) { Text("Download and install") }
            }
        }
    }
}

/** The user's own about-me (web: AboutMeCard); everyone in the household sees it. */
@Composable
private fun AboutMeCard(
    name: String,
    p: PublicProfile,
    saving: Boolean,
    /** The last save failure: the fields the server named are marked with why. */
    errors: FormErrors,
    onSave: (PublicProfile) -> Unit,
) {
    CollapsibleCard("About me", "Make your profile yours. Everyone in the household can see this.") {
        // Keyed on the saved values so the form resets to them after a save
        // (inside the card, so a save doesn't close it).
        key(p) { AboutMeForm(name, p, saving, errors, onSave) }
    }
}

@Composable
private fun AboutMeForm(
    name: String,
    p: PublicProfile,
    saving: Boolean,
    errors: FormErrors,
    onSave: (PublicProfile) -> Unit,
) {
    var emoji by remember { mutableStateOf(p.avatarEmoji.orEmpty()) }
    var color by remember { mutableStateOf(p.profileColor) }
    var bio by remember { mutableStateOf(p.bio.orEmpty()) }
    var month by remember { mutableStateOf(p.birthday?.substring(0, 2)?.toIntOrNull()) }
    var day by remember { mutableStateOf(p.birthday?.substring(3)?.toIntOrNull()?.toString().orEmpty()) }

    val birthday = ProfileStyle.birthday(month, day.toIntOrNull())
    // Both or neither; the server also checks the emoji is a single one.
    val birthdayValid = (month == null && day.isBlank()) || birthday != null
    val next = PublicProfile(
        bio = bio.trim().ifEmpty { null },
        avatarEmoji = emoji.trim().ifEmpty { null },
        profileColor = color,
        birthday = birthday,
    )
    val dirty = next != p

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileAvatar(name, next.avatarEmoji, color, size = 56.dp)
            Text(name, style = MaterialTheme.typography.titleMedium)
        }
        FieldLabel("Avatar emoji")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileStyle.EMOJI_PICKS.forEach { e ->
                FilterChip(selected = emoji == e, onClick = { emoji = e; errors.clear("avatarEmoji") }, label = { Text(e) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = emoji,
                onValueChange = { if (it.length <= 16) emoji = it; errors.clear("avatarEmoji") },
                label = { Text("Or type one") },
                singleLine = true,
                isError = errors["avatarEmoji"] != null,
                supportingText = errors["avatarEmoji"]?.let { { Text(it) } },
                modifier = Modifier.weight(1f),
            )
            if (emoji.isNotEmpty()) TextButton(onClick = { emoji = ""; errors.clear("avatarEmoji") }) { Text("Use initials") }
        }
        FieldLabel("Colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileStyle.COLORS.forEach { (key, argb) ->
                val selected = color == key
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(3.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .clickable { color = if (selected) null else key; errors.clear("profileColor") },
                )
            }
        }
        FieldError(errors["profileColor"])
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The phone's language as Compose sees it, so month names follow a change.
                val locale: Locale = LocalConfiguration.current.locales[0]
                DropdownField(
                    "Birthday month",
                    listOf("" to "-") + Month.entries.map {
                        it.value.toString() to it.getDisplayName(TextStyle.FULL, locale)
                    },
                    month?.toString().orEmpty(),
                    onSelect = { month = it.toIntOrNull(); errors.clear("birthday") },
                )
            }
            OutlinedTextField(
                value = day,
                onValueChange = { v -> day = v.filter(Char::isDigit).take(2); errors.clear("birthday") },
                label = { Text("Day") },
                singleLine = true,
                isError = !birthdayValid || errors["birthday"] != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.5f),
            )
        }
        val birthdayError = errors["birthday"] ?: if (!birthdayValid) "Pick a month and a real day (or neither)." else null
        Text(
            birthdayError ?: "Month and day only, no year.",
            style = MaterialTheme.typography.bodySmall,
            color = if (birthdayError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        OutlinedTextField(
            value = bio,
            onValueChange = { if (it.length <= ProfileStyle.BIO_MAX) bio = it; errors.clear("bio") },
            label = { Text("About me") },
            placeholder = { Text("Favourite foods, hobbies, what you're up to…") },
            isError = errors["bio"] != null,
            supportingText = { Text(errors["bio"] ?: "${bio.length}/${ProfileStyle.BIO_MAX}") },
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(next) }, enabled = dirty && birthdayValid && !saving) {
            Text(if (saving) "Saving…" else "Save")
        }
    }
}

/** Everyone's about-me, read-only (web: HouseholdCard). */
@Composable
private fun HouseholdCard(members: List<Member>, userId: String) {
    CollapsibleCard("Household", "What everyone has shared about themselves.") {
        members.forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileAvatar(m.name, m.avatarEmoji, m.profileColor)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (m.id == userId) "${m.name} (you)" else m.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        m.role?.let { ROLE_LABELS[it] ?: it }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    m.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    ProfileStyle.formatBirthday(m.birthday)?.let {
                        Text(
                            "🎂 $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditProfileCard(
    p: ProfileOverview,
    saving: Boolean,
    /** The last save failure: the fields the server named are marked with why. */
    errors: FormErrors,
    onSave: (name: String?, username: String?, email: String?, currentPassword: String?) -> Unit,
) {
    CollapsibleCard("Edit profile", "Your display name, sign-in username, and recovery email.") {
        // Keyed on the saved values so the form resets to them after a save.
        key(p.name, p.username, p.email) { EditProfileForm(p, saving, errors, onSave) }
    }
}

@Composable
private fun EditProfileForm(
    p: ProfileOverview,
    saving: Boolean,
    errors: FormErrors,
    onSave: (name: String?, username: String?, email: String?, currentPassword: String?) -> Unit,
) {
    var name by remember { mutableStateOf(p.name) }
    var username by remember { mutableStateOf(p.username.orEmpty()) }
    var email by remember { mutableStateOf(p.email) }
    // Changing the email (where password resets go) needs the password.
    var emailPassword by remember { mutableStateOf("") }

    val nameDirty = name.trim() != p.name
    val usernameDirty = username.trim() != p.username.orEmpty()
    val emailDirty = email.trim() != p.email
    // Why each is red: the server's word first, then the same rules checked here.
    val nameError = errors["name"] ?: when {
        name.isBlank() -> "Enter a name."
        name.trim().length > 80 -> "At most 80 characters."
        else -> null
    }
    val usernameError = errors["username"] ?: usernameProblem(username) ?: if (username.isBlank()) "Enter a username." else null
    val emailError = errors["email"] ?: emailProblem(email) ?: if (email.isBlank()) "Enter an email address." else null
    val passwordError = errors["currentPassword"]
    val canSave = (nameDirty || usernameDirty || emailDirty) && nameError == null && usernameError == null &&
        emailError == null && (!emailDirty || emailPassword.isNotEmpty()) && !saving

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; errors.clear("name") },
            label = { Text("Display name") },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; errors.clear("username") },
            label = { Text("Username") },
            supportingText = { Text(usernameError ?: "You sign in with this.") },
            singleLine = true,
            isError = usernameError != null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; errors.clear("email") },
            label = { Text("Email") },
            supportingText = { Text(emailError ?: "Used for password recovery.") },
            singleLine = true,
            isError = emailError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        if (emailDirty) {
            PasswordField(
                "Current password",
                emailPassword,
                supporting = passwordError ?: "Needed to change your email, since password resets go there.",
                isError = passwordError != null,
            ) { emailPassword = it; errors.clear("currentPassword") }
        }
        Button(
            onClick = {
                onSave(
                    name.trim().takeIf { nameDirty },
                    username.trim().takeIf { usernameDirty },
                    email.trim().takeIf { emailDirty },
                    emailPassword.takeIf { emailDirty },
                )
            },
            enabled = canSave,
        ) { Text(if (saving) "Saving…" else "Save changes") }
    }
}

@Composable
private fun ChangePasswordCard(
    /** Bumped after a successful change, so the fields clear. */
    formKey: Int,
    changing: Boolean,
    /** The last failure: a wrong current password or a refused new one is marked on its field. */
    errors: FormErrors,
    onChange: (current: String, new: String, signOutOthers: Boolean) -> Unit,
) {
    CollapsibleCard("Change password", "Enter your current password, then choose a new one.") {
        key(formKey) { ChangePasswordForm(changing, errors, onChange) }
    }
}

@Composable
private fun ChangePasswordForm(
    changing: Boolean,
    errors: FormErrors,
    onChange: (current: String, new: String, signOutOthers: Boolean) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var signOutOthers by remember { mutableStateOf(true) }

    val mismatch = confirm.isNotEmpty() && confirm != new
    val canChange = current.isNotEmpty() && new.length >= MIN_PASSWORD && confirm == new && !changing
    val currentError = errors["currentPassword"]
    val newError = errors["newPassword"] ?: passwordProblem(new)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PasswordField(
            "Current password",
            current,
            supporting = currentError,
            isError = currentError != null,
        ) { current = it; errors.clear("currentPassword") }
        PasswordField(
            "New password",
            new,
            supporting = newError ?: "At least $MIN_PASSWORD characters.",
            isError = newError != null,
        ) { new = it; errors.clear("newPassword") }
        PasswordField(
            "Confirm new password",
            confirm,
            supporting = if (mismatch) "Passwords do not match." else null,
            isError = mismatch,
        ) { confirm = it }
        Row(
            Modifier.fillMaxWidth().clickable { signOutOthers = !signOutOthers },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = signOutOthers, onCheckedChange = { signOutOthers = it })
            Text("Sign out of other devices", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { onChange(current, new, signOutOthers) }, enabled = canChange) {
            Text(if (changing) "Updating…" else "Update password")
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    supporting: String? = null,
    isError: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}
