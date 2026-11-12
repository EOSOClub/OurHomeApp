package com.eosoclub.ourhome.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZoneId

/**
 * The household's time zone (Settings, "Time zone and week"): every date and
 * time the app shows or saves is in it, like the website, "whatever their
 * own phone is set to". The forms used to read and write in the phone's zone,
 * so a phone set to another zone (travelling, or just set wrong) saved a due
 * time hours off, and moved it again with every save.
 *
 * Learned from the server with the Home screen and the task list ([set]).
 * Until then, and with an older server that doesn't say, it is the phone's.
 * Screens that read [id] redraw when it arrives.
 */
object HouseholdZone {
    var id: ZoneId by mutableStateOf(ZoneId.systemDefault())
        private set

    /** [timezone]: an IANA name from the server; null or unknown leaves the zone as it is. */
    fun set(timezone: String?) {
        val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return
        if (zone != id) id = zone
    }
}
