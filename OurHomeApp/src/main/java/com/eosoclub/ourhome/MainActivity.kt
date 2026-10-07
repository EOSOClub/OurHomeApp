package com.eosoclub.ourhome

import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eosoclub.ourhome.data.AppSettings
import com.eosoclub.ourhome.data.AuthState
import com.eosoclub.ourhome.data.SessionManager
import com.eosoclub.ourhome.data.ThemeMode
import com.eosoclub.ourhome.nfc.NfcScans
import com.eosoclub.ourhome.nfc.TagFormat
import com.eosoclub.ourhome.nfc.TagRef
import com.eosoclub.ourhome.notifications.Push
import com.eosoclub.ourhome.notifications.RequestReminders
import com.eosoclub.ourhome.ui.HomeScreen
import com.eosoclub.ourhome.ui.LoginScreen
import com.eosoclub.ourhome.ui.theme.OurHomeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    // A tab a notification asked to open ("requests"); consumed by HomeScreen.
    private val openTab = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            openTab.value = intent.getStringExtra(RequestReminders.EXTRA_OPEN_TAB)
            emitScanFrom(intent)
        }
        val app = application as OurHomeApp
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
                    Root(app.session, app.settings, openTab, onTabOpened = { openTab.value = null })
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
    }

    /** A tag handed over by QuickScanActivity or a quick-scan notification tap. */
    private fun emitScanFrom(intent: Intent) {
        val tagId = intent.getStringExtra(EXTRA_SCANNED_TAG) ?: return
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
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        // A write only makes sense while the user is looking at the prompt.
        NfcScans.cancelWrite()
    }
}

@Composable
private fun Root(
    session: SessionManager,
    settings: AppSettings,
    openTab: StateFlow<String?>,
    onTabOpened: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (session.state.value == AuthState.Checking) session.restore()
    }
    // Background request reminders run only while someone is signed in.
    // Registering for instant alerts on every sign-in/launch also keeps the
    // server's copy of this phone's token fresh.
    LaunchedEffect(state is AuthState.SignedIn, state is AuthState.SignedOut) {
        when (state) {
            is AuthState.SignedIn -> {
                RequestReminders.schedule(context)
                Push.register(context)
            }
            is AuthState.SignedOut -> RequestReminders.cancel(context)
            AuthState.Checking -> Unit
        }
    }
    when (val s = state) {
        AuthState.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is AuthState.SignedOut -> LoginScreen(session, s.message)
        is AuthState.SignedIn -> HomeScreen(session, settings, s.user, openTab, onTabOpened)
    }
}
