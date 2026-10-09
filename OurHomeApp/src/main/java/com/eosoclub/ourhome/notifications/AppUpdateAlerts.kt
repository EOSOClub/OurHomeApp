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
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.AppRelease
import com.eosoclub.ourhome.data.AppUpdate

/**
 * "Update ready" alerts when the server has built a newer version of this app.
 * Runs inside the request-reminder poll (and its push-triggered runs: the
 * server pushes when it announces a build), alerting once per version. Tapping
 * it opens Profile, where the update downloads and installs.
 */
object AppUpdateAlerts {
    private const val CHANNEL_ID = "app_updates"
    // Not 1003: that's DeadlineReminders, and sharing it made each replace the other.
    private const val NOTIFICATION_ID = 1004
    private const val PREFS = "app_update_alerts"
    private const val KEY_ALERTED = "alerted_version_code"
    private const val TAG = "AppUpdateAlerts"

    /** Intent extra value asking MainActivity to open Profile. */
    const val TAB_PROFILE = "profile"

    suspend fun check(context: Context, api: ApiClient) {
        val release = api.appRelease()
        if (release == null || !AppUpdate.isUpdate(context, release)) {
            // Installed (or nothing on offer): drop a stale alert.
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_ALERTED, 0) >= release.versionCode) return
        Log.i(TAG, "update ${release.versionName} available")
        show(context, release)
        prefs.edit { putLong(KEY_ALERTED, release.versionCode) }
    }

    private fun show(context: Context, release: AppRelease) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new version of Our Home is ready"
            },
        )
        val title = "Our Home update ready"
        val text = "Version ${release.versionName}. Tap to open Profile and install it; you stay signed in."
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java)
                .putExtra(RequestReminders.EXTRA_OPEN_TAB, TAB_PROFILE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .lockScreenSafe(context, CHANNEL_ID, title)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }
}
