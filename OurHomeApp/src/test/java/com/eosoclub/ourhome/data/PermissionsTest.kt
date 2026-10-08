package com.eosoclub.ourhome.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {

    @Test
    fun ownRecordsUseTheOwnSwitches() {
        val ownOnly = PageAccess(editOwn = true, deleteOwn = true)
        assertTrue(ownOnly.canEdit("u1", "u1"))
        assertTrue(ownOnly.canDelete("u1", "u1"))
        assertFalse(ownOnly.canEdit("u2", "u1"))
    }

    @Test
    fun othersAndUnownedRecordsUseTheOthersSwitches() {
        val othersOnly = PageAccess(editOthers = true)
        assertTrue(othersOnly.canEdit("u2", "u1"))
        assertTrue(othersOnly.canEdit(null, "u1"))
        assertFalse(othersOnly.canEdit("u1", "u1"))
        assertFalse(othersOnly.canDelete("u2", "u1"))
    }

    @Test
    fun anyIsTrueWhenOneSwitchIsOn() {
        assertFalse(PageAccess.NONE.any)
        assertTrue(PageAccess(deleteOthers = true).any)
    }

    @Test
    fun defaultsMatchTheWebsiteBuiltIns() {
        assertEquals(PageAccess.ALL, defaultAccess("head").tasks)
        assertFalse(defaultAccess("member").tasks.any)
        assertEquals(PageAccess.ALL, defaultAccess("member").shopping)
        assertFalse(defaultAccess("guest").shopping.any)
        for (role in listOf("manager", "member", "guest")) {
            assertEquals(PageAccess.SUBMIT_ONLY, defaultAccess(role).requests)
        }
        assertFalse(defaultAccess("stranger").requests.any)
    }

    @Test
    fun parsesTheMeEndpoint() {
        val json = Json { ignoreUnknownKeys = true }
        val body = """
            {"userId":"u1","role":"guest","access":{
              "tasks":{"create":false,"editOwn":false,"deleteOwn":false,"editOthers":false,"deleteOthers":false},
              "requests":{"create":false,"editOwn":false,"deleteOwn":false,"editOthers":false,"deleteOthers":false},
              "shopping":{"create":true,"editOwn":true,"deleteOwn":false,"editOthers":false,"deleteOthers":false},
              "futurePage":{"create":true}
            }}
        """.trimIndent()
        val me = json.decodeFromString(MyAccess.serializer(), body)
        assertFalse(me.access.requests.create)
        assertTrue(me.access.shopping.canEdit("u1", "u1"))
        assertFalse(me.access.shopping.canDelete("u1", "u1"))
        // Pages the server didn't send default to no access.
        assertFalse(me.access.bills.any)
    }
}
