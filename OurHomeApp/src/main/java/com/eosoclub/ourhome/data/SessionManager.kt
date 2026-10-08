package com.eosoclub.ourhome.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.eosoclub.ourhome.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AuthState {
    data object Checking : AuthState
    data class SignedOut(val message: String? = null) : AuthState
    data class SignedIn(val user: SessionUser) : AuthState
}

/** Owns the server URL, the cookie session, and the app-wide [AuthState]. */
class SessionManager(private val prefs: SharedPreferences, cookiePrefs: SharedPreferences) {

    private val cookieJar = PersistentCookieJar(cookiePrefs)

    private val _state = MutableStateFlow<AuthState>(AuthState.Checking)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    var baseUrl: String = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.DEFAULT_BASE_URL
        private set

    val api = ApiClient(
        baseUrl = { baseUrl },
        cookieJar = cookieJar,
        onUnauthorized = { _state.value = AuthState.SignedOut("Your session has expired. Please sign in again.") },
    )

    /** Restores a saved session on launch. */
    suspend fun restore() {
        _state.value = try {
            api.getSession()?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut()
        } catch (e: Exception) {
            AuthState.SignedOut("Couldn't reach $baseUrl: ${e.message}")
        }
    }

    /**
     * Re-reads the signed-in user when the app comes back to the front, so
     * changes made on the website meanwhile (a new password clearing the
     * temporary-password flag, a new name) show without signing in again.
     * Offline or a server hiccup keeps the current state; only a session the
     * server no longer knows signs out.
     */
    suspend fun refreshUser() {
        if (_state.value !is AuthState.SignedIn) return
        val user = try {
            api.getSession()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return
        }
        // Signed out or switched while the request ran: leave that alone.
        val current = _state.value as? AuthState.SignedIn ?: return
        _state.value = when {
            user == null -> AuthState.SignedOut("Your session has expired. Please sign in again.")
            user != current.user -> AuthState.SignedIn(user)
            else -> current
        }
    }

    /** Signs in and loads the user; throws with a user-facing message on failure. */
    suspend fun signIn(server: String, username: String, password: String) {
        val normalized = normalize(server)
        if (normalized != baseUrl) {
            // Cookies belong to the old origin; start clean on a new server.
            cookieJar.clear()
            baseUrl = normalized
            prefs.edit { putString(KEY_BASE_URL, normalized) }
        }
        api.signIn(username.trim(), password)
        val user = api.getSession() ?: throw ApiException("Signed in, but no session was returned.")
        if (user.householdId == null) {
            api.signOut()
            throw ApiException("This account isn't assigned to a household yet.")
        }
        _state.value = AuthState.SignedIn(user)
    }

    /**
     * Reflects a profile edit in the signed-in user without a round trip
     * (Better Auth's session cookie cache can serve the old name for minutes).
     */
    fun applyProfile(profile: ProfileOverview) {
        val current = _state.value as? AuthState.SignedIn ?: return
        _state.value = AuthState.SignedIn(
            current.user.copy(
                name = profile.name,
                username = profile.username?.lowercase(),
                displayUsername = profile.username,
            ),
        )
    }

    fun clearMustChangePassword() {
        val current = _state.value as? AuthState.SignedIn ?: return
        _state.value = AuthState.SignedIn(current.user.copy(mustChangePassword = false))
    }

    /** The FCM token last registered with the server, so sign-out can remove it. */
    fun rememberPushToken(token: String) = prefs.edit { putString(KEY_PUSH_TOKEN, token) }

    suspend fun signOut() {
        // While the session still works: stop this phone getting the account's
        // alerts (matters on a shared phone). Best-effort; a stale token only
        // wakes a signed-out app, which finds no session and stays quiet.
        prefs.getString(KEY_PUSH_TOKEN, null)?.let { token ->
            try {
                api.unregisterPushDevice(token)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        prefs.edit { remove(KEY_PUSH_TOKEN) }
        api.signOut()
        _state.value = AuthState.SignedOut()
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_PUSH_TOKEN = "push_token"
    }
}
