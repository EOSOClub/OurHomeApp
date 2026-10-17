package com.eosoclub.ourhome.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.location.LiveChecks
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Instant alerts via Firebase Cloud Messaging, and the same messages from
 * Stay connected's own connection to the server ([StayConnected]).
 *
 * The server's pushes carry no content, just "something changed" (see the web
 * repo's pushService). A push runs the same check as the hourly
 * [RequestReminders.Worker], right away, so every alert is still built here
 * from server data and goes through [lockScreenSafe]. Nothing Android shows on
 * its own: the messages are data-only.
 *
 * Every message carries an `id`; a phone that gets it both ways (Firebase and
 * Stay connected) acts on it once ([handle]).
 *
 * Optional: with no google-services.json in the build, Firebase never
 * initialises, [isAvailable] is false, and the hourly check carries on alone
 * (or Stay connected, when it's on).
 */
object Push {
    private const val TAG = "Push"
    private const val REGISTER_WORK = "push-register"
    private const val PREFS = "push"
    private const val KEY_REGISTERED_AT = "registered_at"
    private const val KEY_LAST_AT = "last_push_at"
    private const val KEY_LAST_VIA = "last_push_via"
    private const val TEST_CHANNEL_ID = "alert_test"
    private const val TEST_NOTIFICATION_ID = 1010

    /** The token is sent again this often, so the server can tell a live phone from a gone one. */
    const val REREGISTER_MS = 24 * 60 * 60_000L

    /** Which way a message arrived; the server's check-in field uses [wire]. */
    enum class Via(val wire: String, val label: String) {
        Firebase("firebase", "Firebase"),
        Connection("connection", "Stay connected"),
    }

    private val seen = RecentIds(64)

    fun isAvailable(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** Registers this install's token with the server (sign-in, launch, token rotation). */
    fun register(context: Context) {
        if (!isAvailable(context)) return
        val request = OneTimeWorkRequestBuilder<RegisterWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REGISTER_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** From the hourly check: registers again once a day, even when nothing changed. */
    fun registerIfStale(context: Context, now: Long = System.currentTimeMillis()) {
        val at = prefs(context).getLong(KEY_REGISTERED_AT, 0L)
        if (now - at >= REREGISTER_MS) register(context)
    }

    private val _received = MutableStateFlow(0L)

    /** Changes each time a message reaches this phone, so Profile can show it at once. */
    val received: StateFlow<Long> = _received.asStateFlow()

    /** When the last message reached this phone (epoch ms) and which way; null before the first. */
    fun lastReceived(context: Context): Pair<Long, Via>? {
        val prefs = prefs(context)
        val at = prefs.getLong(KEY_LAST_AT, 0L).takeIf { it > 0 } ?: return null
        val via = Via.entries.firstOrNull { it.wire == prefs.getString(KEY_LAST_VIA, null) } ?: Via.Firebase
        return at to via
    }

    /**
     * Acts on one message from the server. [highPriority]: Firebase delivered
     * it as high priority (Stay connected counts as high: the app is already
     * running and allowed to start things).
     */
    fun handle(context: Context, data: Map<String, String>, via: Via, highPriority: Boolean = true) {
        val type = data["type"] ?: return
        if (!seen.add(data["id"])) {
            Log.i(TAG, "$type already handled (arrived again by ${via.label})")
            return
        }
        val now = System.currentTimeMillis()
        prefs(context).edit {
            putLong(KEY_LAST_AT, now)
            putString(KEY_LAST_VIA, via.wire)
        }
        _received.value = now
        when (type) {
            "sync" -> {
                Log.i(TAG, "sync push (${data["reason"] ?: "unknown"}) by ${via.label}; checking now")
                RequestReminders.checkNow(context)
            }
            // A live location check on this member: Android lets only a
            // high-priority push start it from the background (LiveChecks).
            "live_check" -> {
                val id = data["checkId"] ?: return
                if (!highPriority) {
                    Log.w(TAG, "live check push arrived at normal priority; Android may refuse to start it")
                }
                LiveChecks.start(context, id)
            }
            "live_check_stop" -> LiveChecks.stop(context)
            // A supervising adult ringing this phone (Ring); same high-priority rule.
            "ring" -> Ring.start(context, data["ringerId"])
            // A supervising adult is looking at this member's app usage: send it now.
            "usage_refresh" -> UsageReport.sendNow(context)
            // "Send me a test notification" on Profile.
            "test" -> showTest(context, via)
            // The server lost this phone's connection and is asking whether it's still here.
            "ping" -> PhoneCheckIn.sendNow(context)
        }
    }

    private fun showTest(context: Context, via: Via) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TEST_CHANNEL_ID, "Test notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The test you send from Profile, to check instant alerts reach this phone"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Says nothing about the household, so the lock screen may show it as is.
        val notification = NotificationCompat.Builder(context, TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_request)
            .setContentTitle("Instant alerts work")
            .setContentText("This test reached your phone through ${via.label}.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    class RegisterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val session = (applicationContext as OurHomeApp).session
            return try {
                val token = FirebaseMessaging.getInstance().token.await()
                session.api.registerPushDevice(token, PhoneCheckIn.installId(applicationContext))
                session.rememberPushToken(token)
                prefs(applicationContext).edit { putLong(KEY_REGISTERED_AT, System.currentTimeMillis()) }
                Log.i(TAG, "registered for instant alerts")
                Result.success()
            } catch (e: UnauthorizedException) {
                Log.i(TAG, "not signed in; not registering")
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "registration failed; will retry", e)
                Result.retry()
            }
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val error = task.exception
            if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
        }
    }
}

/**
 * The last [capacity] message ids, oldest dropped first. [add] is false for
 * an id already there: the same message arriving the other way. Messages
 * without an id (an older server) are never treated as repeats.
 */
internal class RecentIds(private val capacity: Int) {
    private val ids = LinkedHashSet<String>()

    @Synchronized
    fun add(id: String?): Boolean {
        if (id.isNullOrEmpty()) return true
        if (!ids.add(id)) return false
        if (ids.size > capacity) ids.remove(ids.first())
        return true
    }
}

/** Receives FCM token changes and the server's pushes. */
class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.register(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Push.handle(
            applicationContext,
            message.data,
            Push.Via.Firebase,
            highPriority = message.priority == RemoteMessage.PRIORITY_HIGH,
        )
    }
}
