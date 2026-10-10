package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as the web's src/lib/places.test.ts. */
class PlacesTest {

    private val places = Places(
        floors = listOf(Floor("up", "Upstairs", 1), Floor("main", "Main", 0)),
        rooms = listOf(
            Room("bath", "Bathroom", "up", 1),
            Room("bed", "Bedroom", "up", 0),
            Room("kit", "Kitchen", "main", 0),
            Room("gar", "Garage", null, 0),
        ),
    )

    private fun task(id: String, room: String? = null, floor: String? = null, position: Int? = null) = Task(
        id = id,
        title = id,
        type = "one_time",
        priority = "medium",
        status = "pending",
        room = room?.let { PlaceRef(it, it) },
        floor = floor?.let { PlaceRef(it, it) },
        position = position,
    )

    private fun ids(list: List<Task>) = list.map { it.id }

    @Test
    fun handOrderedTasksComeFirst() {
        val list = listOf(task("a"), task("b", position = 1), task("c"), task("d", position = 0))
        assertEquals(listOf("d", "b", "a", "c"), ids(orderTasks(list)))
    }

    private val groups = groupByPlace(
        listOf(
            task("vacuum", floor = "up"),
            task("sheets", room = "bed", floor = "up"),
            task("scrub", room = "bath", floor = "up"),
            task("dishes", room = "kit", floor = "main"),
            task("oil", room = "gar"),
            task("bins"),
            task("ghost", room = "deleted-room"),
        ),
        places,
    )

    @Test
    fun floorsInOrderThenRoomsOnNoFloor() {
        assertEquals(listOf("main", "up", null), groups.floors.map { it.floor?.id })
    }

    @Test
    fun roomsInOrderAndWholeFloorTasksStayOnTheFloor() {
        val up = groups.floors[1]
        assertEquals(listOf("vacuum"), ids(up.tasks))
        assertEquals(listOf("bed", "bath"), up.rooms.map { it.room.id })
    }

    @Test
    fun unplacedAndDeletedPlacesGoToTheWholeHouse() {
        assertEquals(listOf("bins", "ghost"), ids(groups.house))
    }

    @Test
    fun emptyPlacesAreLeftOut() {
        val only = groupByPlace(listOf(task("dishes", room = "kit")), places)
        assertEquals(1, only.floors.size)
        assertEquals(listOf("kit"), only.floors[0].rooms.map { it.room.id })
    }

    @Test
    fun moveSwapsWithTheNeighbourAndStopsAtTheEnds() {
        assertEquals(listOf("b", "a", "c"), moveId(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "b", "c"), moveId(listOf("a", "b", "c"), "c", 1))
    }

    @Test
    fun labelNamesFloorAndRoom() {
        assertEquals("Upstairs · Bedroom", task("t").copy(room = PlaceRef("b", "Bedroom"), floor = PlaceRef("u", "Upstairs")).placeLabel())
        assertEquals("Garage", task("t").copy(room = PlaceRef("g", "Garage")).placeLabel())
        assertEquals("Upstairs", task("t").copy(floor = PlaceRef("u", "Upstairs")).placeLabel())
        assertNull(task("t").placeLabel())
    }

    @Test
    fun pickerListsFloorsThenTheirRoomsThenLooseRooms() {
        assertEquals(
            listOf("", "floor:main", "room:kit", "floor:up", "room:bed", "room:bath", "room:gar"),
            places.choices().map { it.first },
        )
    }
}
