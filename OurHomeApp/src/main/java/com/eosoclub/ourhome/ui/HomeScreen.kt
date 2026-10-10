package com.eosoclub.ourhome.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.AppSettings
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.SessionUser
import com.eosoclub.ourhome.data.ThemeMode
import com.eosoclub.ourhome.data.DeadlineState
import com.eosoclub.ourhome.data.AccessMatrix
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.defaultAccess
import com.eosoclub.ourhome.data.Features
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.eosoclub.ourhome.nfc.NfcScans
import com.eosoclub.ourhome.data.awaitingAcceptanceBy
import com.eosoclub.ourhome.data.canApproveMedia
import com.eosoclub.ourhome.data.deadlineAlerts
import com.eosoclub.ourhome.data.isOpen
import com.eosoclub.ourhome.notifications.AppUpdateAlerts
import com.eosoclub.ourhome.notifications.HouseholdReminderAlerts
import com.eosoclub.ourhome.notifications.RequestReminders
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [label] titles the top bar; [short] fits six items in the bottom bar (matches the web's mobile nav). */
internal enum class Tab(val label: String, val short: String) {
    Home("Home", "Home"),
    Tasks("Tasks", "Tasks"),
    Shopping("Shopping", "Shop"),
    Inventory("Inventory", "Stock"),
    Bills("Bills", "Bills"),
    Requests("Requests", "Requests"),
}

@Composable
private fun Tab.icon(): Painter = when (this) {
    Tab.Home -> rememberVectorPainter(Icons.Filled.Home)
    Tab.Tasks -> rememberVectorPainter(Icons.Filled.CheckCircle)
    Tab.Shopping -> rememberVectorPainter(Icons.Filled.ShoppingCart)
    Tab.Inventory -> painterResource(R.drawable.ic_inventory)
    Tab.Bills -> painterResource(R.drawable.ic_receipt)
    Tab.Requests -> painterResource(R.drawable.ic_request)
}

private fun Tab.isOn(features: Features): Boolean = when (this) {
    Tab.Home -> true
    Tab.Tasks -> features.tasks
    Tab.Shopping -> features.shopping
    Tab.Inventory -> features.inventory
    Tab.Bills -> features.bills
    Tab.Requests -> features.requests
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: SessionManager,
    settings: AppSettings,
    user: SessionUser,
    openTab: StateFlow<String?>,
    onTabOpened: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    // A full-screen page over the tabs, opened from the top bar.
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var reportingBug by remember { mutableStateOf(false) }
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    // A message with one action (e.g. Undo after completing a task).
    val showAction: (String, String, () -> Unit) -> Unit = { msg, label, action ->
        scope.launch {
            val result = snackbar.showSnackbar(msg, actionLabel = label, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) action()
        }
    }

    val notifications = viewModel { NotificationsViewModel(session.api) }
    val notifState by notifications.state.collectAsStateWithLifecycle()
    // Shared with RequestsScreen (same ViewModelStore), so the badge and the
    // tab's list come from one fetch.
    val requests = viewModel { RequestsViewModel(session.api) }
    val requestsState by requests.state.collectAsStateWithLifecycle()
    // What this user may add/edit/delete per page (set by the head on the website),
    // and which features the household has (set by the server admin).
    val appContext = LocalContext.current.applicationContext
    val accessVm = viewModel {
        AccessViewModel(
            session.api,
            user.role,
            appContext.getSharedPreferences(AccessViewModel.PREFS, Context.MODE_PRIVATE),
            user.id,
        )
    }
    val access by accessVm.access.collectAsStateWithLifecycle()
    val features by accessVm.features.collectAsStateWithLifecycle()
    val tabs = Tab.entries.filter { it.isOn(features) }
    // A tab (or Points) the server admin just turned off: back to Home.
    LaunchedEffect(features) {
        if (!tab.isOn(features)) tab = Tab.Home
        if (overlay == Overlay.Points && !features.points) overlay = null
    }
    // Keep both badges and the permissions current as the user moves around.
    LaunchedEffect(tab) {
        notifications.refresh()
        requests.refresh()
        accessVm.refresh()
    }

    // Waiting for my acceptance, plus my own maintenance due today or overdue —
    // the tab badge counts them and the dashboard's "Needs you" lists them.
    val waitingRequests = awaitingAcceptanceBy(requestsState.requests, user.id, canApproveMedia(access, user.role))
        .map { WaitingRequest(it, if (it.category == "media") "Mark added" else "Accept", urgent = false) } +
        deadlineAlerts(requestsState.requests, user.id)
            .filter { !it.forRequester && it.state != DeadlineState.DueTomorrow }
            .map { WaitingRequest(it.request, if (it.state == DeadlineState.Overdue) "Overdue" else "Due today", urgent = true) }
    val waitingOnMe = waitingRequests.size
    val anyOpen = requestsState.requests.any { it.isOpen }

    // A tapped notification asks for the Requests tab (reminders), Profile
    // (an app update to install), or a household reminder's tab / the bell.
    val pendingTab by openTab.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTab) {
        val target = when (pendingTab) {
            RequestReminders.TAB_REQUESTS -> Tab.Requests
            HouseholdReminderAlerts.TAB_TASKS -> Tab.Tasks
            HouseholdReminderAlerts.TAB_INVENTORY -> Tab.Inventory
            HouseholdReminderAlerts.TAB_BILLS -> Tab.Bills
            else -> null
        }
        when {
            // A tab the server admin turned off: the tap just opens the app.
            target != null && !target.isOn(features) -> onTabOpened()
            target != null -> {
                overlay = null
                tab = target
                onTabOpened()
            }
            pendingTab == HouseholdReminderAlerts.TAB_NOTIFICATIONS -> {
                overlay = Overlay.Notifications
                onTabOpened()
            }
            pendingTab == AppUpdateAlerts.TAB_PROFILE -> {
                overlay = Overlay.Profile
                onTabOpened()
            }
        }
    }

    // Android 13+ needs runtime consent for the request reminders.
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) showMessage("Notifications are off — you won't get request reminders.")
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !RequestReminders.canNotify(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A scanned NFC tag opens the scan sheet over whatever screen is showing.
    val scan = viewModel { ScanViewModel(session.api) }
    val inventory = viewModel { InventoryViewModel(session.api) }
    val scanned by NfcScans.latest.collectAsStateWithLifecycle()
    LaunchedEffect(scanned) {
        scanned?.let {
            // Tags drive Inventory: with it turned off a scan does nothing.
            if (features.inventory) scan.open(it.ref)
            NfcScans.consume()
        }
    }

    // Accounts given a temporary password land on Profile to choose their own.
    LaunchedEffect(Unit) {
        if (user.mustChangePassword == true) overlay = Overlay.Profile
    }

    BackHandler(enabled = overlay != null) { overlay = null }
    BackHandler(enabled = overlay == null && tab != Tab.Home) { tab = Tab.Home }

    Scaffold(
        topBar = {
            val current = overlay
            if (current != null) {
                TopAppBar(
                    title = { Text(current.label) },
                    navigationIcon = {
                        IconButton(onClick = { overlay = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (current == Overlay.Notifications && notifState.unreadCount > 0) {
                            TextButton(onClick = { notifications.markAllRead() }) { Text("Mark all read") }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(if (tab == Tab.Home) stringResource(R.string.app_name) else tab.label) },
                    actions = {
                        IconButton(onClick = { overlay = Overlay.Notifications }) {
                            BadgedBox(badge = {
                                if (notifState.unreadCount > 0) Badge { Text(notifState.unreadCount.coerceAtMost(99).toString()) }
                            }) {
                                Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Profile")
                                            Text(
                                                listOfNotNull(user.name, (user.displayUsername ?: user.username)?.let { "@$it" })
                                                    .joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                    onClick = { menuOpen = false; overlay = Overlay.Profile },
                                )
                                if (features.points) {
                                    DropdownMenuItem(
                                        text = { Text("Points") },
                                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                                        onClick = { menuOpen = false; overlay = Overlay.Points },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Activity") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                    onClick = { menuOpen = false; overlay = Overlay.Activity },
                                )
                                DropdownMenuItem(
                                    text = { Text("Report a bug") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_bug), contentDescription = null) },
                                    onClick = { menuOpen = false; reportingBug = true },
                                )
                                HorizontalDivider()
                                Text(
                                    "Theme",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                                ThemeMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        leadingIcon = { RadioButton(selected = themeMode == mode, onClick = null) },
                                        onClick = { settings.setThemeMode(mode); menuOpen = false },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                                    onClick = { menuOpen = false; confirmSignOut = true },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (overlay == null) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                if (t == Tab.Requests && (waitingOnMe > 0 || anyOpen)) {
                                    // A number when something waits on you; a dot when
                                    // requests are open but none need you.
                                    BadgedBox(badge = {
                                        if (waitingOnMe > 0) Badge { Text(waitingOnMe.coerceAtMost(99).toString()) } else Badge()
                                    }) { Icon(t.icon(), contentDescription = null) }
                                } else {
                                    Icon(t.icon(), contentDescription = null)
                                }
                            },
                            label = { Text(t.short, maxLines = 1, overflow = TextOverflow.Clip) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (overlay) {
            Overlay.Notifications -> NotificationsScreen(
                notifications,
                showMessage,
                onOpen = { n ->
                    // Go to what it's about; anything else just clears.
                    val target = when (n.subjectType) {
                        "task" -> Tab.Tasks
                        "bill" -> Tab.Bills
                        "inventory_item" -> Tab.Inventory
                        "request" -> Tab.Requests
                        else -> null
                    }
                    when {
                        target != null && target.isOn(features) -> { overlay = null; tab = target }
                        n.subjectType == "app_release" -> overlay = Overlay.Profile
                    }
                },
                modifier,
            )
            Overlay.Profile -> ProfileScreen(session, user.mustChangePassword == true, showMessage, modifier)
            Overlay.ScanHistory -> ScanHistoryScreen(session.api, modifier)
            Overlay.Points -> PointsScreen(session.api, user.id, isHead = user.role == "head", showMessage, modifier)
            Overlay.Activity -> ActivityScreen(session.api, user.id, modifier)
            null -> TabContent(
                tab,
                session,
                user,
                access,
                features,
                waitingRequests,
                showMessage,
                showAction,
                onOpenPoints = { overlay = Overlay.Points },
                onOpenTab = { tab = it },
                onOpenScanHistory = { overlay = Overlay.ScanHistory },
                modifier = modifier,
            )
        }
    }

    // Refresh stock afterwards so the Inventory tab shows the new numbers.
    // ± stock and binding a tag need any Inventory access (as on the server).
    ScanSheet(scan, canWrite = access.inventory.any, onFinished = { inventory.refresh() })

    if (reportingBug) {
        BugReportDialog(
            api = session.api,
            where = overlay?.label ?: tab.label,
            onDismiss = { reportingBug = false },
            showMessage = showMessage,
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Signed in as ${user.name ?: user.username}.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { session.signOut() } }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

private enum class Overlay(val label: String) {
    Notifications("Notifications"),
    Profile("Profile"),
    ScanHistory("Recent scans"),
    Points("Points"),
    Activity("Activity"),
}

@Composable
private fun TabContent(
    tab: Tab,
    session: SessionManager,
    user: SessionUser,
    access: AccessMatrix,
    features: Features,
    waitingRequests: List<WaitingRequest>,
    showMessage: (String) -> Unit,
    showAction: (String, String, () -> Unit) -> Unit,
    onOpenPoints: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    onOpenScanHistory: () -> Unit,
    modifier: Modifier,
) {
    // Points links go nowhere when the server admin turned Points off.
    val openPoints = onOpenPoints.takeIf { features.points }
    when (tab) {
        Tab.Home -> DashboardScreen(
            session.api,
            user.name ?: user.username,
            access,
            features,
            if (features.requests) waitingRequests else emptyList(),
            onOpenTab = onOpenTab,
            onOpenPoints = openPoints,
            modifier = modifier,
        )
        Tab.Tasks -> TasksScreen(session.api, access.tasks, user.id, showMessage, showAction, openPoints, modifier)
        Tab.Shopping -> ShoppingScreen(session.api, access.shopping, access.lists, user.id, showMessage, modifier)
        Tab.Inventory -> InventoryScreen(session.api, access.inventory, user.id, showMessage, onOpenScanHistory, modifier)
        Tab.Bills -> BillsScreen(session.api, access.bills, user.id, showMessage, modifier)
        Tab.Requests -> RequestsScreen(
            session.api,
            user,
            canSubmit = access.requests.create,
            canApproveMedia = canApproveMedia(access, user.role),
            showMessage,
            modifier,
        )
    }
}

/**
 * The signed-in user's page access from `GET /api/permissions/me`. Starts from
 * the role's built-in defaults so screens render immediately; keeps the last
 * good grid when a refresh fails (offline, or a server without the endpoint).
 */
internal class AccessViewModel(
    private val api: ApiClient,
    role: String?,
    private val prefs: SharedPreferences,
    userId: String,
) : ViewModel() {
    private val _access = MutableStateFlow(defaultAccess(role))
    val access: StateFlow<AccessMatrix> = _access.asStateFlow()

    // The household's features (server admin). Starts from the last known set,
    // so a household without e.g. Shopping doesn't see its tab flash on launch.
    private val featuresKey = "features_$userId"
    private val _features = MutableStateFlow(
        prefs.getStringSet(featuresKey, null)?.let { Features.from(it) } ?: Features.ALL,
    )
    val features: StateFlow<Features> = _features.asStateFlow()

    fun refresh() = viewModelScope.launch {
        try {
            val me = api.myAccess()
            _access.value = me.access
            val features = Features.from(me.features)
            if (features != _features.value) {
                _features.value = features
                prefs.edit { putStringSet(featuresKey, features.names()) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    companion object {
        const val PREFS = "household_features"
    }
}
