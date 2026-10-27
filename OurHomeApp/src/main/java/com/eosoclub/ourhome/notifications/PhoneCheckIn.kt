package com.eosoclub.ourhome.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eosoclub.ourhome.BuildConfig
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.DeviceStatus
import com.eosoclub.ourhome.data.UnauthorizedException
import com.eosoclub.ourhome.location.Geofences
import com.eosoclub.ourhome.location.readBattery
import java.util.UUID

/**
 * Tells the server this phone is still here (web phoneStatusService), from
 * the hourly check. For a supervised child, a phone that stops checking in,
 * or one that stops allowing location "all the time" while their sharing is
 * required, alerts the adults who look after them. An app can't report its
 * own uninstall, so silence is how that shows.
 *
 * Sends only: a random id for this install (not a hardware id), the app
 * version, the phone model, whether location, notifications and Usage access
 * are allowed, and how instant alerts reach it (when the last one arrived and
 * which way, battery Unrestricted, Android's standby bucket, Stay connected,
 * and whether "Keep Our Home running" is on in Accessibility).
 *
 * While an adult looks after this member, also its [status] (battery, network
 * and mobile signal), so they're told when the battery runs low, and a phone
 * that goes quiet can be told apart: flat, out of signal, or switched off
 * ([sendBeforeShutdown]). The server also keeps the Map's battery fresh from
 * it. Also sent every 15 minutes while app usage is shared ([UsageReport]),
 * at once when the server asks (a "ping" push, after it lost the phone's
 * connection), and when the battery runs low or nearly flat ([StreamConnection]).
 *
 * While looked after, it also sends the apps it never blocks and the daily
 * limits it used up today, and the answer brings the apps their supervisors
 * blocked ([AppBlocker]), their daily limits, schedules, extra minutes and
 * what a point buys ([AppLimiter]), and their web filter ([WebFilter]: it also
 * sends what Private DNS is set to); a change to any of them wakes the phone
 * to check in at once.
 *
 * Also what the parent PIN did here since a check-in last got through
 * ([CloseGuard.pendingPinEvents]), which checks in at once, so the adults who
 * look after this member are told.
 */
object PhoneCheckIn {
    private const val TAG = "PhoneCheckIn"
    private const val PREFS = "phone_check_in"
    private const val KEY_INSTALL_ID = "install_id"
    private const val NOW_WORK = "phone-check-in-now"
    private const val EMERGENCY_WORK_PREFIX = "phone-emergency-stage-"
    private const val KEY_STAGE = "stage"

    suspend fun send(
        context: Context,
        api: ApiClient,
        shuttingDown: Boolean = false,
        forcedEmergencyStage: String? = null,
    ) {
        val lastPush = Push.lastReceived(context)
        val startedAt = CloseGuard.emergencyStartedAtMs(context)
        val readyAt = CloseGuard.emergencyReadyAtMs(context).takeIf { it > 0L }
        val running = startedAt > 0L
        val due = if (running) {
            CloseGuard.dueEmergencyStages(
                startedAtMs = startedAt,
                nowMs = System.currentTimeMillis(),
                alreadySent = CloseGuard.emergencySentStages(context),
                duration = CloseGuard.emergencyDuration(context),
            )
        } else {
            emptyList()
        }
        val dueStage = CloseGuard.stageToSend(due, forcedEmergencyStage, running)

        val emergencyActive = readyAt != null || dueStage != null
        val lookedAfter = StayConnected.isRequired(context)
        val pinEvents = CloseGuard.pendingPinEvents(context)
        val result = api.checkIn(
            installId = installId(context),
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = Build.MODEL.orEmpty().take(80),
            locationAlways = Geofences.hasFineLocation(context) && Geofences.hasBackgroundLocation(context),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            usageAccess = UsageReport.hasAccess(context),
            privateDns = WebFilter.privateDns(context),
            lastPushAtMs = lastPush?.first,
            lastPushVia = lastPush?.second?.wire,
            batteryUnrestricted = BatteryAccess.isUnrestricted(context),
            standbyBucket = BatteryAccess.standbyBucket(context),
            stayConnected = StayConnected.isOn(context),
            closeGuard = if (emergencyActive) false else CloseGuard.isEnabled(context),
            uninstallGuard = if (emergencyActive) false else CloseGuard.isAdminActive(context),
            emergencyUnlockStage = dueStage,
            emergencyUnlockReadyAtMs = readyAt,
            status = if (lookedAfter) readStatus(context) else null,
            shuttingDown = shuttingDown,
            alwaysAllowed = if (lookedAfter) AppBlocker.alwaysAllowed(context) else null,
            limitsReached = if (lookedAfter) AppLimiter.reachedToday(context) else null,
            pinEvents = pinEvents,
        )
        due.forEach { CloseGuard.markEmergencyStageSent(context, it.wire) }
        CloseGuard.pinEventsSent(context, pinEvents.map { it.id })
        // Switching off: the next check-in (after it's back) sets this again.
        if (shuttingDown) return
        // An adult looks after this member: Stay connected is kept on.
        StayConnected.setRequired(context, result.stayConnectedRequired)
        // And the apps they blocked, their limits and schedules (none once nobody looks after them).
        AppBlocker.save(context, if (result.stayConnectedRequired) result.blockedApps else emptyList())
        AppLimiter.save(context, if (result.stayConnectedRequired) result.appLimits else emptyList())
        AppLimiter.saveSchedules(context, if (result.stayConnectedRequired) result.schedules else emptyList())
        AppLimiter.saveExtra(
            context,
            if (result.stayConnectedRequired) result.extraTime else emptyList(),
            if (result.stayConnectedRequired) result.pointsForTime else null,
        )
        // And the web filter they chose, whose Private DNS setting the guard keeps them out of.
        WebFilter.save(context, if (result.stayConnectedRequired) result.webFilter else null)
    }

