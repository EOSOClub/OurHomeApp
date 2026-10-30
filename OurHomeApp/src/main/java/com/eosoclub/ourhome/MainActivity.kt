package com.eosoclub.ourhome

import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.eosoclub.ourhome.data.AppSettings
import com.eosoclub.ourhome.data.AuthState
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.SetupPrefill
import com.eosoclub.ourhome.data.ThemeMode
import com.eosoclub.ourhome.data.launchTab
import com.eosoclub.ourhome.data.setupPrefill
import com.eosoclub.ourhome.nfc.NfcScans
import com.eosoclub.ourhome.nfc.ScanHandoff
import com.eosoclub.ourhome.nfc.TagFormat
import com.eosoclub.ourhome.nfc.TagRef
import com.eosoclub.ourhome.location.Geofences
import com.eosoclub.ourhome.notifications.Push
import com.eosoclub.ourhome.notifications.RequestReminders
import com.eosoclub.ourhome.notifications.StayConnected
import com.eosoclub.ourhome.ui.ChoosePasswordScreen
import com.eosoclub.ourhome.ui.ClearUserScope
import com.eosoclub.ourhome.ui.HomeScreen
import com.eosoclub.ourhome.ui.UserScope
import com.eosoclub.ourhome.ui.LoginScreen
import com.eosoclub.ourhome.ui.theme.OurHomeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Saved-state key: the tap id of the launch intent's tab, already handled (see launchTab). */
private const val KEY_HANDLED_TAP_ID = "handled_tap_id"

class MainActivity : ComponentActivity() {
    // A tab a notification asked to open ("requests"); consumed by HomeScreen.
    private val openTab = MutableStateFlow<String?>(null)

    // What a setup link's Open Our Home button filled in; consumed by LoginScreen.
    private val prefill = MutableStateFlow<SetupPrefill?>(null)

    // The tap id of the launch intent's tab once handled (or skipped): kept in
    // the saved state, so a restore or Recents never replays that tap.
    private var handledTapId: Long? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        handledTapId?.let { outState.putLong(KEY_HANDLED_TAP_ID, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OurHomeApp
        // The screen a tapped notification asks for. Read even when the activity
        // comes back from saved state, so a saved screen (say Profile) never hides
        // a new tap that relaunched the app; never for Recents or a replay of a
        // tap already handled (its tap id is in the saved state). launchTab decides.
        val decision = launchTab(
            tab = intent.getStringExtra(RequestReminders.EXTRA_OPEN_TAB),
            tapId = if (intent.hasExtra(RequestReminders.EXTRA_TAP_ID)) intent.getLongExtra(RequestReminders.EXTRA_TAP_ID, 0) else null,
            launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0,
            lastHandledTapId = savedInstanceState?.takeIf { it.containsKey(KEY_HANDLED_TAP_ID) }?.getLong(KEY_HANDLED_TAP_ID),
        )
        decision.open?.let { openTab.value = it }
        handledTapId = decision.handledTapId
        if (savedInstanceState == null) {
            emitScanFrom(intent)
            prefillFrom(intent, app.session)
            markReadFrom(intent, app.session)
        }
        setContent {
            val mode by app.settings.themeMode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> isSystemInDarkTheme()
            }
            // Status/nav bar icon colors follow the app theme, not the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            OurHomeTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Root(
                        app.session,
                        app.settings,
                        openTab,
                        onTabOpened = { openTab.value = null },
                        prefill = prefill,
                        onPrefillUsed = { prefill.value = null },
                    )
                }
            }
        }
    }

    // singleTop: tapping a notification (or a routed NFC tag) while the app is
    // open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(RequestReminders.EXTRA_OPEN_TAB)?.let { openTab.value = it }
        emitScanFrom(intent)
        prefillFrom(intent, (application as OurHomeApp).session)
        markReadFrom(intent, (application as OurHomeApp).session)
    }

    /**
     * A tapped reminder or bug report notification: what it listed is read
     * now, on the website and the member's other phones too. Waits out the
     * saved sign-in check on a cold start; signed out, nothing is sent.
     */
    private fun markReadFrom(intent: Intent, session: SessionManager) {
        val ids = intent.getStringArrayExtra(RequestReminders.EXTRA_MARK_READ)?.toList().orEmpty()
        // Only from our own notifications (see ScanHandoff); other apps can start us too.
        if (ids.isEmpty() || !ScanHandoff.isOurs(this, intent)) return
        lifecycleScope.launch {
            if (session.state.first { it != AuthState.Checking } !is AuthState.SignedIn) return@launch
            runCatching { session.api.markNotificationsRead(ids) }
                .onFailure { Log.w("MainActivity", "couldn't mark ${ids.size} notification(s) read", it) }
        }
    }

    /**
     * A setup link's ourhome://setup?username=&server=: fills in the sign-in
     * screen. Only while signed out; any web page can open this link, so it
     * never signs in or changes a server already set (setupPrefill).
     */
    private fun prefillFrom(intent: Intent, session: SessionManager) {
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "ourhome" || uri.host != "setup") return
        if (session.state.value is AuthState.SignedIn) return
        prefill.value = setupPrefill(uri.getQueryParameter("username"), uri.getQueryParameter("server"), session.baseUrl)
    }

    /** A tag handed over by QuickScanActivity or a quick-scan notification tap. */
    private fun emitScanFrom(intent: Intent) {
        val tagId = intent.getStringExtra(EXTRA_SCANNED_TAG) ?: return
        // Only from this app (see ScanHandoff); other apps can start us too.
        if (!ScanHandoff.isOurs(this, intent)) return
        val format = intent.getStringExtra(EXTRA_SCANNED_FORMAT)
            ?.let { runCatching { TagFormat.valueOf(it) }.getOrNull() } ?: TagFormat.OurHome
        NfcScans.emit(TagRef(tagId, format))
    }

    companion object {
        const val EXTRA_SCANNED_TAG = "scanned_tag"
        const val EXTRA_SCANNED_FORMAT = "scanned_format"
    }

    // While the app is in front it reads every tag itself (reader mode), so a
    // scan goes to the scan sheet instead of Home Assistant or another app.
    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.takeIf { it.isEnabled }?.enableReaderMode(
            this,
            { tag -> NfcScans.onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
        // Back from allowing notifications: Stay connected's notification says how it is now.
        StayConnected.refreshIfStale(this)
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        // A White only makes sense while the user is looking at the prompt.
        NfcScans.cancelWrite()
    }
}

