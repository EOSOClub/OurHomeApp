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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.data.BlockedAppRule
import com.eosoclub.ourhome.data.ThemeMode
import com.eosoclub.ourhome.data.blockedReason
import com.eosoclub.ourhome.ui.theme.OurHomeTheme

/**
 * The blocked screen: shown over an app a supervising adult blocked
 * ([AppBlocker]), saying who blocked it, or over one whose daily limit is used
 * up ([AppLimiter]). OK and Back go to the home screen, never back into the
 * app. Opening the app again shows it again. For a used-up limit, while points
 * can buy time, Get more time opens Profile, where they buy it.
 */
class AppBlockedActivity : ComponentActivity() {
    private var title by mutableStateOf("")
    private var reason by mutableStateOf("")
    private var moreTime by mutableStateOf(false)

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
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { goHome() }) { Text("OK") }
                        if (moreTime) {
                            OutlinedButton(onClick = { openProfile() }) { Text("Get more time") }
                        }
                    }
                }
            }
        }
    }

    /** Another blocked app (or used-up limit) while this shows. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "This app is blocked" }
        reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        moreTime = intent.getBooleanExtra(EXTRA_MORE_TIME, false)
    }

    /** Profile, where points buy more time; this screen goes. */
    private fun openProfile() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(RequestReminders.EXTRA_OPEN_TAB, AppUpdateAlerts.TAB_PROFILE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_MORE_TIME = "more_time"

        /** Over an app [block] names: "YouTube is blocked", and who blocked it. */
        fun intent(context: Context, block: BlockedAppRule): Intent =
            intent(context, "${block.label.ifEmpty { "This app" }} is blocked", blockedReason(block.by))

        /** [moreTime]: also offer Get more time (a used-up limit while points can buy time). */
        fun intent(context: Context, title: String, reason: String, moreTime: Boolean = false): Intent =
            Intent(context, AppBlockedActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_MORE_TIME, moreTime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
