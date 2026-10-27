package com.eosoclub.ourhome.notifications

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.BlockedAppRule
import com.eosoclub.ourhome.data.PinEvent
import com.eosoclub.ourhome.data.PinEvents
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * "Keep Our Home running" and "Keep Our Home on this phone" (like Alli360):
 *
 * 1. **Device admin** ([CloseGuardAdminReceiver]): while active in Android's
 *    Device admin settings, Android itself refuses to uninstall Our Home from
 *    the home screen, App info, or anywhere else. Turning it on in Our Home
 *    sets a parent PIN (4 to 8 digits, stored only as a salted SHA-256 hash);
 *    entering that PIN on Profile (or Login when offline) removes Device admin
 *    and opens Android's uninstall prompt.
 * 2. **Accessibility service** ([CloseGuardService]):
 *    - Holds Stay connected's connection instead of [StayConnectedService].
 *      Android's active apps list (the one with the Stop button) only shows
 *      apps running a foreground service, so OurHome isn't in it.
 *    - While an adult looks after this member ([StayConnected.isRequired]) or
 *      Device admin is active, protects OurHome's App info page (where Force
 *      stop is), its Device admin page (where Deactivate is), Android's
 *      package-installer uninstall dialog for OurHome (without blocking app
 *      updates), and this service's own Accessibility pages ([protectedScreen]).
 *    - While this phone uses the web filter a supervising adult chose
 *      ([WebFilter]), protects Android's Private DNS dialog the same way, so it
 *      can't be changed here ([shouldClosePrivateDns]).
 *    - Protecting one: with a parent PIN set, the PIN screen covers it
 *      ([ParentPinActivity]); the right PIN opens it until they leave it or
 *      the screen turns off ([openWithPin]), anything else goes to the home
 *      screen. Without a PIN, and always for the uninstall prompt (Prevent
 *      uninstall refuses it anyway), straight to the home screen.
 *    - The adults who look after this member are told when the PIN opens a
 *      screen, unlocks Our Home, or is wrong too often ([PinEvent]).
 *    - Covers an app a supervising adult blocked with the blocked screen
 *      ([AppBlocker]), and says which app opens for daily limits and schedules
 *      ([AppLimiter]), which cover it the same way once its time is used up or
 *      while a schedule is on.
 * 3. **Emergency offline recovery without PIN**: if the parent PIN is lost and
 *    there is no internet connection, an in-app emergency unlock timer (its
 *    length chosen by the parent with the PIN, [EmergencyDuration]) or
 *    Android Safe Mode lets the phone unlock and uninstall safely.
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
    private const val KEY_EMERGENCY_DURATION = "emergency_duration"
    private const val KEY_EMERGENCY_STARTED_AT_MS = "emergency_started_at_ms"
    private const val KEY_EMERGENCY_READY_AT_MS = "emergency_ready_at_ms"
    private const val KEY_EMERGENCY_SENT_STAGES = "emergency_sent_stages"
    private const val KEY_PASS_PACKAGE = "pass_package"
    private const val KEY_PASS_UNTIL_MS = "pass_until_ms"
    private const val KEY_PIN_EVENTS = "pin_events"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val SYSTEM_PACKAGE = "android"
    private val json = Json { ignoreUnknownKeys = true }
    private val pinEventsSerializer = ListSerializer(PinEvent.serializer())

    /** How long after unlocking Settings / Package Installer stay unblocked. */
    internal const val UNLOCK_WINDOW_MS = 2 * 60_000L

    /** The longest a screen the parent PIN opened stays open, even if they never leave it. */
    internal const val PASS_MAX_MS = 10 * 60_000L

    /**
     * The screens the guard protects. [wire]: the server's name for it (web
     * lib/parentPin.ts PIN_SCREENS). [pinOpens]: whether the parent PIN screen
     * covers it; never the uninstall prompt, which Prevent uninstall refuses
     * anyway (Unlock Our Home in Profile is the way to uninstall).
     */
    enum class Screen(val wire: String, val pinOpens: Boolean = true) {
        AppInfo("app_info"),
        DeviceAdmin("device_admin"),
        Accessibility("accessibility"),
        PrivateDns("private_dns"),
        Uninstall("uninstall", pinOpens = false);

        companion object {
            fun of(wire: String?): Screen? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** Brute-force protection: after this many wrong tries, wait [PIN_LOCKOUT_MS]. */
    internal const val MAX_PIN_ATTEMPTS = 5
    internal const val PIN_LOCKOUT_MS = 30_000L

    /** Default emergency unlock cooldown (5 hours). */
    internal const val EMERGENCY_COOLDOWN_MS = 5 * 60 * 60_000L

    /** One parent-notification milestone along a forgot-PIN emergency unlock countdown. */
    data class EmergencyStage(val wire: String, val offsetMs: Long) {
        companion object {
            val Started = EmergencyStage("started", 0L)
            val TwoHoursElapsed = EmergencyStage("2h_elapsed", 2 * 60 * 60_000L)
            val OneHourLeft = EmergencyStage("1h_left", 4 * 60 * 60_000L)
            val Unlocked = EmergencyStage("unlocked", EMERGENCY_COOLDOWN_MS)
        }
    }

    /**
     * Parent-configurable forgot-PIN emergency unlock durations. Each choice
     * adjusts both the unlock time and how many / how often notifications are
     * sent to the supervising parents' phones. An hour at least, so the
     * timer is never a quick way around the PIN (a phone that saved a removed
     * shorter choice falls back to 5 hours in [fromKey]).
     */
    enum class EmergencyDuration(
        val key: String,
        val label: String,
        val durationMs: Long,
        val scheduleNote: String,
        val stages: List<EmergencyStage>,
    ) {
        Hours1(
            key = "1h",
            label = "1 hour",
            durationMs = 60 * 60_000L,
            scheduleNote = "3 parent notifications: when started, 30 minutes before unlock, and when unlocked",
            stages = listOf(
                EmergencyStage("started", 0L),
                EmergencyStage("30m_left", 30 * 60_000L),
                EmergencyStage("unlocked", 60 * 60_000L),
            ),
        ),
        Hours5(
            key = "5h",
            label = "5 hours",
            durationMs = 5 * 60 * 60_000L,
            scheduleNote = "4 parent notifications: when started, in 2 hours, 1 hour before unlock, and when unlocked",
            stages = listOf(
                EmergencyStage.Started,
                EmergencyStage.TwoHoursElapsed,
                EmergencyStage.OneHourLeft,
                EmergencyStage.Unlocked,
            ),
        ),
        Hours10(
            key = "10h",
            label = "10 hours",
            durationMs = 10 * 60 * 60_000L,
            scheduleNote = "5 parent notifications: when started, in 3 hours, in 6 hours, 1 hour before unlock, and when unlocked",
            stages = listOf(
                EmergencyStage("started", 0L),
                EmergencyStage("3h_elapsed", 3 * 60 * 60_000L),
                EmergencyStage("6h_elapsed", 6 * 60 * 60_000L),
                EmergencyStage("1h_left", 9 * 60 * 60_000L),
                EmergencyStage("unlocked", 10 * 60 * 60_000L),
            ),
        );

        companion object {
            fun fromKey(key: String?): EmergencyDuration =
                entries.firstOrNull { it.key == key } ?: Hours5
        }
    }

    /**
     * Which milestone notifications are due at [nowMs] for an emergency unlock
     * of [duration] that began at [startedAtMs] and hasn't yet sent the stages
     * in [alreadySent].
     */
    internal fun dueEmergencyStages(
        startedAtMs: Long,
        nowMs: Long,
        alreadySent: Set<String>,
        duration: EmergencyDuration = EmergencyDuration.Hours5,
    ): List<EmergencyStage> {
        if (startedAtMs <= 0L) return emptyList()
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        return duration.stages.filter { stage ->
            elapsed >= stage.offsetMs && stage.wire !in alreadySent
        }
    }

    /**
     * The milestone a check-in sends. Only the latest of [due]: the ones it
     * passed while the phone was off or offline are marked sent with it, so
     * the parents never get an older one after a newer one. While an unlock is
     * [running], what's due decides, whichever worker asked; [forced] counts
     * only once it's over ("unlocked", after completing cleared the countdown).
     */
    internal fun stageToSend(due: List<EmergencyStage>, forced: String?, running: Boolean): String? =
        due.lastOrNull()?.wire ?: forced.takeIf { !running }

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

    // --- Parent PIN (4 to 8 digits, salted SHA-256 + brute-force cooldown) --

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

    /** The parent-configured forgot-PIN emergency unlock duration (defaults to 5 hours). */
    fun emergencyDuration(context: Context): EmergencyDuration =
        EmergencyDuration.fromKey(prefs(context).getString(KEY_EMERGENCY_DURATION, null))

    /**
     * Saves a new parent PIN (salted SHA-256 with SecureRandom) and the
     * parent's chosen [duration] for forgot-PIN emergency unlock. Never cancels
     * or shortens a currently running emergency unlock countdown.
     */
    fun setPin(context: Context, pin: String, duration: EmergencyDuration = emergencyDuration(context)) {
        val clean = pin.trim()
        require(isValidPin(clean)) { "PIN must be 4 to 8 digits" }
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        val p = prefs(context)
        val emergencyRunning = p.getLong(KEY_EMERGENCY_READY_AT_MS, 0L) > 0L
        p.edit {
            putString(KEY_PIN_SALT, salt)
            putString(KEY_PIN_HASH, hashPin(clean, salt))
            if (!emergencyRunning) {
                putString(KEY_EMERGENCY_DURATION, duration.key)
            }
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

    /**
     * Checks [pin] against the saved parent PIN, enforcing a 30 s cooldown
     * every 5 wrong tries; each cooldown is told to the supervising adults.
     */
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
        if (attempts >= MAX_PIN_ATTEMPTS) recordPinEvent(context, PinEvents.WRONG_PIN, nowMs = nowMs)
        return false
    }

    /**
     * Checks [pin]; when right, pauses the Accessibility close guard for two
     * minutes (persisted across process restarts), clears the PIN, and tells
     * Android to remove Our Home's Device admin so it can be uninstalled. The
     * supervising adults are told.
     */
    fun unlockWithPin(context: Context, pin: String): Boolean {
        if (!verifyPin(context, pin)) return false
        recordPinEvent(context, PinEvents.UNLOCKED)
        unlockAndRemoveAdmin(context)
        return true
    }

    // --- The parent PIN screen over a protected screen (ParentPinActivity) --

    /**
     * Checks [pin] on the PIN screen over a protected [screen] in
     * [windowPackage]; when right, that screen opens until they leave
     * [windowPackage] ([shouldEndPass]) or the screen turns off, [PASS_MAX_MS]
     * at most, and the supervising adults are told. Prevent uninstall, Keep
     * Our Home running and the PIN all stay.
     */
    fun openWithPin(
        context: Context,
        pin: String,
        screen: Screen,
        windowPackage: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!verifyPin(context, pin, nowMs)) return false
        prefs(context).edit {
            putString(KEY_PASS_PACKAGE, windowPackage)
            putLong(KEY_PASS_UNTIL_MS, nowMs + PASS_MAX_MS)
        }
        recordPinEvent(context, PinEvents.OPENED, screen.wire, nowMs)
        return true
    }

    /** The app whose protected screen the parent PIN opened, while it's open; null otherwise. */
    internal fun passPackage(context: Context, nowMs: Long = System.currentTimeMillis()): String? {
        val p = prefs(context)
        if (p.getLong(KEY_PASS_UNTIL_MS, 0L) <= nowMs) return null
        return p.getString(KEY_PASS_PACKAGE, null)
    }

    /** They left the screen the parent PIN opened, or the screen turned off: it's protected again. */
    fun endPass(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_PASS_PACKAGE) && !p.contains(KEY_PASS_UNTIL_MS)) return
        p.edit {
            remove(KEY_PASS_PACKAGE)
            remove(KEY_PASS_UNTIL_MS)
        }
    }

    /**
     * Whether the screen the parent PIN opened in [passPackage] was left: a
     * window of another app came up ([windowPackage], the one that changed,
     * not the active window, which still says the old app for a moment while
     * the home screen opens). Never for Our Home itself (its PIN screen), the
     * notification shade, Android's own dialogs, or [overPackages]: the
     * keyboard, which opens over Settings to type in the Private DNS box, and
     * the password manager, whose save prompt opens over it (it ended the pass,
     * so the PIN screen came back after each prompt).
     */
    internal fun shouldEndPass(
        passPackage: String,
        windowPackage: String?,
        ownPackage: String,
        overPackages: Set<String> = emptySet(),
    ): Boolean =
        windowPackage != null && windowPackage != passPackage && windowPackage != ownPackage &&
            windowPackage != SYSTEM_UI_PACKAGE && windowPackage != SYSTEM_PACKAGE && windowPackage !in overPackages

    /** The apps that open over others: the keyboard in use and the password manager (autofill service). */
    internal fun overPackages(context: Context): Set<String> = setOfNotNull(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.takeIf { it.isNotEmpty() },
        runCatching {
            context.getSystemService(AutofillManager::class.java)?.autofillServiceComponentName?.packageName
        }.getOrNull(),
    )

    // --- What the parent PIN did, for the supervising adults ------------------

    /** Kept until a check-in gets it through ([PhoneCheckIn]), which this asks for at once. */
    internal fun recordPinEvent(
        context: Context,
        kind: String,
        screen: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val event = PinEvent(UUID.randomUUID().toString(), kind, nowMs, screen)
        synchronized(this) {
            val kept = PinEvents.add(pendingPinEvents(context), event)
            prefs(context).edit { putString(KEY_PIN_EVENTS, json.encodeToString(pinEventsSerializer, kept)) }
        }
        PhoneCheckIn.sendNow(context.applicationContext)
    }

    /** What the parent PIN did that no check-in has got through yet, oldest first. */
    internal fun pendingPinEvents(context: Context): List<PinEvent> =
        prefs(context).getString(KEY_PIN_EVENTS, null)
            ?.let { runCatching { json.decodeFromString(pinEventsSerializer, it) }.getOrNull() }
            .orEmpty()

    /** A check-in got these through ([sent]: their ids). */
    internal fun pinEventsSent(context: Context, sent: Collection<String>) {
        if (sent.isEmpty()) return
        synchronized(this) {
            val left = PinEvents.withoutSent(pendingPinEvents(context), sent)
            prefs(context).edit {
                if (left.isEmpty()) remove(KEY_PIN_EVENTS)
                else putString(KEY_PIN_EVENTS, json.encodeToString(pinEventsSerializer, left))
            }
        }
    }

    // --- Configurable emergency unlock (cannot be canceled; notifies parents)

    /**
     * Starts the emergency unlock countdown using the parent's configured
     * [EmergencyDuration]. Once started, it cannot be canceled, and schedules
     * parent notifications according to [EmergencyDuration.stages].
     */
    fun startEmergencyUnlock(context: Context, nowMs: Long = System.currentTimeMillis()): Long {
        val p = prefs(context)
        val existing = p.getLong(KEY_EMERGENCY_READY_AT_MS, 0L)
        if (existing > 0L) return existing
        val duration = emergencyDuration(context)
        val readyAt = nowMs + duration.durationMs
        p.edit {
            putLong(KEY_EMERGENCY_STARTED_AT_MS, nowMs)
            putLong(KEY_EMERGENCY_READY_AT_MS, readyAt)
            putStringSet(KEY_EMERGENCY_SENT_STAGES, emptySet())
        }
        PhoneCheckIn.scheduleEmergencyNotifications(context.applicationContext, duration)
        return readyAt
    }

    /** Epoch millis when the emergency unlock started, or 0 when not active. */
    fun emergencyStartedAtMs(context: Context): Long =
        prefs(context).getLong(KEY_EMERGENCY_STARTED_AT_MS, 0L)

    /** Epoch millis when the emergency unlock finishes, or 0 when not active. */
    fun emergencyReadyAtMs(context: Context): Long =
        prefs(context).getLong(KEY_EMERGENCY_READY_AT_MS, 0L)

    internal fun emergencySentStages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EMERGENCY_SENT_STAGES, emptySet()).orEmpty()

    internal fun markEmergencyStageSent(context: Context, stageWire: String) {
        val p = prefs(context)
        val updated = p.getStringSet(KEY_EMERGENCY_SENT_STAGES, emptySet()).orEmpty() + stageWire
        p.edit { putStringSet(KEY_EMERGENCY_SENT_STAGES, updated) }
    }

    /**
     * Completes the emergency unlock once the timer has elapsed; false if still
     * counting down. Sends the final "unlocked" notification to the supervising
     * parents' phones.
     */
    fun completeEmergencyUnlock(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val readyAt = emergencyReadyAtMs(context)
        if (readyAt <= 0L || nowMs < readyAt) return false
        PhoneCheckIn.notifyEmergencyStageNow(context.applicationContext, "unlocked")
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
     * admin is active, not inside the parent unlock window, and not while a
     * screen the parent PIN opened is open ([passPackage]).
     */
    internal fun isGuarding(context: Context): Boolean =
        !isUnlockedNow(context) && passPackage(context) == null &&
            (StayConnected.isRequired(context) || isAdminActive(context))

    /**
     * Whether Android's list of enabled Accessibility services ("pkg/cls:...",
     * the class written in full or starting with ".") has ours.
     */
    internal fun listsService(enabled: String?, packageName: String, className: String): Boolean =
        enabled.orEmpty().split(':').any { entry ->
            val (pkg, cls) = entry.trim().split('/', limit = 2).takeIf { it.size == 2 } ?: return@any false
            pkg == packageName && (cls == className || packageName + cls == className)
        }

    /**
     * In Settings' own task, not on top of OurHome's: once the guard is on it
     * sends these pages Home, and left in OurHome's task they'd come back every
     * time OurHome is opened, and be sent Home again.
     */
    fun accessibilitySettings(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** In Settings' own task, for the same reason as [accessibilitySettings]. */
    fun appInfo(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * How to get past Android's "Restricted setting" refusal (an app installed
     * from a file, Android 13 or later). The ⋮ menu item only appears once
     * Android has refused, so the order matters.
     */
    const val RESTRICTED_HINT =
        "If Android says it's a restricted setting: tap OK, then open App info, tap ⋮ at the top and choose " +
            "Allow restricted settings, then go back and turn it on. The ⋮ menu only shows up after Android " +
            "has said no once."

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

    /** Whether a screen must be closed on a guarded phone: it's a [protectedScreen]. */
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
    ): Boolean = protectedScreen(
        windowPackage, ownPackage, appName, guardName, forceStopLabels,
        adminName, deactivateLabels, activateLabels, uninstallLabels, shows,
    ) != null

    /**
     * Which protected screen this is on a guarded phone, or null for any other:
     * - [Screen.AppInfo]: OurHome's App info (its name and a Force stop button on one screen)
     * - [Screen.Accessibility]: this service's own page in Accessibility ([guardName])
     * - [Screen.DeviceAdmin]: OurHome's Device admin deactivation page ([adminName],
     *   or [appName] with a Deactivate button)
     * - [Screen.Uninstall]: Android's package installer *uninstall* prompt for [appName]
     *   (never its update/install prompt, so self-hosted app updates still install cleanly)
     * Never OurHome itself, and never the "Activate device admin app" prompt
     * ([activateLabels]): the parent needs that to turn protection on, and it
     * shows the same [adminName] label as the deactivation page.
     * [shows]: whether the screen has a piece of text exactly so (ignoring case).
     */
    internal fun protectedScreen(
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
    ): Screen? {
        if (windowPackage == null || windowPackage == ownPackage) return null
        // The "Activate device admin app" prompt shows [adminName] too, but the
        // parent needs it to turn protection on, so leave it open.
        if (activateLabels.any(shows)) return null
        if (shows(guardName)) return Screen.Accessibility
        if (shows(adminName)) return Screen.DeviceAdmin
        if (!shows(appName)) return null
        if (windowPackage.contains("packageinstaller", ignoreCase = true)) {
            return if (uninstallLabels.any(shows)) Screen.Uninstall else null
        }
        return when {
            forceStopLabels.any(shows) -> Screen.AppInfo
            deactivateLabels.any(shows) -> Screen.DeviceAdmin
            else -> null
        }
    }

    /**
     * Whether a screen is Android's Private DNS dialog, to be closed while
     * this phone uses the web filter ([guarded]): a Settings screen showing
     * the dialog's "Private DNS provider hostname" choice ([providerLabels]),
     * which the Network & internet page it opens from doesn't show.
     */
    internal fun shouldClosePrivateDns(
        windowPackage: String?,
        guarded: Boolean,
        providerLabels: Set<String>,
        shows: (String) -> Boolean,
    ): Boolean = guarded && windowPackage != null && windowPackage.contains("settings", ignoreCase = true) &&
        providerLabels.any(shows)

    /** The most screen elements one look reads ([collectTexts]). */
    internal const val MAX_NODES = 1500

    /** How a piece of screen text is compared: trimmed, ignoring case. */
    internal fun normalize(text: CharSequence): String = text.toString().trim().lowercase(Locale.ROOT)

    /**
     * Every text and description on the screen under [root], [normalize]d.
     * Walked, because Android's own text search finds nothing in Settings'
     * newer pages (App info on Android 16 and later is built with Compose),
     * though the words are on screen (seen 2026-10-27).
     */
    internal fun textsOn(root: AccessibilityNodeInfo): Set<String> = collectTexts(
        root,
        children = { node -> (0 until node.childCount).mapNotNull { node.getChild(it) } },
        labels = { node -> listOf(node.text, node.contentDescription) },
    )

    /** The labels of [root] and everything under it, [normalize]d, reading at most [max] elements. */
    internal fun <T> collectTexts(
        root: T,
        children: (T) -> List<T>,
        labels: (T) -> List<CharSequence?>,
        max: Int = MAX_NODES,
    ): Set<String> {
        val out = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(root))
        var read = 0
        while (queue.isNotEmpty() && read < max) {
            val node = queue.removeFirst()
            read++
            labels(node).forEach { if (!it.isNullOrBlank()) out += normalize(it) }
            queue.addAll(children(node))
        }
        return out
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
    private var privateDnsLabels: Set<String> = emptySet()
    private var lastCheckAt = 0L
    private var lastToastAt = 0L
    private var lastCoveredApp: String? = null
    private var lastCoveredAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tornDown = false

    /**
     * The screen coming on, or the phone unlocked: daily limits look at the app
     * in front again. The screen going off: a screen the parent PIN opened is
     * protected again.
     */
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) CloseGuard.endPass(context) else AppLimiter.check(context)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        forceStopLabels = CloseGuard.forceStopLabels(this)
        deactivateLabels = CloseGuard.deactivateAdminLabels(this)
        activateLabels = CloseGuard.activateAdminLabels(this)
        privateDnsLabels = WebFilter.providerLabels(this)
        CloseGuard.service = this
        val wake = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { ContextCompat.registerReceiver(this, screenChanges, wake, ContextCompat.RECEIVER_NOT_EXPORTED) }
        // Same rules as any other start (an adult's Stop is still respected).
        StayConnected.ensureRunning(applicationContext)
        AppLimiter.check(this)
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
        // An app opening: covered at once when an adult blocked it (not paused by the parent PIN's unlock).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val block = AppBlocker.blockFor(this, event.packageName?.toString())
            if (block != null) {
                cover(block)
                return
            }
            AppLimiter.opened(this, event.packageName?.toString())
            // Left the screen the parent PIN opened: it's protected again.
            CloseGuard.passPackage(this)?.let { passed ->
                val window = event.packageName?.toString()
                if (CloseGuard.shouldEndPass(passed, window, packageName, CloseGuard.overPackages(this))) {
                    CloseGuard.endPass(this)
                }
            }
        }
        if (!CloseGuard.isGuarding(this)) return
        // Content changes come often: look at most twice a second.
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastCheckAt < CHECK_EVERY_MS) return
        lastCheckAt = now
        val root = rootInActiveWindow ?: return
        val windowPackage = root.packageName?.toString() ?: return
        val onScreen = CloseGuard.textsOn(root)
        val shows = { text: String -> CloseGuard.normalize(text) in onScreen }
        // Only on a Settings screen is it worth asking the network.
        val dnsGuarded = windowPackage.contains("settings", ignoreCase = true) && WebFilter.guarded(this)
        if (CloseGuard.shouldClosePrivateDns(windowPackage, dnsGuarded, privateDnsLabels, shows)) {
            Log.i(TAG, "protecting Private DNS while the web filter is on")
            protect(
                now,
                CloseGuard.Screen.PrivateDns,
                windowPackage,
                "An adult who looks after you chose this phone's web filter, so Private DNS can't be changed here.",
            )
            return
        }
        val screen = CloseGuard.protectedScreen(
            windowPackage = windowPackage,
            ownPackage = packageName,
            appName = getString(R.string.app_name),
            guardName = getString(R.string.close_guard_name),
            forceStopLabels = forceStopLabels,
            adminName = getString(R.string.close_guard_admin_name),
            deactivateLabels = deactivateLabels,
            activateLabels = activateLabels,
            shows = shows,
        ) ?: return
        Log.i(TAG, "protecting a settings or installer page that could stop or remove OurHome (${screen.wire})")
        protect(
            now,
            screen,
            windowPackage,
            "An adult who looks after you keeps Our Home on this phone. Use the parent PIN in Our Home to unlock it.",
        )
    }

    /**
     * A protected [screen] in [windowPackage]: the parent PIN screen over it
     * when the PIN can open it ([ParentPinActivity]); otherwise (the uninstall
     * prompt, or no parent PIN set) to the home screen, saying [why]. Asked
     * again on every look while it's in front: once the PIN screen is, the
     * app in front is Our Home, so it isn't.
     */
    private fun protect(now: Long, screen: CloseGuard.Screen, windowPackage: String, why: String) {
        if (screen.pinOpens && CloseGuard.hasPin(this)) {
            startActivity(ParentPinActivity.intent(this, screen, windowPackage))
        } else {
            goHome(now, why)
        }
    }

    /** To the home screen at once (the PIN screen left without the PIN). */
    internal fun goHomeNow() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** To the home screen, saying why (at most every few seconds). */
    private fun goHome(now: Long, why: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (now - lastToastAt > TOAST_EVERY_MS) {
            lastToastAt = now
            Toast.makeText(this, why, Toast.LENGTH_SHORT).show()
        }
    }

    /** The blocked screen over [block]'s app. */
    private fun cover(block: BlockedAppRule) = coverOnce(block.app) { AppBlocker.cover(this, block) }

    /**
     * The blocked screen over [app], whose daily limit ran out or whose schedule
     * is on (from [AppLimiter]'s thread); [moreTime]: it offers buying more time.
     */
    internal fun coverLimit(app: String, title: String, reason: String, moreTime: Boolean = false) {
        mainHandler.post { coverOnce(app) { startActivity(AppBlockedActivity.intent(this, title, reason, moreTime)) } }
    }

    /** An app's burst of window changes covers it once. */
    private fun coverOnce(app: String, show: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (app == lastCoveredApp && now - lastCoveredAt < COVER_EVERY_MS) return
        lastCoveredApp = app
        lastCoveredAt = now
        show()
    }

    /** The app whose window is active (the one being used; a keyboard's window never is), or null. */
    internal fun activeApp(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** The blocked list changed: covers a blocked app that's already open. */
    internal fun checkBlockedNow() {
        mainHandler.post {
            val open = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            AppBlocker.blockFor(this, open)?.let(::cover)
        }
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
        runCatching { unregisterReceiver(screenChanges) }
        disconnect()
        StayConnected.ensureRunning(applicationContext)
    }

    private companion object {
        const val TAG = "CloseGuard"
        const val CHECK_EVERY_MS = 500L
        const val TOAST_EVERY_MS = 5_000L
        const val COVER_EVERY_MS = 1_000L
    }
}