    /** Schedules the parent notifications for the chosen [duration] (1h, 5h, or 10h). */
    fun scheduleEmergencyNotifications(context: Context, duration: CloseGuard.EmergencyDuration) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        for (stage in duration.stages) {
            val builder = OneTimeWorkRequestBuilder<EmergencyStageWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(androidx.work.workDataOf(KEY_STAGE to stage.wire))
            if (stage.offsetMs > 0L) {
                builder.setInitialDelay(stage.offsetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            wm.enqueueUniqueWork(EMERGENCY_WORK_PREFIX + stage.wire, ExistingWorkPolicy.REPLACE, builder.build())
        }
    }

    /** Sends a specific emergency unlock milestone notification (e.g. "unlocked") right away. */
    fun notifyEmergencyStageNow(context: Context, stageWire: String) {
        val request = OneTimeWorkRequestBuilder<EmergencyStageWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInputData(androidx.work.workDataOf(KEY_STAGE to stageWire))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            EMERGENCY_WORK_PREFIX + stageWire,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Checks in now (the server asked, or the battery changed step), as soon
     * as there's a network. Replaces one already waiting or running, so the
     * latest ask always runs and reads the phone afresh (keeping the first
     * dropped a plug-in that came a moment after a low battery).
     */
    fun sendNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NowWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Android is switching the phone off: one last check-in saying so, while
     * an adult looks after this member. Android gives only a few seconds, so
     * this may not get through; then the phone is just "quiet".
     */
    suspend fun sendBeforeShutdown(context: Context) {
        if (!StayConnected.isRequired(context)) return
        val api = (context.applicationContext as OurHomeApp).session.api
        try {
            send(context, api, shuttingDown = true)
            Log.i(TAG, "told the server the phone is switching off")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't say the phone is switching off", e)
        }
    }

    /** The phone's [DeviceStatus], only while an adult looks after this member. */
    fun statusIfLookedAfter(context: Context): DeviceStatus? =
        if (StayConnected.isRequired(context)) readStatus(context) else null

    private fun readStatus(context: Context): DeviceStatus {
        val battery = readBattery(context)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { connectivity?.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
        val network = DeviceStatus.networkName(
            connected = caps != null,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
        )
        // No permission needed; throws on a phone without a mobile radio.
        val signal = runCatching { context.getSystemService(TelephonyManager::class.java)?.signalStrength?.level }
            .getOrNull()
        return DeviceStatus(battery?.pct, battery?.charging, network, signal)
    }

    /** Made once per install; a reinstall gets a new one. Also names this phone's Stay connected stream. */
    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALL_ID, fresh) }
        return fresh
    }

    class NowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val api = (applicationContext as OurHomeApp).session.api
            return try {
                send(applicationContext, api)
                Result.success()
            } catch (e: UnauthorizedException) {
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "check-in failed; the hourly check tries again", e)
                Result.success()
            }
        }
    }

    class EmergencyStageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val wire = inputData.getString(KEY_STAGE)?.takeIf { it.isNotEmpty() } ?: return Result.success()
            // Only send if the emergency unlock is still active (or this is the final "unlocked" notice).
            if (wire != "unlocked" && CloseGuard.emergencyReadyAtMs(applicationContext) <= 0L) {
                return Result.success()
            }
            val api = (applicationContext as OurHomeApp).session.api
            return try {
                send(applicationContext, api, forcedEmergencyStage = wire)
                Log.i(TAG, "sent emergency unlock stage notification ($wire) to parents")
                Result.success()
            } catch (e: UnauthorizedException) {
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "emergency stage notification ($wire) failed; retrying", e)
                Result.retry()
            }
        }
    }
}
