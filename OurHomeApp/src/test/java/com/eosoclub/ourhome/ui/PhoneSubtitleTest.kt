package com.eosoclub.ourhome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Profile's closed Phone and sharing block must still say what's inside, shared app usage included. */
class PhoneSubtitleTest {

    @Test
    fun namesEverySectionThatShows() {
        assertEquals("Location, app usage, alerts and keeping Our Home running.", phoneSubtitle(showLocation = true, usageShared = true))
    }

    @Test
    fun leavesOutWhatDoesNotShow() {
        assertEquals("Location, alerts and keeping Our Home running.", phoneSubtitle(showLocation = true, usageShared = false))
        assertEquals("App usage, alerts and keeping Our Home running.", phoneSubtitle(showLocation = false, usageShared = true))
        assertEquals("Alerts and keeping Our Home running.", phoneSubtitle(showLocation = false, usageShared = false))
    }
}
