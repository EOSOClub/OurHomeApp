package com.eosoclub.ourhome.location

import android.content.Context
import android.os.BatteryManager
import com.eosoclub.ourhome.data.BatteryLevel

/**
 * This phone's battery, sent with presence reports and live-check fixes and
 * once an hour ([Geofences.sync]) while its member shares their location, so
 * the household sees it next to where they are. Null if Android won't say.
 */
fun readBattery(context: Context): BatteryLevel? {
    val manager = context.getSystemService(BatteryManager::class.java) ?: return null
    val pct = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (pct !in 0..100) return null
    return BatteryLevel(pct, manager.isCharging)
}
