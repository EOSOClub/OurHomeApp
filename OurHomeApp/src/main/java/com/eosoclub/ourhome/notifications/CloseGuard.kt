package com.eosoclub.ourhome.notifications

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import com.eosoclub.ourhome.R
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Keep Our Home running" and "Keep Our Home on this phone" (like Alli360):
 *
 * 1. **Device admin** ([CloseGuardAdminReceiver]): while active in Android's
 *    Device admin settings, Android itself refuses to uninstall Our Home from
 *    the home screen, App info, or anywhere else. Turning it on in Our Home
 *    sets a parent PIN (4–8 digits, stored only as a salted SHA-256 hash);
 *    entering that PIN on Profile (or Login when offline) removes Device admin
 *    and opens Android's uninstall prompt.
 * 2. **Accessibility service** ([CloseGuardService]):
 *    - Holds Stay connected's connection instead of [StayConnectedService].
 *      Android's active apps list (the one with the Stop button) only shows
 *      apps running a foreground service, so OurHome isn't in it.
 *    - While an adult looks after this member ([StayConnected.isRequired]) or
 *      Device admin is active, closes OurHome's App info page (where Force
 *      stop is), its Device admin page (where Deactivate is), Android's
 *      package-installer uninstall dialog for OurHome (without blocking app
 *      updates), and this service's own Accessibility pages, by going to the
 *      home screen.
 * 3. **Emergency offline recovery without PIN**: if the parent PIN is lost and
 *    there is no internet connection, an in-app 15-minute emergency unlock
 *    timer (or Android Safe Mode) lets the phone unlock and uninstall safely.
 */
object CloseGuard {
    private const val TAG = "CloseGuard"
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val PREFS = "close_guard"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_UNLOCKED_UNTIL_MS = "unlocked_until_ms"
    private const val KEY_FAILED_ATTEMPTS = "pin_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL_MS = "pin_lockout_until_ms"
    private const val KEY_EMERGENCY_STARTED_AT_MS = "emergency_started_at_ms"
    private const val KEY_EMERGENCY_READY_AT_MS = "emergency_ready_at_ms"
    private const val KEY_EMERGENCY_SENT_STAGES = "emergency_sent_stages"

    /** How long after unlocking Settings / Package Installer stay unblocked. */
    internal const val UNLOCK_WINDOW_MS = 2 * 60_000L

    /** Brute-force protection: after this many wrong tries, wait [PIN_LOCKOUT_MS]. */
    internal const val MAX_PIN_ATTEMPTS = 5
    internal const val PIN_LOCKOUT_MS = 30_000L

    /**
     * Offline PIN-less emergency unlock cooldown: 5 hours. Once started, it
     * cannot be canceled and alerts the supervising parents at start, in 2 hours,
     * 1 hour before unlock (4 hours in), and when unlocked (5 hours in).
     */
    internal const val EMERGENCY_COOLDOWN_MS = 5 * 60 * 60_000L

    enum class EmergencyStage(val wire: String, val offsetMs: Long) {
        /** Sent immediately when the 5-hour countdown starts (5 hours left). */
        Started("started", 0L),
        /** Sent 2 hours after start (3 hours left). */
        TwoHoursElapsed("2h_elapsed", 2 * 60 * 60_000L),
        /** Sent 1 hour before unlock, i.e. 4 hours after start (1 hour left). */
        OneHourLeft("1h_left", 4 * 60 * 60_000L),
        /** Sent when the 5-hour timer finishes / unlocks. */
        Unlocked("unlocked", EMERGENCY_COOLDOWN_MS),
    }

    /**
     * Which milestone notifications are due at [nowMs] for an emergency unlock
     * that began at [startedAtMs] and hasn't yet sent the stages in [alreadySent].
     */
    internal fun dueEmergencyStages(
        startedAtMs: Long,
        nowMs: Long,
        alreadySent: Set<String>,
    ): List<EmergencyStage> {
        if (startedAtMs <= 0L) return emptyList()
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        return EmergencyStage.entries.filter { stage ->
            elapsed >= stage.offsetMs && stage.wire !in alreadySent
        }
    }

    /** The running service in this process, set by the service itself. */
    @Volatile
    internal var service: CloseGuardService? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Turned on in Android's Accessibility settings. */
    fun isEnabled(context: Context): Boolean = listsService(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        context.packageName,
        CloseGuardService::class.java.name,
    )

