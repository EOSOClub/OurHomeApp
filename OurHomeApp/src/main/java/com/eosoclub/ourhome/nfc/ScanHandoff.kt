package com.eosoclub.ourhome.nfc

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.util.UUID

/**
 * MainActivity is exported (it's the launcher), so any installed app could
 * start it with a "scanned tag" extra and pop the scan sheet on a tag of its
 * choosing. Our own hand-offs (QuickScanActivity, quick-scan notification
 * taps) carry a per-install secret that only this app can read, and
 * MainActivity ignores scan extras without it.
 */
object ScanHandoff {
    private const val PREFS = "scan_handoff"
    private const val KEY = "secret"
    const val EXTRA_SECRET = "scan_handoff_secret"

    private fun secret(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit { putString(KEY, fresh) }
        return fresh
    }

    fun stamp(context: Context, intent: Intent): Intent = intent.putExtra(EXTRA_SECRET, secret(context))

    fun isOurs(context: Context, intent: Intent): Boolean =
        intent.getStringExtra(EXTRA_SECRET) == secret(context)
}
