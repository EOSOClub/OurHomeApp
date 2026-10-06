package com.eosoclub.ourhome.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.eosoclub.ourhome.BuildConfig
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

    suspend fun signOut() {
        api.signOut()
        _state.value = AuthState.SignedOut()
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
    }
}
