package com.eosoclub.ourhome.notifications

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.DeviceStatus
import com.eosoclub.ourhome.location.batteryFrom
import com.eosoclub.ourhome.data.SseParser
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.data.parseStreamMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Stay connected: this phone keeps one open connection to the household's
 * server (web GET /api/phone/stream) and gets every instant alert down it the
 * moment it's sent, with or without Firebase. Runs as a foreground service,
 * so Android lists OurHome under its active apps (with a Stop button) and
 * rarely kills it; its notification is silent, and can be swiped away.
 *
 * Turned on per phone on Profile, or kept on (required) while an adult looks
 * after this member: the check-in's answer ([setRequired]). Started on launch,
 * by the hourly check and after a reboot or update while it's on; Android
 * allows that from the background only while battery is Unrestricted
 * ([BatteryAccess]).
 *
 * Android's Stop button (or Force stop) kills the app, and the app can't stop
 * anyone pressing it. A required phone starts again within about 15 minutes
 * ([WatchdogWorker]), and its supervisors see it isn't connected meanwhile.
 * While "Keep Our Home running" is on in Accessibility ([CloseGuard]), that
 * service holds the connection instead: no foreground service, so no Stop
 * button, and on a required phone OurHome's App info can't be opened.
 * Anyone else's Stop is respected: nothing starts it again in the background
 * until they open OurHome ([ensureRunning]'s `byUser`).
 *
 * Signing out stops it and forgets "required" until the next check-in.
 */
object StayConnected {
    private const val TAG = "StayConnected"
    private const val PREFS = "stay_connected"
    private const val KEY_ON = "on"
    private const val KEY_REQUIRED = "required"
    private const val KEY_STOPPED_BY_USER = "stopped_by_user"
    private const val KEY_EXITS_SEEN_AT = "exits_seen_at"
    private const val WATCHDOG_WORK = "stay-connected-watchdog"
    internal const val CHANNEL_ID = "stay_connected"
    internal const val NOTIFICATION_ID = 1011

    /** The server sends a keep-alive every 45 s; this long without anything means the connection is gone. */
    internal const val IDLE_SEC = 100L

    private val _connected = MutableStateFlow(false)

    /** Connected to the server right now (for Profile). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun setConnected(value: Boolean) {
        _connected.value = value
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** On, by the member's own switch or because it's required. */
    fun isOn(context: Context): Boolean = isRequired(context) || isChosen(context)

    /** The member's own Profile switch. */
    fun isChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** An adult looks after this member: kept on, and started again after Stop. */
    fun isRequired(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUIRED, false)

    /** The Profile switch (locked on while required): on starts it now, off stops it. */
    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ON, on) }
        if (on) ensureRunning(context, byUser = true) else if (!isRequired(context)) stop(context)
    }

    /** From each check-in: whether an adult looks after this member now. */
    fun setRequired(context: Context, required: Boolean) {
        val was = isRequired(context)
        prefs(context).edit { putBoolean(KEY_REQUIRED, required) }
        if (required) {
            scheduleWatchdog(context)
            ensureRunning(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK)
            if (was && !isChosen(context)) stop(context)
        }
    }

    /** Signed out: stop, release any Device admin lock, unblock every app, and forget "required" (the next account may be an adult). */
    fun signedOut(context: Context) {
        prefs(context).edit { remove(KEY_REQUIRED) }
        CloseGuard.signedOut(context)
        AppBlocker.signedOut(context)
        WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK)
        stop(context)
    }

    /** The service is running in this process (set by the service itself). */
    @Volatile
    internal var running = false

    /**
     * The service's notification couldn't be updated because notifications
     * weren't allowed yet (it starts at sign-in, before the member is asked).
     * Once they're allowed, Android shows the notification as it was first
     * posted, "Reconnecting", even though it connected since.
     */
    @Volatile
    internal var notificationStale = false

    /**
     * OurHome came to the front (after Android's permission prompt or its
     * settings, for instance): a stale notification is posted again with
     * whether it's connected now. Restarting a running service only re-posts
     * its notification; the connection carries on.
     */
    fun refreshIfStale(context: Context) {
        if (!running || !notificationStale || !RequestReminders.canNotify(context)) return
        notificationStale = false
        try {
            context.startService(Intent(context, StayConnectedService::class.java))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "couldn't refresh the notification", e)
        }
    }

    /**
     * Starts the service when it's on. A running one is left alone: starting
     * it again would put back a notification the member swiped away.
     * [byUser]: the member opened OurHome or turned the switch on, which ends
     * a Stop they pressed. Otherwise (hourly check, boot) a member who isn't
     * required and pressed Stop is left stopped.
     */
    fun ensureRunning(context: Context, byUser: Boolean = false) {
        if (!isOn(context)) return
        if (byUser) {
            clearStoppedByUser(context)
        } else if (!isRequired(context) && stoppedByUser(context)) {
            Log.i(TAG, "stopped with Android's Stop; waiting until OurHome is opened")
            return
        }
        // The close guard holds the connection while it's on: no foreground
        // service, so Android shows no Stop button for OurHome.
        CloseGuard.service?.let {
            it.connectIfOn()
            return
        }
        if (running) return
        try {
            context.startForegroundService(Intent(context, StayConnectedService::class.java))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // From the background without Unrestricted battery: the next launch starts it.
            Log.w(TAG, "Android didn't allow starting from the background", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "couldn't start", e)
        }
    }

    /** Turned off or no longer required. The member's own switch stays as it is. */
    fun stop(context: Context) {
        context.stopService(Intent(context, StayConnectedService::class.java))
        CloseGuard.service?.disconnect()
        _connected.value = false
    }

    /** The close guard took the connection over: the foreground service isn't needed. */
    internal fun stopService(context: Context) {
        if (running) context.stopService(Intent(context, StayConnectedService::class.java))
    }

    /**
     * Whether Android's Stop (or Force stop) ended the app since the member
     * last opened it. The app gets no call when that happens, so this reads
     * Android's record of how the app's past runs ended, each one once.
     */
    private fun stoppedByUser(context: Context): Boolean {
        val prefs = prefs(context)
        val seenAt = prefs.getLong(KEY_EXITS_SEEN_AT, 0L)
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 10)
        }.getOrDefault(emptyList()).filter { it.timestamp > seenAt }
        if (exits.isNotEmpty()) {
            prefs.edit {
                putLong(KEY_EXITS_SEEN_AT, exits.maxOf { it.timestamp })
                if (exits.any { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED }) {
                    putBoolean(KEY_STOPPED_BY_USER, true)
                }
            }
        }
        return prefs.getBoolean(KEY_STOPPED_BY_USER, false)
    }

    /** The member opened OurHome (or it was updated): earlier exits no longer count. */
    private fun clearStoppedByUser(context: Context) {
        prefs(context).edit {
            remove(KEY_STOPPED_BY_USER)
            putLong(KEY_EXITS_SEEN_AT, System.currentTimeMillis())
        }
    }

    private fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WATCHDOG_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * While required: starts the service again within about 15 minutes of
     * Android's Stop (Android keeps running an app's jobs after Stop).
     */
    class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (isRequired(applicationContext)) ensureRunning(applicationContext)
            return Result.success()
        }
    }

    internal fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Stay connected", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while this phone keeps its connection to the household's server"
                setShowBadge(false)
            },
        )
    }
}

