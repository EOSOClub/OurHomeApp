package com.eosoclub.ourhome.location

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.eosoclub.ourhome.data.BatteryLevel

/**
 * This phone's battery, sent with presence reports and live-check fixes and
 * once an hour ([Geofences.sync]) while its member shares their location, so
 * the household sees it next to where they are. Null if Android won't say.
 *
 * Read from Android's latest battery broadcast, the same reading Stay
 * connected reacts to ([batteryFrom]), so a check-in it starts says what it
 * saw: BatteryManager's own numbers can lag a broadcast by a moment, and its
 * isCharging stays false while plugged in on a slow charger or at a charge limit.
 */
fun readBattery(context: Context): BatteryLevel? {
    // A null receiver only reads the last (sticky) broadcast; nothing is registered.
    val sticky = runCatching {
        ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }.getOrNull()
    sticky?.let(::batteryFrom)?.let { return it }
    val manager = context.getSystemService(BatteryManager::class.java) ?: return null
    val pct = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (pct !in 0..100) return null
    return BatteryLevel(pct, manager.isCharging)
}

/** A battery broadcast's level, and whether the phone is plugged in (counted as charging); null if it doesn't say. */
fun batteryFrom(intent: Intent): BatteryLevel? {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    if (level < 0 || scale <= 0) return null
    val pct = level * 100 / scale
    if (pct !in 0..100) return null
    return BatteryLevel(pct, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
}
