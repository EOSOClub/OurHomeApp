package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Floors and rooms: where tasks are done and stock is kept, and the order
// they're shown in. Mirror of the web's lib/places.ts (+ PlacesTest, same
// cases as places.test.ts) - change both together. Set up in Settings → Rooms
// & floors (here or on the website), shown while Tasks or Inventory is on.

@Serializable
data class PlaceRef(val id: String, val name: String)

/** One of a task's places: a room (with its floor), or a whole floor (no room). */
@Serializable
data class TaskPlace(val room: PlaceRef? = null, val floor: PlaceRef? = null)

/** Something kept in a place: a task, or an inventory item (no hand-set position). */
interface Placed {
    /** One room, or null. For a task in several places, its first. */
    val room: PlaceRef?
    /** The whole floor, or its room's floor. */
    val floor: PlaceRef?
    /**
     * Every place, for things that can be in several (tasks). Null = just
     * [room] / [floor] (inventory items, or a server from before several places).
     */
    val places: List<TaskPlace>? get() = null
    /** Hand-set order within its place; null = after the ordered ones, as given. */
    val position: Int?
}

/** Its places, or its one place ([] = the whole house). */
private fun Placed.placeList(): List<TaskPlace> =
    places ?: listOfNotNull(TaskPlace(room, floor).takeIf { room != null || floor != null })

@Serializable
data class Floor(val id: String, val name: String, val position: Int = 0)

@Serializable
data class Room(val id: String, val name: String, val floorId: String? = null, val position: Int = 0)

/** GET /api/places - each list already in the household's order. */
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

    /**
     * The task Location picker, which can pick several: per floor its name
     * and its options (the whole floor, then its rooms), then rooms on no
     * floor. Values as in [choices]; nothing picked = the whole house.
     */
    fun choiceGroups(): List<Pair<String, List<Pair<String, String>>>> {
        val floorIds = floors.map { it.id }.toSet()
        val byFloor = floors.sortedWith(floorOrder).map { f ->
            f.name to listOf("floor:${f.id}" to "All of ${f.name}") +
                rooms.filter { it.floorId == f.id }.sortedWith(roomOrder).map { "room:${it.id}" to it.name }
        }
        val loose = rooms.filter { it.floorId == null || it.floorId !in floorIds }.sortedWith(roomOrder)
        return byFloor + listOfNotNull(
            loose.takeIf { it.isNotEmpty() }?.let { list ->
                (if (floors.isEmpty()) "Rooms" else "Other rooms") to list.map { "room:${it.id}" to it.name }
            },
        )
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

/** Every place of a task as picker values ("room:<id>" / "floor:<id>"); [] = the whole house. */
fun Placed.placeChoices(): List<String> =
    placeList().mapNotNull { p -> p.room?.let { "room:${it.id}" } ?: p.floor?.let { "floor:${it.id}" } }

private fun label(room: PlaceRef?, floor: PlaceRef?): String? = when {
    room != null -> floor?.let { "${it.name} · ${room.name}" } ?: room.name
    else -> floor?.name
}

/** "Upstairs · Bedroom", "Upstairs", "Garage", or null for none (the whole house). */
fun Placed.placeLabel(): String? = label(room, floor)

/**
 * Every place as one label. One place reads as [placeLabel]; several share
 * their floor: "Ground floor · Kitchen, Laundry; Upstairs".
 */
fun Placed.placesLabel(): String? {
    val list = placeList()
    if (list.size <= 1) return list.firstOrNull()?.let { label(it.room, it.floor) }
    // Per floor (null = rooms on no floor), in the order they come.
    val parts = linkedMapOf<String?, Pair<Boolean, MutableList<String>>>()
    for (p in list) {
        val key = p.floor?.name
        val (whole, rooms) = parts[key] ?: (false to mutableListOf())
        p.room?.let { rooms += it.name }
        parts[key] = (whole || p.room == null) to rooms
    }
    return parts.flatMap { (floor, part) ->
        val (whole, rooms) = part
        listOfNotNull(
            floor?.takeIf { whole },
            rooms.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { r -> floor?.let { "$it · $r" } ?: r },
        )
    }.takeIf { it.isNotEmpty() }?.joinToString("; ")
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
 * first, then rooms on no floor, then whole-house ones. A task in several
 * places is listed under each of them. Empty places are left out; ones none
 * of whose places still exist count as whole-house.
 */
fun <T : Placed> groupByPlace(tasks: List<T>, places: Places): PlaceGroups<T> {
    val roomIds = places.rooms.map { it.id }.toSet()
    val floorIds = places.floors.map { it.id }.toSet()
    val inRoom = mutableMapOf<String, MutableList<T>>()
    val onFloor = mutableMapOf<String, MutableList<T>>()
    val house = mutableListOf<T>()
    fun MutableMap<String, MutableList<T>>.add(id: String, t: T) {
        val list = getOrPut(id) { mutableListOf() }
        if (t !in list) list += t
    }
    for (t in orderTasks(tasks)) {
        var placed = false
        for (p in t.placeList()) {
            val room = p.room
            val floor = p.floor
            when {
                room != null && room.id in roomIds -> { inRoom.add(room.id, t); placed = true }
                room == null && floor != null && floor.id in floorIds -> { onFloor.add(floor.id, t); placed = true }
            }
        }
        if (!placed) house += t
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
