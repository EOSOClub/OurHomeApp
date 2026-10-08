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

    private fun media(status: String?) = HouseholdRequest(
        id = "m-$status",
        category = "media",
        title = "Dune",
        status = status,
        requester = UserRef(other, other),
        createdAt = "2026-10-01T12:00:00Z",
    )

    @Test fun `open media waits on approvers only, including old accepted rows`() {
        val all = listOf(media(null), media("pending"), media("accepted"), media("completed"))
        assertEquals(
            listOf("m-null", "m-pending", "m-accepted"),
            awaitingAcceptanceBy(all, me, approvesMedia = true).map { it.id },
        )
        assertTrue(awaitingAcceptanceBy(all, me, approvesMedia = false).isEmpty())
    }

    @Test fun `media is one step - waiting or added`() {
        assertEquals("pending", media("accepted").mediaStatus)
        assertEquals("pending", media(null).mediaStatus)
        assertEquals("completed", media("completed").mediaStatus)
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
        assertEquals(listOf(mine), awaitingAcceptanceBy(listOf(mine, accepted, theirs), me, approvesMedia = true))
    }
}
