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
        assertFalse(defaultAccess("adult").tasks.any)
        assertEquals(PageAccess.ALL, defaultAccess("adult").shopping)
        assertFalse(defaultAccess("guest").shopping.any)
        for (role in listOf("adult", "teen", "child", "guest")) {
            assertEquals(PageAccess.SUBMIT_ONLY, defaultAccess(role).requests)
        }
        assertEquals(PageAccess.SUBMIT_AND_APPROVE, defaultAccess("manager").requests)
        assertTrue(defaultAccess("head").requests.approve)
        assertFalse(defaultAccess("stranger").requests.any)
    }

    @Test
    fun theOldMemberNameStillMeansAdult() {
        // A server from before 2026-10-11 still sends "member".
        assertEquals(defaultAccess("adult"), defaultAccess("member"))
        for (p in Permission.entries) assertEquals(can("adult", p), can("member", p))
        assertTrue(can("adult", Permission.TasksComplete))
        assertFalse(can("adult", Permission.MembersManage))
    }

    @Test
    fun teensAndChildrenGetTieredDefaults() {
        val teen = defaultAccess("teen")
        for (page in listOf(teen.calendar, teen.shopping, teen.inventory)) {
            assertEquals(PageAccess.OWN_ONLY, page)
        }
        assertFalse(teen.lists.any)
        assertFalse(teen.tasks.any)
        assertFalse(teen.bills.any)

        val child = defaultAccess("child")
        assertEquals(PageAccess.ADD_ONLY, child.shopping)
        assertTrue(child.shopping.any) // enough to tick items off
        assertFalse(child.lists.any)
        assertFalse(child.inventory.any)
    }

    @Test
    fun listAccessFallsBackToShoppingOnAnOlderServer() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            AccessMatrix.serializer(),
            """{"shopping":{"create":true,"editOwn":true,"deleteOwn":true,"editOthers":false,"deleteOthers":false}}""",
        )
        assertTrue(old.lists.create)
        val new = json.decodeFromString(
            AccessMatrix.serializer(),
            """{"shopping":{"create":true},"shoppingLists":{"create":false},"requests":{"create":true,"approve":true}}""",
        )
        assertFalse(new.lists.create)
        assertTrue(new.requests.approve)
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

    @Test
    fun theHeadManagesEveryoneButNeverAssignsHead() {
        USER_ROLES.forEach { assertTrue(it, canManageMember("head", it)) }
        assertEquals(listOf("manager", "adult", "teen", "child", "guest"), assignableRoles("head"))
        assertFalse(canAssignRole("head", "head"))
    }

    @Test
    fun aManagerManagesOnlyTheRanksBelow() {
        assertFalse(canManageMember("manager", "head"))
        assertFalse(canManageMember("manager", "manager"))
        assertTrue(canManageMember("manager", "adult"))
        assertTrue(canManageMember("manager", "guest"))
        assertEquals(listOf("adult", "teen", "child", "guest"), assignableRoles("manager"))
    }

    @Test
    fun everyoneElseManagesNobody() {
        listOf("adult", "member", "teen", "child", "guest", null, "unknown").forEach { actor ->
            assertFalse("$actor", canManageMember(actor, "guest"))
            assertTrue("$actor", assignableRoles(actor).isEmpty())
        }
        // Unknown roles are never manageable, even by the head.
        assertFalse(canManageMember("head", "unknown"))
    }

    @Test
    fun anOldMemberRoleRanksAsAdult() {
        assertTrue(canManageMember("manager", "member"))
        assertFalse(canAssignRole("head", "member"))
    }

    @Test
    fun onlyTheHeadManagesTheHousehold() {
        assertTrue(can("head", Permission.HouseholdManage))
        listOf("manager", "adult", "teen", "child", "guest").forEach { assertFalse(it, can(it, Permission.HouseholdManage)) }
        assertTrue(can("manager", Permission.MembersManage))
        assertFalse(can("adult", Permission.MembersManage))
    }
}
