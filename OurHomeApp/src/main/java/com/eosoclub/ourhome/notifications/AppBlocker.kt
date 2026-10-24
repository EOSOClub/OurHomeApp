package com.eosoclub.ourhome.notifications

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.edit
import com.eosoclub.ourhome.data.BlockedAppRule
import com.eosoclub.ourhome.data.blockFor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Apps a supervising adult blocked on this phone (web appBlockService). The
 * list comes with each check-in's answer ([PhoneCheckIn]) and is kept here,
 * so blocking works offline too. "Keep Our Home running" ([CloseGuardService])
 * sees each app that opens and covers a blocked one with [AppBlockedActivity].
 * Only while an adult looks after this member; never the apps this phone
 * always keeps open ([alwaysAllowed]).
 */
object AppBlocker {
    private const val TAG = "AppBlocker"
    private const val PREFS = "app_blocker"
    private const val KEY_BLOCKS = "blocks"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(BlockedAppRule.serializer())

    @Volatile
    private var cached: List<BlockedAppRule>? = null

    @Volatile
    private var allowedCache: Set<String>? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The blocked apps as last heard from the server. */
    fun blocks(context: Context): List<BlockedAppRule> = cached ?: run {
        val stored = prefs(context).getString(KEY_BLOCKS, null)
        val list = stored?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
        cached = list
        list
    }

    /**
     * The list from a check-in's answer. A change is acted on at once: a
     * blocked app already open is covered.
     */
    fun save(context: Context, blocks: List<BlockedAppRule>) {
        // The default phone or messages app may have changed since.
        allowedCache = null
        if (blocks == blocks(context)) return
        prefs(context).edit { putString(KEY_BLOCKS, json.encodeToString(serializer, blocks)) }
        cached = blocks
        Log.i(TAG, "${blocks.size} app(s) blocked")
        CloseGuard.service?.checkBlockedNow()
    }

    /** Signed out: nothing stays blocked for whoever signs in next. */
    fun signedOut(context: Context) {
        prefs(context).edit { remove(KEY_BLOCKS) }
        cached = emptyList()
    }

    /**
     * The apps this phone never blocks, as package names: OurHome, the home
     * screen, the phone and messages apps (calls and texts always work),
     * Settings and Android's own screens. Sent with the check-in, so People
     * you look after offers no Block for them.
     */
    fun alwaysAllowed(context: Context): Set<String> = allowedCache ?: run {
        val out = linkedSetOf(context.packageName, "com.android.settings", "com.android.systemui")
        runCatching {
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .mapTo(out) { it.activityInfo.packageName }
        }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(out::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(out::add)
        allowedCache = out
        out
    }

    /** The block for [foreground] (the app just opened), while an adult looks after this member; else null. */
    fun blockFor(context: Context, foreground: String?): BlockedAppRule? {
        if (foreground == null || !StayConnected.isRequired(context)) return null
        val list = blocks(context)
        if (list.isEmpty()) return null
        return blockFor(foreground, list, alwaysAllowed(context))
    }

    /** Covers the blocked app with the blocked screen (its own task, so OK can go home). */
    fun cover(context: Context, block: BlockedAppRule) {
        Log.i(TAG, "covering a blocked app")
        context.startActivity(AppBlockedActivity.intent(context, block))
    }
}
