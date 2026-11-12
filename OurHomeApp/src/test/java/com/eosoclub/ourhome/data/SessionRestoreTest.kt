package com.eosoclub.ourhome.data

import kotlinx.coroutines.CancellationException
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What counts as signed out. Being signed out lifts a supervised phone's
 * protections, so only the server saying so may do it: not a failed check,
 * and not the phone's own clock.
 */
class SessionRestoreTest {
    private val base = "https://home.example"
    private val user = SessionUser(id = "u1")

    @Test
    fun aSessionTheServerKnowsSignsIn() {
        assertEquals(AuthState.SignedIn(user), stateAfterRestore(Result.success(user), base))
    }

    @Test
    fun theServerSayingNoSessionSignsOut() {
        assertEquals(AuthState.SignedOut(), stateAfterRestore(Result.success(null), base))
    }

    @Test
    fun noNetworkIsNotASignOut() {
        val state = stateAfterRestore(Result.failure(IOException("Unable to resolve host")), base)
        assertTrue(state is AuthState.Unreachable)
        assertEquals("Couldn't reach $base: Unable to resolve host", (state as AuthState.Unreachable).message)
    }

    @Test
    fun aServerErrorIsNotASignOut() {
        val state = stateAfterRestore(Result.failure(ApiException("Could not reach the server (503)")), base)
        assertTrue(state is AuthState.Unreachable)
    }

    @Test
    fun aCancelledCheckDecidesNothing() {
        assertThrows(CancellationException::class.java) {
            stateAfterRestore(Result.failure(CancellationException("screen turned")), base)
        }
    }

    private fun cookie(setCookie: String) = Cookie.parse(base.toHttpUrl(), setCookie)!!

    @Test
    fun aSessionCookieIsKeptWhateverThePhoneClockSays() {
        // Long past its date by any clock: still the server's to refuse, not ours to drop.
        assertFalse(takesCookieAway(cookie("session=abc; Path=/; Expires=Thu, 01 Jan 2015 00:00:00 GMT")))
        assertFalse(takesCookieAway(cookie("session=abc; Path=/; Max-Age=604800")))
        assertFalse(takesCookieAway(cookie("session=abc; Path=/")))
    }

    @Test
    fun theServerTakingTheCookieAwayRemovesIt() {
        assertTrue(takesCookieAway(cookie("session=abc; Path=/; Max-Age=0")))
        assertTrue(takesCookieAway(cookie("session=; Path=/")))
    }
}
