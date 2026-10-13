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
 * version, the phone model, and whether location, notifications and Usage
 * access are allowed.
 */
object PhoneCheckIn {
    private const val PREFS = "phone_check_in"
    private const val KEY_INSTALL_ID = "install_id"

    suspend fun send(context: Context, api: ApiClient) {
        api.checkIn(
            installId = installId(context),
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = Build.MODEL.orEmpty().take(80),
            locationAlways = Geofences.hasFineLocation(context) && Geofences.hasBackgroundLocation(context),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            usageAccess = UsageReport.hasAccess(context),
        )
    }

    /** Made once per install; a reinstall gets a new one. */
    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALL_ID, fresh) }
        return fresh
    }
}
