package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RequestAttentionTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 10, 10)
    private val me = "me"
    private val other = "other"

    /** Accepted maintenance with a done-by date at noon local, like the web/app forms. */
    private fun maintenance(
        due: LocalDate?,
        assignee: String = me,
        requester: String = other,
        status: String = "accepted",
    ) = HouseholdRequest(
        id = "r-$due-$assignee-$status",
        category = "maintenance",
        title = "Fix faucet",
        assignee = UserRef(assignee, assignee),
        status = status,
        dueAt = due?.atTime(12, 0)?.atZone(zone)?.toInstant()?.toString(),
        requester = UserRef(requester, requester),
        createdAt = "2026-10-01T12:00:00Z",
    )

    private fun alerts(vararg r: HouseholdRequest) = deadlineAlerts(r.toList(), me, today, zone)

    @Test fun `assignee hears about tomorrow, today and overdue`() {
        assertEquals(DeadlineState.DueTomorrow, alerts(maintenance(today.plusDays(1))).single().state)
        assertEquals(DeadlineState.DueToday, alerts(maintenance(today)).single().state)
        assertEquals(DeadlineState.Overdue, alerts(maintenance(today.minusDays(3))).single().state)
    }

    @Test fun `nothing for dates further out`() {
        assertTrue(alerts(maintenance(today.plusDays(2))).isEmpty())
    }

    @Test fun `only accepted requests with a date count`() {
        assertTrue(alerts(maintenance(today, status = "pending")).isEmpty())
        assertTrue(alerts(maintenance(today, status = "completed")).isEmpty())
        assertTrue(alerts(maintenance(null)).isEmpty())
    }

    @Test fun `requester hears only when overdue`() {
        val overdue = alerts(maintenance(today.minusDays(1), assignee = other, requester = me)).single()
        assertTrue(overdue.forRequester)
        assertEquals(DeadlineState.Overdue, overdue.state)
        assertTrue(alerts(maintenance(today, assignee = other, requester = me)).isEmpty())
    }

    @Test fun `bystanders hear nothing`() {
        assertTrue(alerts(maintenance(today.minusDays(1), assignee = other, requester = other)).isEmpty())
    }

    @Test fun `media requests are ignored`() {
        val media = maintenance(today).copy(category = "media")
        assertTrue(alerts(media).isEmpty())
    }

    private fun mediaModule(handles: Boolean) = RequestModule(id = "media", workflow = "approve", label = "Media", handles = handles)
    private val handlesMedia = listOf(mediaModule(true))
    private val handlesNothing = listOf(mediaModule(false))

    private fun media(status: String?) = HouseholdRequest(
        id = "m-$status",
        category = "media",
        title = "Dune",
        status = status,
        requester = UserRef(other, other),
        createdAt = "2026-10-01T12:00:00Z",
    )

    @Test fun `open media waits on those who handle it only, including old accepted rows`() {
        val all = listOf(media(null), media("pending"), media("accepted"), media("completed"), media("declined"))
        assertEquals(
            listOf("m-null", "m-pending", "m-accepted"),
            awaitingAcceptanceBy(all, me, handlesMedia).map { it.id },
        )
        assertTrue(awaitingAcceptanceBy(all, me, handlesNothing).isEmpty())
    }

    @Test fun `media is one step - waiting, added or declined`() {
        assertEquals("pending", media("accepted").mediaStatus)
        assertEquals("pending", media(null).mediaStatus)
        assertEquals("completed", media("completed").mediaStatus)
        assertEquals("declined", media("declined").mediaStatus)
        assertEquals(false, media("declined").isOpen)
    }

    @Test fun `a problem report says so, with its season and episode`() {
        val show = media("pending").copy(title = "Severance", mediaType = "tv", year = 2022)
        assertEquals("TV: Severance (2022) · S2 - from $other", show.copy(season = 2).summary())
        val problem = show.copy(issue = true, season = 2, episode = 3)
        assertEquals("TV problem: Severance (2022) · S2 E3 - from $other", problem.summary())
        assertEquals("TV problem: Severance (2022)", problem.copy(season = null, episode = null).mediaTitle())
        // A problem waits on whoever handles media, like a request to add.
        assertEquals(listOf(problem), awaitingAcceptanceBy(listOf(problem), me, handlesMedia))
    }

    @Test fun `an assign-workflow module's handles flag doesn't make anyone a handler`() {
        val modules = listOf(RequestModule(id = "maintenance", workflow = "assign", label = "Maintenance", handles = true))
        val theirs = maintenance(null, assignee = other, status = "pending")
        assertTrue(awaitingAcceptanceBy(listOf(theirs), me, modules).isEmpty())
    }

    @Test fun `an older server gets the old two kinds`() {
        val modules = defaultRequestModules(canSubmit = true, approvesMedia = false)
        assertEquals(listOf("media", "maintenance"), modules.map { it.id })
        assertTrue(modules.all { it.canAsk })
        assertTrue(awaitingAcceptanceBy(listOf(media("pending")), me, modules).isEmpty())
        val approver = defaultRequestModules(canSubmit = false, approvesMedia = true)
        assertEquals(listOf("m-pending"), awaitingAcceptanceBy(listOf(media("pending")), me, approver).map { it.id })
    }

    private fun decision(category: String, requester: String, status: String? = "pending") = HouseholdRequest(
        id = "$category-$requester-$status",
        category = category,
        title = "Sleepover at Ava's",
        status = status,
        requester = UserRef(requester, requester),
        createdAt = "2026-10-01T12:00:00Z",
    )

    private fun permissionModule(handles: Boolean, supervising: List<String>) = RequestModule(
        id = "permission",
        workflow = "approve",
        label = "Permission",
        handles = handles,
        bySupervisors = true,
        supervisedIds = listOf("kid"),
        supervisingIds = supervising,
    )

    @Test fun `a supervised child's permission waits on their supervisors only`() {
        val kids = decision("permission", "kid")
        val parent = permissionModule(handles = false, supervising = listOf("kid"))
        val head = permissionModule(handles = true, supervising = emptyList())
        assertTrue(parent.decides("kid"))
        assertEquals(false, head.decides("kid"))
        assertEquals(listOf(kids), awaitingAcceptanceBy(listOf(kids), me, listOf(parent)))
        assertTrue(awaitingAcceptanceBy(listOf(kids), me, listOf(head)).isEmpty())
        // Decided: waits on nobody.
        assertTrue(awaitingAcceptanceBy(listOf(decision("permission", "kid", "completed")), me, listOf(parent)).isEmpty())
    }

    @Test fun `anyone without supervisors goes to the usual handlers`() {
        val adults = decision("permission", "adult")
        assertTrue(permissionModule(handles = true, supervising = emptyList()).decides("adult"))
        assertEquals(false, permissionModule(handles = false, supervising = listOf("kid")).decides("adult"))
        // With the rule off, supervisors don't matter.
        val off = permissionModule(handles = true, supervising = listOf("kid")).copy(bySupervisors = false)
        assertTrue(off.decides("kid"))
        assertEquals(listOf(adults), awaitingAcceptanceBy(listOf(adults), me, listOf(off)))
    }

    @Test fun `purchase and permission summaries say what they are`() {
        assertEquals("Permission: Sleepover at Ava's - from kid", decision("permission", "kid").summary())
        assertEquals("Buy: LEGO", decision("purchase", "kid").copy(title = "LEGO").decisionTitle())
        assertEquals("Maintenance: Fix faucet - from $other", maintenance(null).summary())
    }

    @Test fun `media approval comes from the grid, and the head always has it`() {
        val approver = AccessMatrix(requests = PageAccess.SUBMIT_AND_APPROVE)
        assertTrue(canApproveMedia(approver, "member"))
        assertTrue(canApproveMedia(AccessMatrix(), "head"))
        assertEquals(false, canApproveMedia(defaultAccess("member"), "member"))
        assertTrue(canApproveMedia(defaultAccess("manager"), "manager"))
    }

    @Test fun `maintenance waits only on its pending assignee`() {
        val mine = maintenance(null, status = "pending")
        val accepted = maintenance(today, status = "accepted")
        val theirs = maintenance(null, assignee = other, status = "pending")
        assertEquals(listOf(mine), awaitingAcceptanceBy(listOf(mine, accepted, theirs), me, handlesMedia))
    }
}
