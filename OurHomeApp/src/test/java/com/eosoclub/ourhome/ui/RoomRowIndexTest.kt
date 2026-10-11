package com.eosoclub.ourhome.ui

import com.eosoclub.ourhome.data.Floor
import com.eosoclub.ourhome.data.PlaceRef
import com.eosoclub.ourhome.data.Places
import com.eosoclub.ourhome.data.Room
import com.eosoclub.ourhome.data.Task
import com.eosoclub.ourhome.data.groupByPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scrolling to a scanned location tag's room must land on its heading row. */
class RoomRowIndexTest {

    private val places = Places(
        floors = listOf(Floor("main", "Main", 0), Floor("up", "Upstairs", 1)),
        rooms = listOf(Room("kit", "Kitchen", "main", 0), Room("bed", "Bedroom", "up", 0), Room("gar", "Garage", null, 0)),
    )

    private fun task(id: String, room: String? = null, floor: String? = null) =
        Task(id = id, title = id, type = "one_time", priority = "medium", status = "pending",
            room = room?.let { PlaceRef(it, it) }, floor = floor?.let { PlaceRef(it, it) })

    // Rows: 0 header | 1 Main | 2 Kitchen | 3 dishes | 4 wipe | 5 Upstairs | 6 vacuum | 7 Bedroom | 8 sheets | 9 Other rooms | 10 Garage
    private val groups = groupByPlace(
        listOf(
            task("dishes", room = "kit"), task("wipe", room = "kit"),
            task("vacuum", floor = "up"), task("sheets", room = "bed"), task("oil", room = "gar"),
        ),
        places,
    )

    @Test
    fun pointsAtTheRoomHeading() {
        assertEquals(2, roomRowIndex(groups, emptySet(), "kit", leading = 1))
        assertEquals(7, roomRowIndex(groups, emptySet(), "bed", leading = 1))
        assertEquals(10, roomRowIndex(groups, emptySet(), "gar", leading = 1))
    }

    @Test
    fun foldedRoomsAboveOnlyCountTheirHeading() {
        // 0 header | 1 Main | 2 Kitchen (folded) | 3 Upstairs | 4 vacuum | 5 Bedroom
        assertEquals(5, roomRowIndex(groups, setOf("room:kit"), "bed", leading = 1))
        // A folded floor hides its rooms: 1 Main (folded) | 2 Upstairs | 3 vacuum | 4 Bedroom
        assertEquals(4, roomRowIndex(groups, setOf("floor:main"), "bed", leading = 1))
    }

    @Test
    fun aRoomWithNothingOpenHasNoRow() {
        assertNull(roomRowIndex(groupByPlace(listOf(task("dishes", room = "kit")), places), emptySet(), "bed", leading = 1))
    }
}
