package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// What's wrong with a value typed in a member form, in the same words as the
// server (web lib/validation/user.ts), so a field can say why it's red before
// anything is sent. null: fine, or still empty (a blank field isn't marked
// until it's needed). The server checks again and has the last word.

fun usernameProblem(value: String): String? {
    val v = value.trim()
    return when {
        value.isEmpty() -> null
        v.length < 3 -> "At least 3 characters."
        v.length > 30 -> "At most 30 characters."
        !Regex("^[a-zA-Z0-9._-]+$").matches(v) -> "Use only letters, numbers and . _ - (no spaces)."
        else -> null
    }
}

fun emailProblem(value: String): String? {
    val v = value.trim()
    return when {
        v.isEmpty() -> null
        !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(v) -> "Enter a valid email address, like name@example.com."
        else -> null
    }
}

fun passwordProblem(value: String): String? = when {
    value.isEmpty() -> null
    value.length < 8 -> "At least 8 characters."
    value.length > 128 -> "At most 128 characters."
    else -> null
}

/** GET /api/supervision (Head of House only): who may supervise, and each child or teen with their supervisors. */
@Serializable
data class Supervision(
    val supervisors: List<SupervisionPerson> = emptyList(),
    val supervised: List<SupervisedPerson> = emptyList(),
) {
    /** "Supervised by Alex, Sam", or a nudge when nobody is. */
    fun supervisedBy(kidId: String): String? {
        val kid = supervised.firstOrNull { it.id == kidId } ?: return null
        val names = supervisors.filter { it.id in kid.supervisorIds }.map { it.name }
        return if (names.isEmpty()) "Nobody supervises them yet" else "Supervised by ${names.joinToString(", ")}"
    }
}

@Serializable
data class SupervisionPerson(val id: String, val name: String, val role: String = "")

@Serializable
data class SupervisedPerson(
    val id: String,
    val name: String,
    val role: String = "",
    val supervisorIds: List<String> = emptyList(),
)
