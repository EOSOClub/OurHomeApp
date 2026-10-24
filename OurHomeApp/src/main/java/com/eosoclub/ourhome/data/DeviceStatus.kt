package com.eosoclub.ourhome.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How this phone is doing, so the adults who look after its member can be
 * told why it went quiet (flat, out of signal, switched off) rather than just
 * that it did (web lib/phoneHealth.ts). Sent only while an adult looks after
 * this member: with the check-in, and when Stay connected opens its stream.
 * [network]: "wifi" | "cellular" | "other" | "none"; [signal]: mobile signal,
 * 0 (none) to 4 (great). Null: Android wouldn't say.
 */
data class DeviceStatus(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val network: String? = null,
    val signal: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        battery?.let { put("battery", it) }
        charging?.let { put("charging", it) }
        network?.let { put("network", it) }
        signal?.let { put("signal", it) }
    }

    /** As the stream's query fields, each starting with "&"; empty when there's nothing. */
    fun toQuery(): String =
        listOfNotNull(
            battery?.let { "battery=$it" },
            charging?.let { "charging=$it" },
            network?.let { "network=$it" },
            signal?.let { "signal=$it" },
        ).joinToString("") { "&$it" }

    companion object {
        /** At or below this, not charging, the phone tells the server at once: its adults are told it's low (web PHONE_LOW_PCT). */
        const val LOW_PCT = 15

        /** At or below this, not charging, it tells the server again: it's about to go flat (web PHONE_FLAT_PCT). */
        const val FLAT_PCT = 5

        /** Android's network as the server's word for it. */
        fun networkName(connected: Boolean, wifi: Boolean, cellular: Boolean): String = when {
            !connected -> "none"
            wifi -> "wifi"
            cellular -> "cellular"
            else -> "other"
        }

        /**
         * How low the battery is: 0 fine (or charging), 1 at [LOW_PCT] or
         * below, 2 at [FLAT_PCT] or below. A reading is sent at once each
         * time this changes: lower, so the adults are told; back up (plugged
         * in), so their low battery alert goes without waiting.
         */
        fun lowStep(pct: Int, charging: Boolean): Int = when {
            charging || pct !in 0..LOW_PCT -> 0
            pct <= FLAT_PCT -> 2
            else -> 1
        }
    }
}