    /** Active in Android's Device admin settings, so Android blocks uninstalling Our Home. */
    fun isAdminActive(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(context)) == true
    }.getOrDefault(false)

    /** Both Accessibility ("Keep Our Home running") and Device admin (uninstall lock) are on. */
    fun isFullyEnabled(context: Context): Boolean = isEnabled(context) && isAdminActive(context)

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, CloseGuardAdminReceiver::class.java)

    /** Opens Android's system screen to activate Our Home as a Device admin. */
    fun activateAdminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.close_guard_admin_description))
        }

    /** Opens Android's standard uninstall confirmation for Our Home (once Device admin is off). */
    fun uninstallIntent(context: Context): Intent =
        Intent(Intent.ACTION_DELETE, "package:${context.packageName}".toUri())

    // --- Parent PIN (4–8 digits, salted SHA-256 + brute-force cooldown) -----

    /** A parent PIN is 4 to 8 digits. */
    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    internal fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** Whether a parent PIN has been set on this phone. */
    fun hasPin(context: Context): Boolean = !prefs(context).getString(KEY_PIN_HASH, null).isNullOrEmpty()

    /**
     * Saves a new parent PIN (salted SHA-256 with SecureRandom) and clears any
     * temporary unlock window. Never cancels a running 5-hour emergency unlock.
     */
    fun setPin(context: Context, pin: String) {
        val clean = pin.trim()
        require(isValidPin(clean)) { "PIN must be 4 to 8 digits" }
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit {
            putString(KEY_PIN_SALT, salt)
            putString(KEY_PIN_HASH, hashPin(clean, salt))
            remove(KEY_UNLOCKED_UNTIL_MS)
            remove(KEY_FAILED_ATTEMPTS)
            remove(KEY_LOCKOUT_UNTIL_MS)
        }
    }

    /** Seconds left on the brute-force cooldown after too many wrong PINs (0 when ready). */
    fun pinLockoutRemainingSec(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val until = prefs(context).getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        return if (until > nowMs) (((until - nowMs) + 999L) / 1000L).toInt() else 0
    }

    /** Checks [pin] against the saved parent PIN, enforcing a 30 s cooldown every 5 wrong tries. */
    fun verifyPin(context: Context, pin: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (pinLockoutRemainingSec(context, nowMs) > 0) return false
        val p = prefs(context)
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val expected = p.getString(KEY_PIN_HASH, null) ?: return false
        val actual = hashPin(pin.trim(), salt)
        if (constantTimeEquals(actual, expected)) {
            p.edit {
                remove(KEY_FAILED_ATTEMPTS)
                remove(KEY_LOCKOUT_UNTIL_MS)
            }
            return true
        }
        val attempts = p.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        p.edit {
            if (attempts >= MAX_PIN_ATTEMPTS) {
                putInt(KEY_FAILED_ATTEMPTS, 0)
                putLong(KEY_LOCKOUT_UNTIL_MS, nowMs + PIN_LOCKOUT_MS)
            } else {
                putInt(KEY_FAILED_ATTEMPTS, attempts)
            }
        }
        return false
    }

    /**
     * Checks [pin]; when right, pauses the Accessibility close guard for two
     * minutes (persisted across process restarts), clears the PIN, and tells
     * Android to remove Our Home's Device admin so it can be uninstalled.
     */
    fun unlockWithPin(context: Context, pin: String): Boolean {
        if (!verifyPin(context, pin)) return false
        unlockAndRemoveAdmin(context)
        return true
    }

    // --- 5-hour emergency unlock (cannot be canceled; notifies parents) -----

    /**
     * Starts the 5-hour emergency unlock countdown. Once started, it cannot be
     * canceled, and schedules parent notifications at:
     * - Start (0h)
     * - 2 hours elapsed (3h remaining)
     * - 1 hour before unlock (4h elapsed, 1h remaining)
     * - Unlocked (5h elapsed)
     */
    fun startEmergencyUnlock(context: Context, nowMs: Long = System.currentTimeMillis()): Long {
        val p = prefs(context)
        val existing = p.getLong(KEY_EMERGENCY_READY_AT_MS, 0L)
        if (existing > 0L) return existing
        val readyAt = nowMs + EMERGENCY_COOLDOWN_MS
        p.edit {
            putLong(KEY_EMERGENCY_STARTED_AT_MS, nowMs)
            putLong(KEY_EMERGENCY_READY_AT_MS, readyAt)
            putStringSet(KEY_EMERGENCY_SENT_STAGES, emptySet())
        }
        PhoneCheckIn.scheduleEmergencyNotifications(context.applicationContext)
        return readyAt
    }

    /** Epoch millis when the 5-hour emergency unlock started, or 0 when not active. */
    fun emergencyStartedAtMs(context: Context): Long =
        prefs(context).getLong(KEY_EMERGENCY_STARTED_AT_MS, 0L)

    /** Epoch millis when the 5-hour emergency unlock finishes, or 0 when not active. */
    fun emergencyReadyAtMs(context: Context): Long =
        prefs(context).getLong(KEY_EMERGENCY_READY_AT_MS, 0L)

    internal fun emergencySentStages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EMERGENCY_SENT_STAGES, emptySet()).orEmpty()

    internal fun markEmergencyStageSent(context: Context, stage: EmergencyStage) {
        val p = prefs(context)
        val updated = p.getStringSet(KEY_EMERGENCY_SENT_STAGES, emptySet()).orEmpty() + stage.wire
        p.edit { putStringSet(KEY_EMERGENCY_SENT_STAGES, updated) }
    }

    /**
     * Completes the 5-hour emergency unlock once the timer has elapsed; false
     * if still counting down. Sends the final "unlocked" notification to the
     * supervising parents' phones.
     */
    fun completeEmergencyUnlock(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val readyAt = emergencyReadyAtMs(context)
        if (readyAt <= 0L || nowMs < readyAt) return false
        PhoneCheckIn.notifyEmergencyStageNow(context.applicationContext, EmergencyStage.Unlocked)
        unlockAndRemoveAdmin(context, nowMs)
        return true
    }

    /** Removes Device admin and pauses the guard for 2 minutes (persisted across process death). */
    fun unlockAndRemoveAdmin(context: Context, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit {
            putLong(KEY_UNLOCKED_UNTIL_MS, nowMs + UNLOCK_WINDOW_MS)
            remove(KEY_PIN_SALT)
            remove(KEY_PIN_HASH)
            remove(KEY_FAILED_ATTEMPTS)
            remove(KEY_LOCKOUT_UNTIL_MS)
            remove(KEY_EMERGENCY_STARTED_AT_MS)
            remove(KEY_EMERGENCY_READY_AT_MS)
            remove(KEY_EMERGENCY_SENT_STAGES)
        }
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(adminComponent(context))
        }.onFailure { Log.w(TAG, "couldn't remove active Device admin", it) }
        PhoneCheckIn.sendNow(context.applicationContext)
    }

    /** Signed out: release Device admin and clear any saved PIN so a signed-out phone is never stuck locked. */
    fun signedOut(context: Context) {
        prefs(context).edit { clear() }
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(adminComponent(context))
        }
    }

    /** True while inside the 2-minute window after unlocking. */
    internal fun isUnlockedNow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = prefs(context).getLong(KEY_UNLOCKED_UNTIL_MS, 0L)
        return until > nowMs
    }

    /**
     * Whether the Accessibility service should guard Settings / uninstall screens
     * right now: only while supervised ([StayConnected.isRequired]) or Device
     * admin is active, and not inside the parent unlock window.
     */
    internal fun isGuarding(context: Context): Boolean =
        !isUnlockedNow(context) && (StayConnected.isRequired(context) || isAdminActive(context))

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
     * The Device admin "Deactivate" button's words in this phone's language
     * (Settings' `remove_device_admin`), and English.
     */
    @SuppressLint("DiscouragedApi")
    internal fun deactivateAdminLabels(context: Context): Set<String> {
        val labels = mutableSetOf("Deactivate this device admin app")
        runCatching {
            val res = context.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
            val id = res.getIdentifier("remove_device_admin", "string", SETTINGS_PACKAGE)
            if (id != 0) labels += res.getString(id)
        }.onFailure { Log.w(TAG, "can't read Settings' remove_device_admin text; using English", it) }
        return labels
    }

    /**
     * The "Activate device admin app" prompt's words (Settings'
     * `add_device_admin` button), its English title, and English. This is the
     * screen the parent needs to turn protection on, so the guard must never
     * close it even though it shows the admin's [close_guard_admin_name] label.
     */
    @SuppressLint("DiscouragedApi")
    internal fun activateAdminLabels(context: Context): Set<String> {
        val labels = DEFAULT_ACTIVATE_LABELS.toMutableSet()
        runCatching {
            val res = context.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
            val id = res.getIdentifier("add_device_admin", "string", SETTINGS_PACKAGE)
            if (id != 0) labels += res.getString(id)
        }.onFailure { Log.w(TAG, "can't read Settings' add_device_admin text; using English", it) }
        return labels
    }

    /**
     * Whether a screen must be closed on a guarded phone:
     * - OurHome's App info (its name and a Force stop button on one screen)
     * - This service's own page in Accessibility ([guardName])
     * - OurHome's Device admin deactivation page ([adminName], or [appName] with a Deactivate button)
     * - Android's package installer *uninstall* prompt for [appName] (never its
     *   update/install prompt, so self-hosted app updates still install cleanly)
     * Never OurHome itself, and never the "Activate device admin app" prompt
     * ([activateLabels]): the parent needs that to turn protection on, and it
     * shows the same [adminName] label as the deactivation page.
     * [shows]: whether the screen has a piece of text exactly so (ignoring case).
     */
    internal fun shouldClose(
        windowPackage: String?,
        ownPackage: String,
        appName: String,
        guardName: String,
        forceStopLabels: Set<String>,
        adminName: String = "Keep Our Home on this phone",
        deactivateLabels: Set<String> = emptySet(),
        activateLabels: Set<String> = DEFAULT_ACTIVATE_LABELS,
        uninstallLabels: Set<String> = DEFAULT_UNINSTALL_LABELS,
        shows: (String) -> Boolean,
    ): Boolean {
        if (windowPackage == null || windowPackage == ownPackage) return false
        // The "Activate device admin app" prompt shows [adminName] too, but the
        // parent needs it to turn protection on, so leave it open.
        if (activateLabels.any(shows)) return false
        if (shows(guardName) || shows(adminName)) return true
        if (!shows(appName)) return false
        if (windowPackage.contains("packageinstaller", ignoreCase = true)) {
            return uninstallLabels.any(shows)
        }
        return forceStopLabels.any(shows) || deactivateLabels.any(shows)
    }

    internal val DEFAULT_UNINSTALL_LABELS = setOf(
        "Do you want to uninstall this app?",
        "Uninstall",
    )

    internal val DEFAULT_ACTIVATE_LABELS = setOf(
        "Activate device admin app",
        "Activate this device admin app",
    )
}

class CloseGuardAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        PhoneCheckIn.sendNow(context.applicationContext)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "An adult who looks after you keeps Our Home on this phone. Unlock it with the parent PIN in Our Home first."

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        PhoneCheckIn.sendNow(context.applicationContext)
    }
}

class CloseGuardService : AccessibilityService() {
    private var connection: StreamConnection? = null
    private var forceStopLabels: Set<String> = emptySet()
    private var deactivateLabels: Set<String> = emptySet()
    private var activateLabels: Set<String> = emptySet()
    private var lastCheckAt = 0L
    private var lastToastAt = 0L
    private var tornDown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        forceStopLabels = CloseGuard.forceStopLabels(this)
        deactivateLabels = CloseGuard.deactivateAdminLabels(this)
        activateLabels = CloseGuard.activateAdminLabels(this)
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
        if (!CloseGuard.isGuarding(this)) return
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
            adminName = getString(R.string.close_guard_admin_name),
            deactivateLabels = deactivateLabels,
            activateLabels = activateLabels,
        ) { text -> root.shows(text) }
        if (!close) return
        Log.i(TAG, "closing a settings or installer page that could stop or remove OurHome")
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (now - lastToastAt > TOAST_EVERY_MS) {
            lastToastAt = now
            Toast.makeText(
                this,
                "An adult who looks after you keeps Our Home on this phone. Use the parent PIN in Our Home to unlock it.",
                Toast.LENGTH_SHORT,
            ).show()
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

