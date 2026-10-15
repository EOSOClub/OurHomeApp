package com.eosoclub.ourhome.notifications

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.eosoclub.ourhome.BuildConfig
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.location.Geofences
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
 */
object PhoneCheckIn {
    private const val PREFS = "phone_check_in"
    private const val KEY_INSTALL_ID = "install_id"

    suspend fun send(context: Context, api: ApiClient) {
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
        )
        // An adult looks after this member: Stay connected is kept on.
        StayConnected.setRequired(context, result.stayConnectedRequired)
    }

    /** Made once per install; a reinstall gets a new one. Also names this phone's Stay connected stream. */
    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALL_ID, fresh) }
        return fresh
    }
}
