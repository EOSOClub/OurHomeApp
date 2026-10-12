package com.eosoclub.ourhome.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.ApiException
import com.eosoclub.ourhome.data.Features
import com.eosoclub.ourhome.data.HomeReminder
import com.eosoclub.ourhome.data.PresenceReport
import com.eosoclub.ourhome.data.StoreReminder
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.data.geofenceSignature
import com.eosoclub.ourhome.notifications.HouseholdReminderAlerts
import com.eosoclub.ourhome.notifications.RequestReminders
import com.eosoclub.ourhome.notifications.lockScreenSafe
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Presence: the phone watches the household's map places with geofences
 * (Google Play services) and tells the server when it arrives at or leaves
 * one. Only while the member shares their location (Profile → Location) and
 * has allowed precise location "all the time"; otherwise nothing is watched.
 *
 * - "Arrived" is Android's dwell: inside for [LOITER_MS], so driving past a
 *   store or GPS wobble at the edge doesn't count. "Left" counts at once.
 * - Geofences are registered again only when the places change
 *   ([geofenceSignature]), after a reboot or app update (Android drops them),
 *   or when Android says it lost them. Registering makes Android report the
 *   place the phone is in, which the server ignores if nothing changed.
 * - Reports go through WorkManager, so one made offline is sent later; the
 *   server drops reports older than what it has, and too late for a reminder.
 * - A store with lists to buy answers with a reminder, posted here through
 *   [lockScreenSafe] like every other alert.
 */
object Geofences {
    private const val TAG = "Geofences"
    private const val PREFS = "geofences"
    private const val KEY_SIGNATURE = "signature"
    private const val SYNC_WORK = "geofence-sync"
    private const val KEY_FORCE = "force"
    private const val CHANNEL_ID = "store_reminders"
    private const val NOTIFICATION_ID = 1006
    private const val HOME_CHANNEL_ID = "home_reminders"
    private const val HOME_NOTIFICATION_ID = 1008
    private const val LOITER_MS = 2 * 60_000

