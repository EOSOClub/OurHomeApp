package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlocksTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val roblox = BlockedAppRule("com.roblox.client", "Roblox", "Alex")

    @Test
    fun blocksOnlyTheListedAppAndNeverOneAlwaysOpen() {
        val blocks = listOf(roblox, BlockedAppRule("com.google.android.dialer", "Phone", "Alex"))
        val allowed = setOf("com.google.android.dialer", "com.eosoclub.ourhome")
        assertEquals(roblox, blockFor("com.roblox.client", blocks, allowed))
        // A block on the phone app (an older list, or a server that didn't know) never applies.
        assertNull(blockFor("com.google.android.dialer", blocks, allowed))
        assertNull(blockFor("com.google.android.youtube", blocks, allowed))
        assertNull(blockFor(null, blocks, allowed))
        assertNull(blockFor("", blocks, allowed))
        assertNull(blockFor("com.roblox.client", emptyList(), allowed))
    }

    @Test
    fun saysWhoBlockedIt() {
        assertEquals("Alex blocked it on your phone. Ask them if you need it.", blockedReason("Alex"))
        assertEquals(
            "An adult who looks after you blocked it on your phone. Ask them if you need it.",
            blockedReason(null),
        )
    }

    @Test
    fun readsTheCheckInAnswerWithAndWithoutBlocks() {
        val answer = json.decodeFromString(
            CheckInResult.serializer(),
            """{"checkedIn":true,"stayConnectedRequired":true,"blockedApps":[
                {"app":"com.roblox.client","label":"Roblox","by":"Alex"},
                {"app":"com.zhiliaoapp.musically","label":"TikTok","by":null}]}""",
        )
        assertEquals(listOf(roblox, BlockedAppRule("com.zhiliaoapp.musically", "TikTok", null)), answer.blockedApps)
        // An older server sends none.
        assertEquals(emptyList<BlockedAppRule>(), json.decodeFromString(CheckInResult.serializer(), """{"checkedIn":true}""").blockedApps)
    }

    @Test
    fun readsTheBlockingOnPeopleYouLookAfter() {
        val person = json.decodeFromString(
            LookedAfter.serializer(),
            """{"id":"riley","name":"Riley","blocking":{"apps":[{"app":"com.roblox.client","label":"Roblox",
                "icon":true,"byName":"Alex","at":"2026-10-24T15:00:00.000Z"}],
                "alwaysAllowed":["com.google.android.dialer"],"works":false}}""",
        )
        assertTrue(person.blocking.isBlocked("com.roblox.client"))
        assertFalse(person.blocking.isBlocked("com.google.android.youtube"))
        assertEquals(false, person.blocking.works)
        assertEquals(listOf("com.google.android.dialer"), person.blocking.alwaysAllowed)
        // An older server: nothing blocked, and no word on whether it would work.
        val old = json.decodeFromString(LookedAfter.serializer(), """{"id":"riley","name":"Riley"}""")
        assertEquals(LookedAfterBlocking(), old.blocking)
        assertNull(old.blocking.works)
    }

    @Test
    fun summarisesTheBlocksOnTheCard() {
        val entry = { label: String -> BlockedAppEntry("app.$label", label) }
        assertNull(blockedSummary(emptyList()))
        assertEquals("Roblox blocked", blockedSummary(listOf(entry("Roblox"))))
        assertEquals("Roblox and TikTok blocked", blockedSummary(listOf(entry("Roblox"), entry("TikTok"))))
        assertEquals("3 apps blocked", blockedSummary(listOf(entry("Roblox"), entry("TikTok"), entry("YouTube"))))
    }
}
