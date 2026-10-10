package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Floors and rooms: where tasks are done, and the order they're shown in.
// Mirror of the web's lib/places.ts (+ PlacesTest, same cases as
// places.test.ts) — change both together. Set up on the website (Settings →
// Rooms & floors); the app only reads them.

@Serializable
data class PlaceRef(val id: String, val name: String)

@Serializable
data class Floor(val id: String, val name: String, val position: Int = 0)

@Serializable
data class Room(val id: String, val name: String, val floorId: String? = null, val position: Int = 0)

/** GET /api/places — each list already in the household's order. */
@Serializable
data class Places(val floors: List<Floor> = emptyList(), val rooms: List<Room> = emptyList()) {
    val isEmpty: Boolean get() = floors.isEmpty() && rooms.isEmpty()

    /**
     * Location picker options as (value, label): "" = whole house,
     * "floor:<id>" = a whole floor, "room:<id>" = a room (labelled with its floor).
     */
    fun choices(): List<Pair<String, String>> {
        val floorIds = floors.map { it.id }.toSet()
        val byFloor = floors.sortedWith(floorOrder).flatMap { f ->
            listOf("floor:${f.id}" to "All of ${f.name}") +
                rooms.filter { it.floorId == f.id }.sortedWith(roomOrder).map { "room:${it.id}" to "${f.name} · ${it.name}" }
        }
        val loose = rooms.filter { it.floorId == null || it.floorId !in floorIds }
            .sortedWith(roomOrder).map { "room:${it.id}" to it.name }
        return listOf("" to "Whole house") + byFloor + loose
    }
}

private val floorOrder = compareBy<Floor>({ it.position }, { it.name })
private val roomOrder = compareBy<Room>({ it.position }, { it.name })

/** A task's place as the picker's value ("room:<id>", "floor:<id>", ""). */
fun Task.placeChoice(): String = room?.let { "room:${it.id}" } ?: floor?.let { "floor:${it.id}" } ?: ""

/** "Upstairs · Bedroom", "Upstairs", "Garage", or null for the whole house. */
fun Task.placeLabel(): String? = when {
    room != null -> floor?.let { "${it.name} · ${room.name}" } ?: room.name
    else -> floor?.name
}

/**
 * Tasks in their hand-set order: positioned ones first (by position), then the
 * rest in the order given (the server's due-date order).
 */
fun orderTasks(tasks: List<Task>): List<Task> =
    tasks.withIndex().sortedWith { a, b ->
        val pa = a.value.position
        val pb = b.value.position
        when {
            pa != null && pb != null && pa != pb -> pa - pb
            pa != null && pb == null -> -1
            pa == null && pb != null -> 1
            else -> a.index - b.index
        }
    }.map { it.value }

data class RoomGroup(val room: Room, val tasks: List<Task>)

/** [floor] null = rooms on no floor; [tasks] = its whole-floor tasks. */
data class FloorGroup(val floor: Floor?, val tasks: List<Task>, val rooms: List<RoomGroup>)

data class PlaceGroups(val floors: List<FloorGroup>, val house: List<Task>)

/**
 * Tasks grouped Floor → Room in the household's order: floors first, then
 * rooms on no floor, then whole-house tasks. Empty places are left out;
 * tasks pointing at a deleted place count as whole-house.
 */
fun groupByPlace(tasks: List<Task>, places: Places): PlaceGroups {
    val roomIds = places.rooms.map { it.id }.toSet()
    val floorIds = places.floors.map { it.id }.toSet()
    val inRoom = mutableMapOf<String, MutableList<Task>>()
    val onFloor = mutableMapOf<String, MutableList<Task>>()
    val house = mutableListOf<Task>()
    for (t in orderTasks(tasks)) {
        val room = t.room
        val floor = t.floor
        when {
            room != null && room.id in roomIds -> inRoom.getOrPut(room.id) { mutableListOf() } += t
            room == null && floor != null && floor.id in floorIds -> onFloor.getOrPut(floor.id) { mutableListOf() } += t
            else -> house += t
        }
    }
    fun roomGroups(floorId: String?) = places.rooms
        .filter { (it.floorId?.takeIf { f -> f in floorIds }) == floorId }
        .sortedWith(roomOrder)
        .mapNotNull { r -> inRoom[r.id]?.takeIf { it.isNotEmpty() }?.let { RoomGroup(r, it) } }

    val groups = places.floors.sortedWith(floorOrder).mapNotNull { f ->
        val group = FloorGroup(f, onFloor[f.id].orEmpty(), roomGroups(f.id))
        group.takeIf { it.tasks.isNotEmpty() || it.rooms.isNotEmpty() }
    }.toMutableList()
    val loose = roomGroups(null)
    if (loose.isNotEmpty()) groups += FloorGroup(null, emptyList(), loose)
    return PlaceGroups(groups, house)
}

/** [ids] with [id] moved one step up (-1) or down (+1); unchanged at an end. */
fun moveId(ids: List<String>, id: String, direction: Int): List<String> {
    val from = ids.indexOf(id)
    val to = from + direction
    if (from == -1 || to !in ids.indices) return ids
    return ids.toMutableList().apply { this[from] = ids[to]; this[to] = ids[from] }
}
