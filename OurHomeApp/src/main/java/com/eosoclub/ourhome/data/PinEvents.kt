package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

/**
 * Something the parent PIN did on this phone, kept until a check-in gets it
 * to the server (web lib/parentPin.ts), which tells the adults who look after
 * this member. [id]: made here, the same every time it's sent, so the server
 * alerts once. [kind]: one of [PinEvents]. [screen]: what it opened
 * (CloseGuard.Screen.wire), for [PinEvents.OPENED].
 */
@Serializable
data class PinEvent(val id: String, val kind: String, val atMs: Long, val screen: String? = null)

object PinEvents {
    /** Wrong 5 times in a row, so the phone makes them wait before the next try. */
    const val WRONG_PIN = "wrong_pin"

    /** Opened a protected screen (the parent PIN screen over it). */
    const val OPENED = "opened"

    /** Unlock Our Home: Prevent uninstall turned off. */
    const val UNLOCKED = "unlocked"

    /** As many as a check-in takes (web phoneCheckInSchema): the oldest go first. */
    const val MAX_KEPT = 20

    /** [kept] with [event] added, at most [MAX_KEPT]. */
    fun add(kept: List<PinEvent>, event: PinEvent): List<PinEvent> = (kept + event).takeLast(MAX_KEPT)

    /** [kept] without the ones a check-in got through ([sent] ids); any added meanwhile stay. */
    fun withoutSent(kept: List<PinEvent>, sent: Collection<String>): List<PinEvent> {
        val ids = sent.toSet()
        return kept.filterNot { it.id in ids }
    }
}