@Composable
private fun Root(
    session: SessionManager,
    settings: AppSettings,
    openTab: StateFlow<String?>,
    onTabOpened: () -> Unit,
    prefill: StateFlow<SetupPrefill?>,
    onPrefillUsed: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val pendingPrefill by prefill.collectAsStateWithLifecycle()
    // A link that arrived while the saved sign-in was still being checked: not for this session.
    LaunchedEffect(state is AuthState.SignedIn, pendingPrefill) {
        if (state is AuthState.SignedIn && pendingPrefill != null) onPrefillUsed()
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (session.state.value == AuthState.Checking) session.restore()
    }
    // Back in front: pick up account changes made on the website meanwhile.
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { session.refreshUser() }
    }
    // Background request reminders run only while someone is signed in.
    // Registering for instant alerts on every sign-in/launch also keeps the
    // server's copy of this phone's token fresh.
    LaunchedEffect(state is AuthState.SignedIn, state is AuthState.SignedOut) {
        when (state) {
            is AuthState.SignedIn -> {
                RequestReminders.schedule(context)
                Push.register(context)
                // In the foreground Android always allows starting it, and
                // opening OurHome ends a Stop the member pressed.
                StayConnected.ensureRunning(context, byUser = true)
                // Picks up map place or sharing changes since the last run.
                Geofences.syncNow(context)
            }
            is AuthState.SignedOut -> RequestReminders.cancel(context)
            AuthState.Checking -> Unit
        }
    }
    when (val s = state) {
        AuthState.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is AuthState.SignedOut -> {
            ClearUserScope()
            LoginScreen(session, s.message, pendingPrefill, onPrefillUsed)
        }
        // Each account gets its own screen state (see UserScopes).
        is AuthState.SignedIn -> UserScope(s.user.id) {
            // A temporary password is replaced before anything else (web: the
            // /change-password gate); Home follows once it's done.
            if (s.user.mustChangePassword == true) ChoosePasswordScreen(session, s.user)
            else HomeScreen(session, settings, s.user, openTab, onTabOpened)
        }
    }
}
