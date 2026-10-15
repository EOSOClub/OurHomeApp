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
 * and mobile signal), so a phone that goes quiet can be told apart: flat, out
 * of signal, or switched off ([sendBeforeShutdown]). Also sent at once when
 * the server asks (a "ping" push, after it lost the phone's connection) and
 * when the battery goes nearly flat ([StreamConnection]).
 */
object PhoneCheckIn {
    private const val TAG = "PhoneCheckIn"
    private const val PREFS = "phone_check_in"
    private const val KEY_INSTALL_ID = "install_id"
    private const val NOW_WORK = "phone-check-in-now"

    suspend fun send(context: Context, api: ApiClient, shuttingDown: Boolean = false) {
        val lastPush = Push.lastReceived(context)
        val result = api.checkIn(
            installId = installId(context),
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = Build.MODEL.orEmpty().take(80),
            locationAlways = Geofences.hasFineLocation(context) && Geofences.hasBackgroundLocation(context),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            usageAccess = UsageReport.hasAccess(context),
            lastPushAtMs = lastPush?.first,
            lastPushVia = lastPush?.second?.wire,
            batteryUnrestricted = BatteryAccess.isUnrestricted(context),
            standbyBucket = BatteryAccess.standbyBucket(context),
            stayConnected = StayConnected.isOn(context),
            closeGuard = CloseGuard.isEnabled(context),
            status = statusIfLookedAfter(context),
            shuttingDown = shuttingDown,
        )
        // Switching off: the next check-in (after it's back) sets this again.
        if (shuttingDown) return
        // An adult looks after this member: Stay connected is kept on.
        StayConnected.setRequired(context, result.stayConnectedRequired)
    }

    /** Checks in now (the server asked, or the battery is nearly flat), as soon as there's a network. */
    fun sendNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NowWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
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
}
