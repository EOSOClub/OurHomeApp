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

    private fun ids(list: List<Task>): List<String> = list.map { it.id }

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
    fun aTaskInSeveralPlacesIsListedUnderEach() {
        val mop = task("mop", room = "kit", floor = "main").copy(
            places = listOf(
                TaskPlace(PlaceRef("kit", "Kitchen"), PlaceRef("main", "Main")),
                TaskPlace(PlaceRef("bath", "Bathroom"), PlaceRef("up", "Upstairs")),
                TaskPlace(null, PlaceRef("up", "Upstairs")),
                TaskPlace(PlaceRef("deleted-room", "Gone"), null),
            ),
        )
        val g = groupByPlace(listOf(mop, task("dishes", room = "kit")), places)
        val (main, up) = g.floors
        assertEquals(listOf("mop", "dishes"), ids(main.rooms[0].tasks))
        assertEquals(listOf("mop"), ids(up.tasks))
        assertEquals(listOf("mop"), ids(up.rooms[0].tasks))
        assertEquals(emptyList<String>(), ids(g.house))
    }

    @Test
    fun aTaskNoneOfWhosePlacesExistGoesToTheWholeHouse() {
        val lost = task("lost").copy(places = listOf(TaskPlace(PlaceRef("gone", "Gone"), null)))
        assertEquals(listOf("lost"), ids(groupByPlace(listOf(lost), places).house))
    }

    @Test
    fun severalPlacesShareTheirFloorInTheLabel() {
        val main = PlaceRef("main", "Ground floor")
        val up = PlaceRef("up", "Upstairs")
        val t = task("t").copy(
            places = listOf(
                TaskPlace(PlaceRef("k", "Kitchen"), main),
                TaskPlace(PlaceRef("l", "Laundry"), main),
                TaskPlace(null, up),
                TaskPlace(PlaceRef("g", "Garage"), null),
            ),
        )
        assertEquals("Ground floor · Kitchen, Laundry; Upstairs; Garage", t.placesLabel())
        // One place, none, and a server from before several places.
        assertEquals("Garage", task("t").copy(places = listOf(TaskPlace(PlaceRef("g", "Garage")))).placesLabel())
        assertNull(task("t").copy(places = emptyList()).placesLabel())
        assertEquals("Upstairs · Bedroom", task("t").copy(room = PlaceRef("b", "Bedroom"), floor = up).placesLabel())
    }

    @Test
    fun pickerValuesForEveryPlace() {
        val t = task("t", room = "kit").copy(
            places = listOf(TaskPlace(PlaceRef("kit", "Kitchen")), TaskPlace(null, PlaceRef("up", "Upstairs"))),
        )
        assertEquals(listOf("room:kit", "floor:up"), t.placeChoices())
        // A server from before several places: just its one place.
        assertEquals(listOf("floor:up"), task("t", floor = "up").placeChoices())
        assertEquals(emptyList<String>(), task("t").placeChoices())
    }

    @Test
    fun multiPickerGroupsByFloorThenLooseRooms() {
        assertEquals(
            listOf(
                "Main" to listOf("floor:main", "room:kit"),
                "Upstairs" to listOf("floor:up", "room:bed", "room:bath"),
                "Other rooms" to listOf("room:gar"),
            ),
            places.choiceGroups().map { (title, options) -> title to options.map { it.first } },
        )
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

    @Test
    fun roomOnlyPickerForLocationTags() {
        assertEquals(
            listOf("room:kit", "room:bed", "room:bath", "room:gar"),
            places.choices(roomsOnly = true).map { it.first },
        )
        assertEquals("Not set", places.choices(none = "Not set").first().second)
        assertEquals("Upstairs · Bedroom", places.roomLabel("bed"))
        assertEquals("Garage", places.roomLabel("gar"))
        assertNull(places.roomLabel("gone"))
    }

    @Test
    fun itemsGroupByRoomInTheOrderGiven() {
        fun item(id: String, room: String? = null) =
            InventoryItem(id = id, name = id, room = room?.let { PlaceRef(it, it) })
        val g = groupByPlace(listOf(item("ice", "gar"), item("beans", "kit"), item("salt", "kit"), item("misc")), places)
        assertEquals(listOf("main", null), g.floors.map { it.floor?.id })
        assertEquals(listOf("beans", "salt"), g.floors[0].rooms[0].tasks.map { it.id })
        assertEquals(listOf("misc"), g.house.map { it.id })
    }
}
