package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Floors and rooms: where tasks are done and stock is kept, and the order
// they're shown in. Mirror of the web's lib/places.ts (+ PlacesTest, same
// cases as places.test.ts) — change both together. Set up in Settings → Rooms
// & floors (here or on the website), shown while Tasks or Inventory is on.

@Serializable
data class PlaceRef(val id: String, val name: String)

/** Something kept in a place: a task, or an inventory item (no hand-set position). */
interface Placed {
    /** One room, or null. */
    val room: PlaceRef?
    /** The whole floor, or its room's floor. */
    val floor: PlaceRef?
    /** Hand-set order within its place; null = after the ordered ones, as given. */
    val position: Int?
}

@Serializable
data class Floor(val id: String, val name: String, val position: Int = 0)

@Serializable
data class Room(val id: String, val name: String, val floorId: String? = null, val position: Int = 0)

/** GET /api/places — each list already in the household's order. */
@Serializable
data class Places(val floors: List<Floor> = emptyList(), val rooms: List<Room> = emptyList()) {
    val isEmpty: Boolean get() = floors.isEmpty() && rooms.isEmpty()

    /**
     * Location picker options as (value, label): "" = [none] (whole house for
     * tasks), "floor:<id>" = a whole floor, "room:<id>" = a room (labelled with
     * its floor). [roomsOnly] leaves out floors and the "" option (NFC location
     * tags open a room).
     */
    fun choices(none: String = "Whole house", roomsOnly: Boolean = false): List<Pair<String, String>> {
        val floorIds = floors.map { it.id }.toSet()
        val byFloor = floors.sortedWith(floorOrder).flatMap { f ->
            (if (roomsOnly) emptyList() else listOf("floor:${f.id}" to "All of ${f.name}")) +
                rooms.filter { it.floorId == f.id }.sortedWith(roomOrder).map { "room:${it.id}" to "${f.name} · ${it.name}" }
        }
        val loose = rooms.filter { it.floorId == null || it.floorId !in floorIds }
            .sortedWith(roomOrder).map { "room:${it.id}" to it.name }
        return (if (roomsOnly) emptyList() else listOf("" to none)) + byFloor + loose
    }

    /** "Upstairs · Bedroom" or "Garage" for a room id; null when it's gone. */
    fun roomLabel(roomId: String): String? {
        val room = rooms.firstOrNull { it.id == roomId } ?: return null
        val floor = floors.firstOrNull { it.id == room.floorId }
        return floor?.let { "${it.name} · ${room.name}" } ?: room.name
    }
}

private val floorOrder = compareBy<Floor>({ it.position }, { it.name })
private val roomOrder = compareBy<Room>({ it.position }, { it.name })

/** A task's or item's place as the picker's value ("room:<id>", "floor:<id>", ""). */
fun Placed.placeChoice(): String = room?.let { "room:${it.id}" } ?: floor?.let { "floor:${it.id}" } ?: ""

/** "Upstairs · Bedroom", "Upstairs", "Garage", or null for none (the whole house). */
fun Placed.placeLabel(): String? {
    val room = room
    return when {
        room != null -> floor?.let { "${it.name} · ${room.name}" } ?: room.name
        else -> floor?.name
    }
}

/**
 * In hand-set order: positioned ones first (by position), then the rest in
 * the order given (the server's due-date order for tasks).
 */
fun <T : Placed> orderTasks(tasks: List<T>): List<T> =
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

data class RoomGroup<T>(val room: Room, val tasks: List<T>)

/** [floor] null = rooms on no floor; [tasks] = its whole-floor tasks (or items). */
data class FloorGroup<T>(val floor: Floor?, val tasks: List<T>, val rooms: List<RoomGroup<T>>)

data class PlaceGroups<T>(val floors: List<FloorGroup<T>>, val house: List<T>)

/**
 * Tasks (or items) grouped Floor → Room in the household's order: floors
 * first, then rooms on no floor, then whole-house ones. Empty places are left
 * out; ones pointing at a deleted place count as whole-house.
 */
fun <T : Placed> groupByPlace(tasks: List<T>, places: Places): PlaceGroups<T> {
    val roomIds = places.rooms.map { it.id }.toSet()
    val floorIds = places.floors.map { it.id }.toSet()
    val inRoom = mutableMapOf<String, MutableList<T>>()
    val onFloor = mutableMapOf<String, MutableList<T>>()
    val house = mutableListOf<T>()
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
