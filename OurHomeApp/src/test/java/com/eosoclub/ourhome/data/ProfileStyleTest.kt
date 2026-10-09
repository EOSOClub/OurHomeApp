package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The same birthday/initials cases as the web's src/lib/profile.test.ts. */
class ProfileStyleTest {
    @Test
    fun birthdaysAcceptRealDaysIncludingFeb29() {
        assertTrue(ProfileStyle.isBirthday("01-01"))
        assertTrue(ProfileStyle.isBirthday("02-29"))
        assertTrue(ProfileStyle.isBirthday("12-31"))
    }

    @Test
    fun birthdaysRejectImpossibleOrMalformedDays() {
        assertFalse(ProfileStyle.isBirthday("02-30"))
        assertFalse(ProfileStyle.isBirthday("04-31"))
        assertFalse(ProfileStyle.isBirthday("13-01"))
        assertFalse(ProfileStyle.isBirthday("00-10"))
        assertFalse(ProfileStyle.isBirthday("1-1"))
    }

    @Test
    fun birthdayBuildsAndFormats() {
        assertEquals("03-04", ProfileStyle.birthday(3, 4))
        assertNull(ProfileStyle.birthday(2, 30))
        assertNull(ProfileStyle.birthday(null, 4))
        assertEquals("March 14", ProfileStyle.formatBirthday("03-14", Locale.US))
        assertNull(ProfileStyle.formatBirthday("02-30", Locale.US))
    }

    @Test
    fun initialsUseFirstAndLastWords() {
        assertEquals("JD", ProfileStyle.initials("jane van doe"))
        assertEquals("S", ProfileStyle.initials("Sam"))
        assertEquals("?", ProfileStyle.initials("  "))
    }

    @Test
    fun unknownColourFallsBackToSlate() {
        assertEquals(0xFF64748B, ProfileStyle.color("mauve"))
        assertEquals(0xFF14B8A6, ProfileStyle.color("teal"))
    }
}
