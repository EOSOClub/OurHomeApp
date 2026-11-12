package com.eosoclub.ourhome.data

import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Keeps Better Auth's session cookies across app restarts.
 *
 * Cookies are stored in their Set-Cookie form, keyed by name/domain/path, and
 * reparsed against the origin they came from. Storage is app-private
 * SharedPreferences; good enough for an experiment, not hardened storage.
 */
class PersistentCookieJar(private val prefs: SharedPreferences) : CookieJar {

    private val cookies = mutableMapOf<String, Cookie>()

    init {
        prefs.getStringSet(KEY, emptySet())!!.forEach { line ->
            val (origin, setCookie) = line.split('\n', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            val url = origin.toHttpUrlOrNull() ?: return@forEach
            Cookie.parse(url, setCookie)?.let { cookies[it.key()] = it }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            if (takesCookieAway(cookie)) this.cookies.remove(cookie.key())
            else this.cookies[cookie.key()] = cookie
        }
        persist()
    }

    /**
     * Every stored cookie for [url], whatever this phone's clock says about
     * its expiry: the server decides whether a session is still good. They
     * used to be dropped here, for good, once the phone's clock passed their
     * expiry. Setting the date a week ahead threw away a session the server
     * still accepted, and the app then signed out (which, on a supervised
     * phone, lifts its protections). An expired one that is sent is ignored
     * by the server.
     */
    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.filter { it.matches(url) }

    @Synchronized
    fun clear() {
        cookies.clear()
        persist()
    }

    private fun persist() {
        val lines = cookies.values.map { c ->
            val scheme = if (c.secure) "https" else "http"
            "$scheme://${c.domain}${c.path}\n$c"
        }.toSet()
        prefs.edit { putStringSet(KEY, lines) }
    }

    private fun Cookie.key() = "$name|$domain|$path"

    private companion object {
        const val KEY = "cookies"
    }
}

/**
 * Whether a Set-Cookie from the server removes the cookie: Max-Age 0 (which
 * OkHttp reads as the earliest possible expiry) or an empty value, the two
 * ways Better Auth signs out. Not "its expiry is before this phone's clock":
 * the clock is the phone owner's to change.
 */
internal fun takesCookieAway(cookie: Cookie): Boolean =
    cookie.expiresAt == Long.MIN_VALUE || cookie.value.isEmpty()
