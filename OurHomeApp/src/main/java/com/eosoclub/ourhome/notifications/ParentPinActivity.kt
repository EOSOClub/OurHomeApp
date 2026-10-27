package com.eosoclub.ourhome.notifications

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.data.ThemeMode
import com.eosoclub.ourhome.ui.theme.OurHomeTheme
import kotlinx.coroutines.delay

/**
 * The parent PIN screen over a protected screen ([CloseGuard.Screen]):
 * OurHome's App info, its Device admin page, Keep Our Home running in
 * Accessibility, or Private DNS while the web filter is on. The right PIN
 * opens it ([CloseGuard.openWithPin]) and this screen goes, showing it.
 * Cancel, Back, Home or the screen turning off without the PIN go to the home
 * screen, never back to it. Opening it again shows this again.
 */
class ParentPinActivity : ComponentActivity() {
    private var screen by mutableStateOf(CloseGuard.Screen.AppInfo)
    private var windowPackage = ""

    /** Opened with the PIN, or already on the way home: leaving needs nothing more. */
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        read(intent)
        val settings = (application as OurHomeApp).settings
        setContent {
            // The app's own theme choice, and bar icons to match (as MainActivity).
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            OurHomeTheme(dark = dark) {
                BackHandler { goHome() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PinForm(screen, onOpen = ::open, onCancel = ::goHome)
                }
            }
        }
    }

    /** Another protected screen while this shows. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    /**
     * Left some other way (Home, Recents, the screen turning off): the
     * protected screen is still under this one, so the phone goes home.
     */
    override fun onStop() {
        super.onStop()
        if (done) return
        done = true
        CloseGuard.service?.goHomeNow()
        finish()
    }

    private fun read(intent: Intent) {
        screen = CloseGuard.Screen.of(intent.getStringExtra(EXTRA_SCREEN)) ?: CloseGuard.Screen.AppInfo
        windowPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
    }

    /**
     * The right PIN: the protected screen under this one shows. This task is
     * moved behind first: finished in front, Android would go to the home
     * screen instead (a task the guard started returns there), which also
     * counts as leaving and protects the screen again.
     */
    private fun open(pin: String): Boolean {
        if (!CloseGuard.openWithPin(applicationContext, pin, screen, windowPackage)) return false
        done = true
        moveTaskToBack(true)
        finish()
        return true
    }

    private fun goHome() {
        done = true
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"
        private const val EXTRA_PACKAGE = "package"

        /** Over [screen], shown by the app [windowPackage]: leaving that app protects it again. */
        fun intent(context: Context, screen: CloseGuard.Screen, windowPackage: String): Intent =
            Intent(context, ParentPinActivity::class.java)
                .putExtra(EXTRA_SCREEN, screen.wire)
                .putExtra(EXTRA_PACKAGE, windowPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** What [screen] is for, so the child knows why it's protected. */
        internal fun why(screen: CloseGuard.Screen): String = when (screen) {
            CloseGuard.Screen.AppInfo -> "Our Home's App info is where it can be stopped."
            CloseGuard.Screen.DeviceAdmin -> "This page turns off Prevent uninstall, which keeps Our Home on this phone."
            CloseGuard.Screen.Accessibility -> "This page turns off Keep Our Home running."
            CloseGuard.Screen.PrivateDns -> "Private DNS is where this phone's web filter is set."
            CloseGuard.Screen.Uninstall -> "This removes Our Home from this phone."
        }
    }
}

/** The PIN, its wrong-PIN wait, Open and Cancel. [onOpen]: false for a wrong PIN. */
@Composable
private fun PinForm(screen: CloseGuard.Screen, onOpen: (String) -> Boolean, onCancel: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    val lockoutSec = CloseGuard.pinLockoutRemainingSec(context, nowMs)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The wait counts down while it shows.
    LaunchedEffect(lockoutSec > 0) {
        while (CloseGuard.pinLockoutRemainingSec(context) > 0) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
        nowMs = System.currentTimeMillis()
    }

    fun tryOpen() {
        if (!CloseGuard.isValidPin(pin) || lockoutSec > 0) return
        if (onOpen(pin)) return
        pin = ""
        nowMs = System.currentTimeMillis()
        error = if (CloseGuard.pinLockoutRemainingSec(context) > 0) null else "That's not the parent PIN."
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ask an adult", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "${ParentPinActivity.why(screen)} An adult who looks after you protects it. They can enter the parent PIN to open it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = pin,
            onValueChange = {
                if (it.length <= 8 && it.all(Char::isDigit)) {
                    pin = it
                    error = null
                }
            },
            label = { Text("Parent PIN") },
            singleLine = true,
            enabled = lockoutSec == 0,
            isError = error != null || lockoutSec > 0,
            supportingText = (if (lockoutSec > 0) "Too many tries. Try again in ${lockoutSec}s." else error)
                ?.let { msg -> { Text(msg) } },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { tryOpen() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(enabled = lockoutSec == 0 && CloseGuard.isValidPin(pin), onClick = { tryOpen() }) { Text("Open") }
        }
        Text(
            "The adults who look after you are told when the PIN is used, or entered wrong too many times.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