/**
 * The open connection itself: reads the server's stream, hands each message
 * to [Push.handle], and reconnects with backoff (sooner when a network comes
 * back). Held by whichever runs Stay connected: [StayConnectedService], or
 * the close guard ([CloseGuardService]) while that's on. [onConnected] is
 * called with each change; [onSignedOut] when the server says the session is
 * gone, after which the loop has ended.
 */
internal class StreamConnection(
    private val context: Context,
    private val onConnected: (Boolean) -> Unit,
    private val onSignedOut: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Volatile
    private var cancelCall: (() -> Unit)? = null

    /** Woken when a network comes back, so a wait for the next try ends early. */
    private val networkBack = Channel<Unit>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var phoneEvents: BroadcastReceiver? = null

    /** The last [DeviceStatus.lowStep] told to the server. */
    @Volatile
    private var lowStepSent = 0

    fun start() {
        if (loop != null) return
        watchNetwork()
        watchPhone()
        loop = scope.launch { run() }
    }

    fun stop() {
        loop?.cancel()
        cancelCall?.invoke()
        networkCallback?.let { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        networkCallback = null
        phoneEvents?.let { runCatching { context.unregisterReceiver(it) } }
        phoneEvents = null
        scope.cancel()
    }

    /**
     * While connected, OurHome is running, so it can hear what Android tells
     * running apps: the phone switching off (its last word to the server), and
     * the battery running low, then nearly flat, and being plugged in again (a
     * check-in at once each time). The adults who look after this member are
     * told it's low, and if it goes quiet, why, not just that it did
     * (PhoneCheckIn).
     */
    private fun watchPhone() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SHUTDOWN -> {
                        val pending = goAsync()
                        scope.launch {
                            try {
                                withTimeoutOrNull(SHUTDOWN_WAIT_MS) { PhoneCheckIn.sendBeforeShutdown(context) }
                            } finally {
                                pending.finish()
                            }
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val (pct, charging) = batteryFrom(intent) ?: return
                        val step = DeviceStatus.lowStep(pct, charging)
                        if (step != lowStepSent && StayConnected.isRequired(context)) {
                            // Lower: the adults are told. Back up (plugged in): their alert goes.
                            lowStepSent = step
                            Log.i(TAG, "battery at $pct%${if (charging) ", charging" else ""}; checking in")
                            PhoneCheckIn.sendNow(context)
                        } else if (step < lowStepSent) {
                            lowStepSent = step
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // System broadcasts only; nothing from other apps.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { phoneEvents = receiver }
            .onFailure { Log.w(TAG, "can't hear shutdown or battery; the server only sees the phone go quiet", it) }
    }

    private suspend fun run() {
        val api = (context.applicationContext as OurHomeApp).session.api
        var backoffMs = FIRST_BACKOFF_MS
        while (scope.isActive) {
            val parser = SseParser { event, data ->
                when (event) {
                    "hello" -> {
                        backoffMs = FIRST_BACKOFF_MS
                        onConnected(true)
                    }
                    null, "message" -> parseStreamMessage(data)?.let { Push.handle(context.applicationContext, it, Push.Via.Connection) }
                }
            }
            try {
                api.stream(
                    PhoneCheckIn.installId(context),
                    StayConnected.IDLE_SEC,
                    status = PhoneCheckIn.statusIfLookedAfter(context),
                    onOpen = { cancelCall = it },
                    onLine = parser::line,
                )
                // The server ends each stream after a while: straight back on,
                // still shown as connected unless that fails.
                Log.i(TAG, "stream ended by the server; reconnecting")
                delay(RECONNECT_MS)
                continue
            } catch (e: UnauthorizedException) {
                Log.i(TAG, "signed out; stopping")
                onConnected(false)
                onSignedOut()
                return
            } catch (e: Exception) {
                if (!scope.isActive) return
                Log.w(TAG, "connection lost; trying again in ${backoffMs / 1000} s", e)
            } finally {
                cancelCall = null
            }
            onConnected(false)
            withTimeoutOrNull(backoffMs) { networkBack.receive() }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkBack.trySend(Unit)
            }
        }
        runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "can't watch the network; retrying on a timer only", it) }
    }

    private companion object {
        const val TAG = "StayConnected"
        const val FIRST_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val RECONNECT_MS = 500L

        /** Android waits only a few seconds for apps at shutdown. */
        const val SHUTDOWN_WAIT_MS = 4_000L
    }
}

