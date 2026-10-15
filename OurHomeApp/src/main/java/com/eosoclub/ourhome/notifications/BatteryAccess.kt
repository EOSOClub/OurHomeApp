package com.eosoclub.ourhome.notifications

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Battery "Unrestricted" for OurHome: Android's battery optimization (Doze and
 * the app standby buckets) no longer holds the hourly check, pushes or Stay
 * connected back, and the app may start its ring and live-check services from
 * the background. Asked for on Profile; reported in the check-in, so the
 * adults who look after a child are told when it's still restricted.
 */
object BatteryAccess {
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Android's own "Let app always run in background?" question. Google Play
     * limits which apps may ask it; OurHome isn't on Play (built by each server).
     */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    /** Android's app standby bucket (10 active ... 45 restricted), for the check-in. */
    fun standbyBucket(context: Context): Int =
        context.getSystemService(UsageStatsManager::class.java).appStandbyBucket

    /**
     * Some phone makers stop background apps on top of Android's own rules,
     * each with their own settings. dontkillmyapp.com (a community site) has
     * the steps per maker; null for makers not known to do this.
     */
    fun makerHelpUrl(manufacturer: String = Build.MANUFACTURER): String? {
        val maker = manufacturer.trim().lowercase()
        return MAKERS_WITH_EXTRA_RULES.firstOrNull { maker == it || maker.startsWith("$it ") }
            ?.let { "https://dontkillmyapp.com/$it" }
    }

    private val MAKERS_WITH_EXTRA_RULES = listOf(
        "samsung", "xiaomi", "huawei", "oneplus", "oppo", "vivo", "realme", "meizu", "asus", "nokia", "sony",
        "lenovo", "wiko",
    )
}
