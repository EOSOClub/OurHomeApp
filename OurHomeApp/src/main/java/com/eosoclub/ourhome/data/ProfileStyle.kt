package com.eosoclub.ourhome.data

import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * About-me helpers, mirroring the web's lib/profile.ts (keep the colour keys
 * and hex values in sync with PROFILE_COLORS there).
 */
object ProfileStyle {
    /** Avatar colour key → ARGB. Order is the picker's order. */
    val COLORS: List<Pair<String, Long>> = listOf(
        "slate" to 0xFF64748B,
        "red" to 0xFFEF4444,
        "orange" to 0xFFF97316,
        "amber" to 0xFFF59E0B,
        "green" to 0xFF22C55E,
        "teal" to 0xFF14B8A6,
        "blue" to 0xFF3B82F6,
        "violet" to 0xFF8B5CF6,
        "pink" to 0xFFEC4899,
    )

    const val BIO_MAX = 500
    const val PRONOUNS_MAX = 40

    /** Quick picks; any single emoji can be typed instead. */
    val EMOJI_PICKS = listOf("😀", "😎", "🦊", "🐱", "🐶", "🦄", "🌻", "🌈", "⚽", "🎮", "🎸", "📚", "🍕", "🚀", "⭐", "🏡")

    fun color(key: String?): Long = COLORS.firstOrNull { it.first == key }?.second ?: COLORS[0].second

    /** "Jane van Doe" → "JD"; blank → "?". */
    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val picked = if (parts.size > 1) listOf(parts.first(), parts.last()) else parts
        return picked.joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }.ifEmpty { "?" }
    }

    /** Days in each month of a leap year, so Feb 29 is allowed. */
    private val MONTH_DAYS = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /** "MM-DD" naming a real day of some year. */
    fun isBirthday(value: String): Boolean {
        val m = Regex("^(\\d{2})-(\\d{2})$").find(value) ?: return false
        val month = m.groupValues[1].toInt()
        val day = m.groupValues[2].toInt()
        return month in 1..12 && day in 1..MONTH_DAYS[month - 1]
    }

    /** month 1–12 + day → "MM-DD", or null when not a real day. */
    fun birthday(month: Int?, day: Int?): String? {
        if (month == null || day == null) return null
        val v = "%02d-%02d".format(month, day)
        return v.takeIf(::isBirthday)
    }

    /** "03-14" → "March 14". */
    fun formatBirthday(value: String?, locale: Locale = Locale.getDefault()): String? {
        if (value == null || !isBirthday(value)) return null
        val month = Month.of(value.substring(0, 2).toInt()).getDisplayName(TextStyle.FULL, locale)
        return "$month ${value.substring(3).toInt()}"
    }
}
