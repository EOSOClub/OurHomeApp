package com.eosoclub.ourhome.nfc

/**
 * Parses an amount typed into the quick-scan notification. Same convention as
 * the Home Assistant prompt it replaces: negative = used, positive (or no
 * sign) = restocked. Accepts "−" (Unicode minus), "+", and a decimal comma.
 * Returns null for blank, zero, or unreadable input.
 */
fun parseScanAmount(text: String?): Double? {
    val cleaned = text?.trim()?.replace('−', '-')?.replace(',', '.')?.replace(" ", "") ?: return null
    if (cleaned.isEmpty()) return null
    return cleaned.toDoubleOrNull()?.takeIf { it != 0.0 && it.isFinite() }
}
