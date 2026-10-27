package com.eosoclub.ourhome.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.eosoclub.ourhome.data.PRIVATE_DNS_OFF
import com.eosoclub.ourhome.data.normalizeHost
import com.eosoclub.ourhome.data.usesFilter

/**
 * The web filter a supervising adult chose for this phone (web
 * lib/webFilter.ts). OurHome can't switch Android's Private DNS on by itself
 * (only a device owner can), so Set up this phone shows the hostname to enter,
 * each check-in says what Private DNS is set to ([privateDns]), and the
 * answer brings the chosen hostname, kept here ([save]). While this phone uses
 * it, "Keep Our Home running" ([CloseGuardService]) closes Private DNS's
 * dialog, so it can't be changed here.
 */
object WebFilter {
    private const val TAG = "WebFilter"
    private const val PREFS = "web_filter"
    private const val KEY_HOSTNAME = "hostname"
    private const val SETTINGS_PACKAGE = "com.android.settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * What Android's Private DNS is set to: the provider hostname, or
     * [PRIVATE_DNS_OFF] (off or Automatic); null when it can't tell (no network
     * right now). Read from the network in use, which carries the setting.
     */
    fun privateDns(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = runCatching { connectivity.getLinkProperties(connectivity.activeNetwork) }.getOrNull() ?: return null
        return props.privateDnsServerName?.let(::normalizeHost) ?: PRIVATE_DNS_OFF
    }

    /** The chosen filter's hostname, as last heard from the server; null when none. */
    fun expected(context: Context): String? = prefs(context).getString(KEY_HOSTNAME, null)

    /** The hostname from a check-in's answer (null: none chosen, or nobody looks after this member). */
    fun save(context: Context, hostname: String?) {
        if (hostname == expected(context)) return
        prefs(context).edit { if (hostname == null) remove(KEY_HOSTNAME) else putString(KEY_HOSTNAME, hostname) }
        Log.i(TAG, if (hostname == null) "no web filter" else "web filter chosen")
    }

    /** Whether this phone's Private DNS is [hostname] right now. */
    fun isOn(context: Context, hostname: String): Boolean = usesFilter(privateDns(context), hostname)

    /** A filter is chosen and this phone uses it: the guard keeps them out of Private DNS. */
    fun guarded(context: Context): Boolean = expected(context)?.let { isOn(context, it) } == true

    /** Signed out: no filter for whoever signs in next. */
    fun signedOut(context: Context) {
        prefs(context).edit { remove(KEY_HOSTNAME) }
    }

    /**
     * Android's Network & internet page (Connections on Samsung), where Private
     * DNS is; there's no public way to open its dialog directly. Settings' own
     * task, like [CloseGuard.accessibilitySettings].
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The words of the Private DNS dialog's "Private DNS provider hostname"
     * choice in this phone's language (Settings' `private_dns_mode_provider`),
     * and English. Only the dialog shows them; the page it opens from shows
     * just "Private DNS".
     */
    @SuppressLint("DiscouragedApi")
    internal fun providerLabels(context: Context): Set<String> {
        val labels = mutableSetOf(DEFAULT_PROVIDER_LABEL)
        runCatching {
            val res = context.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
            val id = res.getIdentifier("private_dns_mode_provider", "string", SETTINGS_PACKAGE)
            if (id != 0) labels += res.getString(id)
        }.onFailure { Log.w(TAG, "can't read Settings' Private DNS text; using English", it) }
        return labels
    }

    internal const val DEFAULT_PROVIDER_LABEL = "Private DNS provider hostname"
}
