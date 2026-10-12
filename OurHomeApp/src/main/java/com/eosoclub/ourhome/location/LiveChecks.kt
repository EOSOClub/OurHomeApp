package com.eosoclub.ourhome.location

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.LiveCheck
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.data.intervalLabel
import com.eosoclub.ourhome.notifications.AppUpdateAlerts
import com.eosoclub.ourhome.notifications.RequestReminders
import com.eosoclub.ourhome.notifications.lockScreenSafe
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Live location checks on this phone's member (web liveCheckService). The
 * server's push starts [LiveCheckService], a location foreground service:
 * it shows a notification for the whole check ("Alex is checking where you
 * are until 3:15", with Stop) and sends a fix every interval until the check
 * ends, is stopped, or the server says no (409).
 *
 * Android lets a push start a location service from the background only when
 * the push is high priority and the app may use location "all the time", so
 * without that permission nothing starts and the person checking sees "their
 * phone hasn't answered". A check is never hidden: no notification, no fixes.
 */
object LiveChecks {
    private const val TAG = "LiveChecks"
    internal const val CHANNEL_ID = "live_checks"
    internal const val NOTIFICATION_ID = 1007
    internal const val ACTION_START = "com.eosoclub.ourhome.LIVE_CHECK_START"
    internal const val ACTION_STOP = "com.eosoclub.ourhome.LIVE_CHECK_STOP"
    internal const val EXTRA_CHECK_ID = "checkId"

    /** A push asked for a check: start the service if this phone may. */
    fun start(context: Context, checkId: String) {
        if (!Geofences.hasFineLocation(context) || !Geofences.hasBackgroundLocation(context)) {
            Log.w(TAG, "live check $checkId: location isn't allowed all the time; not starting")
            return
        }
        val intent = Intent(context, LiveCheckService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_CHECK_ID, checkId)
        try {
            context.startForegroundService(intent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // The push arrived at normal priority (Android lowered it): no way to start.
            Log.w(TAG, "live check $checkId: Android didn't allow starting it", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "live check $checkId: location permission missing", e)
        }
    }

    /** The person checking stopped it: take the notification down now. */
    fun stop(context: Context) {
        context.stopService(Intent(context, LiveCheckService::class.java))
    }

    internal fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live location checks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown the whole time someone in the household is checking where you are"
            },
        )
    }
}

class LiveCheckService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var checkId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(LiveChecks.EXTRA_CHECK_ID)
        when (intent?.action) {
            LiveChecks.ACTION_STOP -> {
                // From the notification's Stop (only after unlocking: see [notification]).
                // The notification goes at once; the service lives until the server knows.
                job?.cancel()
                job = null
                checkId = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                scope.launch {
                    if (id != null) stopOnServer(id)
                    stopSelf()
                }
            }
            LiveChecks.ACTION_START -> {
                if (id == null) {
                    finish()
                    return START_NOT_STICKY
                }
                // Android requires the notification within seconds of the start;
                // the name and end time follow once the server answers.
                if (!enterForeground(notification(id, null, canStop = true))) return START_NOT_STICKY
                if (id != checkId) {
                    job?.cancel()
                    checkId = id
                    job = scope.launch { run(id) }
                }
            }
            else -> finish()
        }
        // Not restarted by Android after being killed: a fresh push starts the next check.
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: android.app.Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            LiveChecks.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        // Permission taken away between the push and here, or Android refused.
        Log.w("LiveChecks", "couldn't show the live check notification; stopping", e)
        stopSelf()
        false
    }

    private suspend fun run(id: String) {
        val api = (applicationContext as OurHomeApp).session.api
        try {
            var check = api.liveCheck(id)
            val canStop = runCatching { api.locationConsent().canChange }.getOrDefault(true)
            showNotification(notification(id, check, canStop))
            val client = LocationServices.getFusedLocationProviderClient(this)
            while (!check.isOver && System.currentTimeMillis() < Instant.parse(check.endsAt).toEpochMilli()) {
                val location = currentLocation(client)
                if (location != null) {
                    try {
                        check = api.sendLiveCheckFix(
                            id,
                            location.latitude,
                            location.longitude,
                            location.accuracy.toDouble(),
                            location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                            readBattery(this),
                        )
                    } catch (e: ApiException) {
                        // 409: over, or live checks turned off; 404/403: gone or Location off.
                        if (e.status in setOf(403, 404, 409)) break
                        Log.w("LiveChecks", "fix not sent; trying again next time", e)
                    }
                }
                delay(check.intervalSec * 1000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            Log.i("LiveChecks", "signed out; live check ends")
        } catch (e: Exception) {
            Log.w("LiveChecks", "live check $id stopped", e)
        }
        finish()
    }

    // Checked in [LiveChecks.start]; Android also refuses the service without it.
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient,
    ): android.location.Location? {
        val cancel = CancellationTokenSource()
        return try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token).await()
        } catch (e: CancellationException) {
            cancel.cancel()
            throw e
        } catch (e: Exception) {
            Log.w("LiveChecks", "no location fix", e)
            null
        }
    }

    private suspend fun stopOnServer(id: String) {
        try {
            (applicationContext as OurHomeApp).session.api.stopLiveCheck(id)
        } catch (e: Exception) {
            Log.w("LiveChecks", "couldn't tell the server the check was stopped", e)
        }
    }

    private fun finish() {
        job?.cancel()
        job = null
        checkId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showNotification(notification: android.app.Notification) {
        if (!RequestReminders.canNotify(this)) return
        try {
            NotificationManagerCompat.from(this).notify(LiveChecks.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("LiveChecks", "notification permission revoked", e)
        }
    }

    /** [check] null: just started, before the server has said who and until when. */
    private fun notification(id: String, check: LiveCheck?, canStop: Boolean): android.app.Notification {
        LiveChecks.ensureChannel(this)
        val title = check?.let { "${it.requesterName} is checking where you are" } ?: "Someone is checking where you are"
        val text = check?.let {
            val until = Instant.parse(it.endsAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
            "Until $until. Your location is sent every ${intervalLabel(it.intervalSec)}."
        } ?: "Your location is being sent to them for a few minutes."
        // Tapping opens Profile, where the check and the log are.
        val open = PendingIntent.getActivity(
            this,
            7,
            Intent(this, MainActivity::class.java)
                .putExtra(RequestReminders.EXTRA_OPEN_TAB, AppUpdateAlerts.TAB_PROFILE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, LiveChecks.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_LOCATION_SHARING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            // Who is checking stays off the lock screen; that a check runs does not.
            .lockScreenSafe(this, LiveChecks.CHANNEL_ID, "Location check running")
        if (canStop) {
            val stop = PendingIntent.getService(
                this,
                8,
                Intent(this, LiveCheckService::class.java)
                    .setAction(LiveChecks.ACTION_STOP)
                    .putExtra(LiveChecks.EXTRA_CHECK_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_location, "Stop", stop)
                    // Changes data: never from a locked screen.
                    .setAuthenticationRequired(true)
                    .build(),
            )
        }
        return builder.build()
    }
}

private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}
