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
 * re-parsed against the origin they came from. Storage is app-private
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
            if (cookie.expiresAt < System.currentTimeMillis()) this.cookies.remove(cookie.key())
            else this.cookies[cookie.key()] = cookie
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val expired = cookies.values.filter { it.expiresAt < now }
        if (expired.isNotEmpty()) {
            expired.forEach { cookies.remove(it.key()) }
            persist()
        }
        return cookies.values.filter { it.matches(url) }
    }

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
