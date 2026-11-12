package com.eosoclub.ourhome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The dates the forms save are in the household's zone, whatever the phone
 * is set to, and a repeat's "Ends on" covers the whole of that day.
 */
class FormDatesTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun aPickedDateIsNoonInTheHouseholdZone() {
        // Nov 20 in Chicago (CST, UTC-6); a phone in Tokyo used to make this 03:00Z.
        assertEquals(Instant.parse("2026-11-20T18:00:00Z"), dueInstant(LocalDate.of(2026, 11, 20), null, chicago))
        assertEquals(Instant.parse("2026-11-20T03:00:00Z"), dueInstant(LocalDate.of(2026, 11, 20), null, tokyo))
        assertNull(dueInstant(null, null, chicago))
    }

    @Test
    fun aNewDateKeepsTheTimeOfDayItHad() {
        // Due 9:30 Chicago on Nov 20, moved to Nov 23: still 9:30 Chicago.
        val moved = dueInstant(LocalDate.of(2026, 11, 23), "2026-11-20T15:30:00Z", chicago)
        assertEquals(Instant.parse("2026-11-23T15:30:00Z"), moved)
    }

    @Test
    fun endsOnCoversTheWholeDay() {
        // The last moment of Dec 16 in Chicago: a 6 PM task that day still happens.
        val until = repeatUntil(LocalDate.of(2026, 12, 16), null, chicago)
        assertEquals(Instant.parse("2026-12-17T05:59:59.999Z"), until)
        assertEquals(LocalDate.of(2026, 12, 16), until!!.atZone(chicago).toLocalDate())
        assertNull(repeatUntil(null, "2026-12-16T18:00:00Z", chicago))
    }

    @Test
    fun anEndDateLeftAloneIsSentBackAsStored() {
        // Stored at noon by an older app or the website: not moved by a save that didn't touch it.
        val stored = "2026-12-16T18:00:00Z"
        assertEquals(Instant.parse(stored), repeatUntil(LocalDate.of(2026, 12, 16), stored, chicago))
        // Changed to another day: the end of that day.
        assertEquals(Instant.parse("2026-12-18T05:59:59.999Z"), repeatUntil(LocalDate.of(2026, 12, 17), stored, chicago))
    }

    @Test
    fun endsOnHoldsOnADaylightSavingDay() {
        // Mar 8 2026: clocks go forward in Chicago, the day is 23 hours long.
        val until = repeatUntil(LocalDate.of(2026, 3, 8), null, chicago)
        assertEquals(Instant.parse("2026-03-09T04:59:59.999Z"), until)
    }
}
