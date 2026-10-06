package com.eosoclub.ourhome.nfc

// How a physical tag maps to the website's `tagId` (pure, so it's unit-tested).
//
// - Tags written by this app hold "ourhome://tag/<id>" (+ an Android
//   Application Record), so scanning one always opens Our Home — no chooser.
// - Tags written by the Home Assistant companion app hold
//   "https://www.home-assistant.io/tag/<id>". Reading that <id> keeps every
//   tag already mapped for HA working; rewriting keeps the same id.
// - Any other tag (blank, or unknown content) is identified by its hardware
//   UID as "uid:<hex>".

const val OUR_TAG_SCHEME = "ourhome"

/** Where a scanned tag's id came from — decides whether to offer rewriting. */
enum class TagFormat { OurHome, HomeAssistant, Uid }

data class TagRef(val tagId: String, val format: TagFormat)

private val OUR_TAG_URL = Regex("^ourhome://tag/([^/?#]+)", RegexOption.IGNORE_CASE)
private val HA_TAG_URL = Regex("^https?://(?:www\\.)?home-assistant\\.io/tag/([^/?#]+)", RegexOption.IGNORE_CASE)

/** The tag id inside an Our Home or HA tag URL, or null if [uri] is neither. */
fun tagRefFromUri(uri: String): TagRef? {
    val trimmed = uri.trim()
    OUR_TAG_URL.find(trimmed)?.let { return TagRef(it.groupValues[1], TagFormat.OurHome) }
    HA_TAG_URL.find(trimmed)?.let { return TagRef(it.groupValues[1], TagFormat.HomeAssistant) }
    return null
}

/** The URI this app writes to a tag. */
fun ourTagUri(tagId: String): String = "$OUR_TAG_SCHEME://tag/$tagId"

/** Stable id for a tag without a recognised record, from its hardware UID. */
fun uidTagId(uid: ByteArray): String = "uid:" + uid.joinToString("") { "%02x".format(it) }