    /** Intent extra value asking MainActivity for the Shopping tab. */
    const val TAB_SHOPPING = "shopping"

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Brings the geofences in line with the server, in the background. [force]
     * registers again even if the places look unchanged (reboot, app update,
     * a permission just granted).
     */
    fun syncNow(context: Context, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInputData(workDataOf(KEY_FORCE to force))
            .build()
        // A forced sync must not be swallowed by a plain one already queued.
        val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC_WORK, policy, request)
    }

    /**
     * The check itself, also run by the hourly worker. Watches nothing unless
     * the Location feature is on, the member shares, and both permissions are
     * granted. Returns how many places are watched.
     */
    suspend fun sync(context: Context, api: ApiClient, force: Boolean = false, known: Features? = null): Int {
        val features = known ?: Features.from(runCatching { api.myAccess() }.getOrNull()?.features)
        if (!features.location || !api.locationConsent().isSharing ||
            !hasFineLocation(context) || !hasBackgroundLocation(context)
        ) {
            removeAll(context)
            return 0
        }
        val places = api.mapPlaces()
        val signature = geofenceSignature(places)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getString(KEY_SIGNATURE, null) == signature) return places.size
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(pendingIntent(context)).awaitOrNull()
        if (places.isNotEmpty()) {
            register(context, places.map { p ->
                Geofence.Builder()
                    .setRequestId(p.id)
                    .setCircularRegion(p.lat, p.lng, p.radiusM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setLoiteringDelay(LOITER_MS)
                    .build()
            })
        }
        prefs.edit { putString(KEY_SIGNATURE, signature) }
        Log.i(TAG, "watching ${places.size} map place(s)")
        return places.size
    }

    // Both location permissions are checked in [sync] just before this.
    @SuppressLint("MissingPermission")
    private suspend fun register(context: Context, fences: List<Geofence>) {
        val request = GeofencingRequest.Builder()
            // Report the place the phone is already in, once it has been there LOITER_MS.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofences(fences)
            .build()
        LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent(context)).await()
    }

    /** Stops watching (sharing off, signed out, permission gone). */
    fun removeAll(context: Context) {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
        forgetRegistration(context)
    }

    /** The next sync registers again, whatever the places look like. */
    fun forgetRegistration(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_SIGNATURE) }
    }

    /** Signed out: nothing watched, no reminder left showing. */
    fun clear(context: Context) {
        removeAll(context)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        NotificationManagerCompat.from(context).cancel(HOME_NOTIFICATION_ID)
    }

    // Mutable: Play services fills in which geofence fired and how.
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun showReminder(context: Context, reminder: StoreReminder) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Store reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shopping lists with something to buy when you get to a store"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            6,
            Intent(context, MainActivity::class.java)
                .putExtra(RequestReminders.EXTRA_OPEN_TAB, TAB_SHOPPING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shopping)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Where they are and what's on the list stay off the lock screen.
            .lockScreenSafe(context, CHANNEL_ID, "Shopping reminder")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }

    /**
     * "You're home: 3 chores due". Its own channel, so anyone who doesn't want
     * it can turn just this off in Android's settings. Opens Tasks.
     */
    fun showHomeReminder(context: Context, reminder: HomeReminder) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(HOME_CHANNEL_ID, "Home reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your chores due today, when you get home"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            9,
            Intent(context, MainActivity::class.java)
                .putExtra(RequestReminders.EXTRA_OPEN_TAB, HouseholdReminderAlerts.TAB_TASKS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, HOME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // The chores stay off the lock screen.
            .lockScreenSafe(context, HOME_CHANNEL_ID, "Chores reminder")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(HOME_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }

    /** Queues one report; WorkManager sends it once there's a network. */
    internal fun enqueueReport(context: Context, report: PresenceReport) {
        val request = OneTimeWorkRequestBuilder<ReportWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    "placeId" to report.placeId,
                    "transition" to report.transition,
                    "at" to report.atMillis,
                    "lat" to report.lat,
                    "lng" to report.lng,
                    "accuracy" to report.accuracyM,
                ),
            )
            .build()
        // Kept in order per place, so a quick leave-and-return arrives as it happened.
        WorkManager.getInstance(context)
            .enqueueUniqueWork("presence-${report.placeId}", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = (applicationContext as OurHomeApp).session.api
            return try {
                if (api.getSession() == null) {
                    removeAll(applicationContext)
                    return Result.success()
                }
                sync(applicationContext, api, inputData.getBoolean(KEY_FORCE, false))
                Result.success()
            } catch (e: UnauthorizedException) {
                removeAll(applicationContext)
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "sync failed; will retry", e)
                Result.retry()
            }
        }
    }

    class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = (applicationContext as OurHomeApp).session.api
            val report = PresenceReport(
                placeId = inputData.getString("placeId") ?: return Result.success(),
                transition = inputData.getString("transition") ?: return Result.success(),
                atMillis = inputData.getLong("at", System.currentTimeMillis()),
                lat = inputData.keyValueMap["lat"] as? Double,
                lng = inputData.keyValueMap["lng"] as? Double,
                accuracyM = inputData.keyValueMap["accuracy"] as? Double,
            )
            return try {
                val result = api.reportPresence(report)
                Log.i(TAG, "reported ${report.transition} ${report.placeId}")
                result.reminder?.let { showReminder(applicationContext, it) }
                result.home?.let { showHomeReminder(applicationContext, it) }
                Result.success()
            } catch (e: UnauthorizedException) {
                removeAll(applicationContext)
                Result.success()
            } catch (e: ApiException) {
                when (e.status) {
                    // Sharing turned off (here or on the website), or the
                    // Location feature turned off: stop watching.
                    409, 403 -> removeAll(applicationContext)
                    // The place was deleted: pick up the current ones.
                    404 -> syncNow(applicationContext, force = true)
                    // Bad input won't get better by retrying.
                    422 -> Log.w(TAG, "report refused", e)
                    else -> return retryOrDrop(e)
                }
                Result.success()
            } catch (e: Exception) {
                retryOrDrop(e)
            }
        }

        // A day of retries is plenty: by then it's no longer news.
        private fun retryOrDrop(e: Exception): Result =
            if (runAttemptCount < 12) {
                Log.w(TAG, "report failed; will retry", e)
                Result.retry()
            } else {
                Log.w(TAG, "report failed too often; dropped", e)
                Result.success()
            }
    }
}

/** Android's geofence events: arrived (dwelled) at or left a map place. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("Geofences", "geofence error ${event.errorCode}")
            // Location was turned off and Android dropped the geofences: the
            // next sync (hourly, or at launch) registers them again.
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                Geofences.forgetRegistration(context)
            }
            return
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_DWELL -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            else -> return
        }
        val location = event.triggeringLocation
        val at = location?.time?.takeIf { it > 0 } ?: System.currentTimeMillis()
        event.triggeringGeofences.orEmpty().forEach { fence ->
            Geofences.enqueueReport(
                context,
                PresenceReport(
                    placeId = fence.requestId,
                    transition = transition,
                    atMillis = at,
                    lat = location?.latitude,
                    lng = location?.longitude,
                    accuracyM = location?.accuracy?.toDouble(),
                ),
            )
        }
    }
}

/** Android drops geofences on reboot and on app updates: register them again. */
class GeofenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Geofences.syncNow(context, force = true)
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}

/** For cleanup calls whose failure doesn't matter (nothing was registered). */
private suspend fun <T> Task<T>.awaitOrNull(): T? = runCatching { await() }.getOrNull()