class StayConnectedService : Service() {
    private var connection: StreamConnection? = null

    /** When its notification was last posted (elapsedRealtime; see [show]). */
    private var postedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StayConnected.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must go foreground again, even when already running.
        if (!RequestReminders.canNotify(this)) StayConnected.notificationStale = true
        if (!enterForeground(notification(StayConnected.connected.value))) return START_NOT_STICKY
        // Off, or the close guard holds the connection (no Stop button that way).
        if (!StayConnected.isOn(this) || CloseGuard.service != null) {
            finish()
            return START_NOT_STICKY
        }
        if (connection == null) {
            connection = StreamConnection(applicationContext, ::setConnected, ::finish).also { it.start() }
        }
        // Brought back by Android if it has to kill it for memory.
        return START_STICKY
    }

    private fun setConnected(value: Boolean) {
        if (StayConnected.connected.value == value) return
        StayConnected.setConnected(value)
        show(notification(value))
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        // The "special use" type came with Android 14; before it no type is needed.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, StayConnected.NOTIFICATION_ID, notification, type)
        postedAt = SystemClock.elapsedRealtime()
        true
    } catch (e: Exception) {
        Log.w(TAG, "couldn't go foreground; not connecting", e)
        stopSelf()
        false
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connection?.stop()
        // Handed over to the close guard: its connection says whether it's connected.
        if (CloseGuard.service == null) StayConnected.setConnected(false)
        StayConnected.running = false
        super.onDestroy()
    }

    /**
     * Updates the notification, unless the member swiped it away: then it stays away.
     * Missing only counts as swiped a few seconds after it was posted: Android may
     * not list it straight away, and the connection can come up before that (it did
     * on a fast network, leaving "Reconnecting" up while connected).
     */
    private fun show(notification: Notification) {
        if (!RequestReminders.canNotify(this)) {
            StayConnected.notificationStale = true
            return
        }
        val showing = getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == StayConnected.NOTIFICATION_ID }
        if (!showing && SystemClock.elapsedRealtime() - postedAt > SWIPE_GRACE_MS) return
        postedAt = SystemClock.elapsedRealtime()
        try {
            NotificationManagerCompat.from(this).notify(StayConnected.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }

    /** Says nothing about the household, so the lock screen may show it as is. */
    private fun notification(connected: Boolean): Notification {
        StayConnected.ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, StayConnected.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_request)
            .setContentTitle(if (connected) "Staying connected" else "Reconnecting")
            .setContentText(
                if (connected) {
                    "Alerts arrive the moment they happen. Turn this off on your Profile."
                } else {
                    "Not connected to the server right now. Trying again soon."
                },
            )
            .setContentIntent(open)
            // Not ongoing: since Android 13 it can be swiped away and the
            // service carries on (it stays in Android's active apps list).
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val TAG = "StayConnected"

        /** Longer than Android takes to list a new notification, shorter than anyone swipes. */
        const val SWIPE_GRACE_MS = 3_000L
    }
}
