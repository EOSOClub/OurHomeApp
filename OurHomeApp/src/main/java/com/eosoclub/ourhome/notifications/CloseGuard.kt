package com.eosoclub.ourhome.notifications

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.net.toUri
import com.eosoclub.ourhome.R

/**
 * "Keep Our Home running": an Accessibility service the member (or a parent)
 * turns on in Android's Accessibility settings. Android keeps an enabled
 * Accessibility service running, so it does two things:
 *
 * - Holds Stay connected's connection instead of [StayConnectedService].
 *   Android's active apps list (the one with the Stop button) only shows apps
 *   running a foreground service, so OurHome isn't in it.
 * - While an adult looks after this member ([StayConnected.isRequired]),
 *   closes OurHome's App info page (where Force stop is) and this service's
 *   own Accessibility pages, by going to the home screen.
 *
 * It looks at what's on screen only to recognise those pages, by OurHome's
 * name next to the Force stop button or this service's name. Nothing it sees
 * is stored or sent. It can't stop safe mode, or Accessibility being turned
 * off before the service starts.
 *
 * On Android 13 and newer, Android greys this switch out for an app that
 * wasn't installed from a store until "Allow restricted settings" is chosen
 * in OurHome's App info (the ⋮ menu).
 */
object CloseGuard {
    private const val TAG = "CloseGuard"
    private const val SETTINGS_PACKAGE = "com.android.settings"

    /** The running service in this process, set by the service itself. */
    @Volatile
    internal var service: CloseGuardService? = null

    /** Turned on in Android's Accessibility settings. */
    fun isEnabled(context: Context): Boolean = listsService(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        context.packageName,
        CloseGuardService::class.java.name,
    )

    /**
     * Whether Android's list of enabled Accessibility services ("pkg/cls:...",
     * the class written in full or starting with ".") has ours.
     */
    internal fun listsService(enabled: String?, packageName: String, className: String): Boolean =
        enabled.orEmpty().split(':').any { entry ->
            val (pkg, cls) = entry.trim().split('/', limit = 2).takeIf { it.size == 2 } ?: return@any false
            pkg == packageName && (cls == className || packageName + cls == className)
        }

    fun accessibilitySettings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun appInfo(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    /**
     * The Force stop button's words in this phone's language (Settings' own
     * text), and English. By name, since another app's resources have no R.
     */
    @SuppressLint("DiscouragedApi")
    internal fun forceStopLabels(context: Context): Set<String> {
        val labels = mutableSetOf("Force stop")
        runCatching {
            val res = context.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
            val id = res.getIdentifier("force_stop", "string", SETTINGS_PACKAGE)
            if (id != 0) labels += res.getString(id)
        }.onFailure { Log.w(TAG, "can't read Settings' Force stop text; using English", it) }
        return labels
    }

    /**
     * Whether a screen must be closed on a required phone: OurHome's App info
     * (its name and a Force stop button on one screen) or this service's own
     * page in Accessibility (its name). Never OurHome itself. [shows]: whether
     * the screen has a piece of text exactly so (ignoring case).
     */
    internal fun shouldClose(
        windowPackage: String?,
        ownPackage: String,
        appName: String,
        guardName: String,
        forceStopLabels: Set<String>,
        shows: (String) -> Boolean,
    ): Boolean {
        if (windowPackage == null || windowPackage == ownPackage) return false
        if (shows(guardName)) return true
        return shows(appName) && forceStopLabels.any(shows)
    }
}

class CloseGuardService : AccessibilityService() {
    private var connection: StreamConnection? = null
    private var forceStopLabels: Set<String> = emptySet()
    private var lastCheckAt = 0L
    private var lastToastAt = 0L
    private var tornDown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        forceStopLabels = CloseGuard.forceStopLabels(this)
        CloseGuard.service = this
        // Same rules as any other start (an adult's Stop is still respected).
        StayConnected.ensureRunning(applicationContext)
    }

    /** Holds Stay connected's connection while it's on, in place of the foreground service. */
    @Synchronized
    internal fun connectIfOn() {
        if (!StayConnected.isOn(this)) {
            disconnect()
            return
        }
        if (connection != null) return
        StayConnected.stopService(this)
        connection = StreamConnection(applicationContext, StayConnected::setConnected, ::disconnect).also { it.start() }
    }

    @Synchronized
    internal fun disconnect() {
        val held = connection ?: return
        connection = null
        held.stop()
        StayConnected.setConnected(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!StayConnected.isRequired(this)) return
        // Content changes come often: look at most twice a second.
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastCheckAt < CHECK_EVERY_MS) return
        lastCheckAt = now
        val root = rootInActiveWindow ?: return
        val close = CloseGuard.shouldClose(
            windowPackage = root.packageName?.toString(),
            ownPackage = packageName,
            appName = getString(R.string.app_name),
            guardName = getString(R.string.close_guard_name),
            forceStopLabels = forceStopLabels,
        ) { text -> root.shows(text) }
        if (!close) return
        Log.i(TAG, "closing a settings page that could stop OurHome")
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (now - lastToastAt > TOAST_EVERY_MS) {
            lastToastAt = now
            Toast.makeText(this, "An adult who looks after you keeps Our Home running.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Android's own text search (part of a word counts), then an exact match. */
    private fun AccessibilityNodeInfo.shows(text: String): Boolean =
        findAccessibilityNodeInfosByText(text).any { node ->
            node.text?.toString()?.trim().equals(text, ignoreCase = true) ||
                node.contentDescription?.toString()?.trim().equals(text, ignoreCase = true)
        }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    /** Turned off in Accessibility: the foreground service takes the connection back. */
    private fun tearDown() {
        if (tornDown) return
        tornDown = true
        if (CloseGuard.service === this) CloseGuard.service = null
        disconnect()
        StayConnected.ensureRunning(applicationContext)
    }

    private companion object {
        const val TAG = "CloseGuard"
        const val CHECK_EVERY_MS = 500L
        const val TOAST_EVERY_MS = 5_000L
    }
}
