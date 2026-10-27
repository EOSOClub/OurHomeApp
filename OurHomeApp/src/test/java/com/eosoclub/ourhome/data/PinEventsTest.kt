package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PinEventsTest {
    private fun event(n: Int) = PinEvent("event-$n", PinEvents.WRONG_PIN, n.toLong())

    @Test
    fun `keeps the newest 20, as many as a check-in takes`() {
        val kept = (1..25).fold(emptyList<PinEvent>()) { list, n -> PinEvents.add(list, event(n)) }
        assertEquals(PinEvents.MAX_KEPT, kept.size)
        assertEquals((6..25).map { "event-$it" }, kept.map { it.id })
    }

    @Test
    fun `drops only what a check-in got through, keeping any added meanwhile`() {
        val kept = listOf(event(1), event(2), event(3))
        assertEquals(listOf(event(3)), PinEvents.withoutSent(kept, listOf("event-1", "event-2")))
        assertEquals(kept, PinEvents.withoutSent(kept, emptyList()))
    }

    @Test
    fun `every kind is one the server knows`() {
        // web lib/parentPin.ts PIN_EVENT_KINDS
        assertEquals(
            setOf("wrong_pin", "opened", "unlocked"),
            setOf(PinEvents.WRONG_PIN, PinEvents.OPENED, PinEvents.UNLOCKED),
        )
    }
}
