package com.eosoclub.ourhome.nfc

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One physical scan; [at] distinguishes re-scanning the same tag. */
data class ScannedTag(val ref: TagRef, val at: Long = System.currentTimeMillis())

enum class NfcStatus { Unsupported, Disabled, Ready }

/** Outcome of writing a tag; [at] distinguishes repeated attempts. */
data class WriteResult(val tagId: String, val ok: Boolean, val message: String, val at: Long = System.currentTimeMillis())

/**
 * NFC plumbing shared by MainActivity (reader mode while the app is open),
 * QuickScanActivity (tags scanned with the app closed) and the UI.
 *
 * While a write is armed ([armWrite]) the next tag in reader mode is written
 * with this app's URI instead of being scanned.
 */
object NfcScans {
    private const val TAG = "NfcScans"
    private val _latest = MutableStateFlow<ScannedTag?>(null)
    val latest: StateFlow<ScannedTag?> = _latest.asStateFlow()

    private val _writeResult = MutableStateFlow<WriteResult?>(null)
    val writeResult: StateFlow<WriteResult?> = _writeResult.asStateFlow()

    @Volatile private var pendingWrite: Pair<String, String>? = null // tagId to packageName

    fun consume() {
        _latest.value = null
    }

    fun status(context: Context): NfcStatus {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcStatus.Unsupported
        return if (adapter.isEnabled) NfcStatus.Ready else NfcStatus.Disabled
    }

    /** The next tag held to the phone (app open) is written for [tagId]. */
    fun armWrite(tagId: String, packageName: String) {
        _writeResult.value = null
        pendingWrite = tagId to packageName
    }

    fun cancelWrite() {
        pendingWrite = null
    }

    /** Reader-mode callback (binder thread). */
    fun onTag(tag: Tag) {
        val write = pendingWrite
        if (write != null) {
            pendingWrite = null
            _writeResult.value = TagWriter.write(tag, write.first, write.second)
            return
        }
        val ndef = runCatching { Ndef.get(tag)?.cachedNdefMessage }.getOrNull()
        emit(refFrom(ndef?.let { arrayOf(it) }, tag))
    }

    /** Hands a scan read elsewhere (e.g. QuickScanActivity) to the UI. */
    fun emit(ref: TagRef?) {
        if (ref == null) {
            Log.w(TAG, "scanned a tag with no usable id")
            return
        }
        Log.i(TAG, "scanned ${ref.tagId} (${ref.format})")
        _latest.value = ScannedTag(ref)
    }

    /** Reads the tag behind an NFC dispatch intent, or null if it isn't one. */
    fun readIntent(intent: Intent?): TagRef? {
        val action = intent?.action ?: return null
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED && action != NfcAdapter.ACTION_TECH_DISCOVERED) return null
        @Suppress("DEPRECATION")
        val messages = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()?.toTypedArray()
        }
        @Suppress("DEPRECATION")
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        // The dispatched URI is also on the intent's data (handy for testing).
        return refFrom(messages, tag) ?: intent.dataString?.let(::tagRefFromUri)
    }

    private fun refFrom(messages: Array<NdefMessage>?, tag: Tag?): TagRef? =
        messages.orEmpty()
            .flatMap { it.records.asList() }
            .firstNotNullOfOrNull { record -> record.toUri()?.toString()?.let(::tagRefFromUri) }
            ?: tag?.id?.takeIf { it.isNotEmpty() }?.let { TagRef(uidTagId(it), TagFormat.Uid) }
}

/** Writes "ourhome://tag/<id>" + an Android Application Record to a tag. */
private object TagWriter {
    fun write(tag: Tag, tagId: String, packageName: String): WriteResult {
        val message = NdefMessage(
            arrayOf(
                NdefRecord.createUri(ourTagUri(tagId)),
                // Makes Android open Our Home for this tag, no app chooser.
                NdefRecord.createApplicationRecord(packageName),
            ),
        )
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                ndef.use {
                    if (!it.isWritable) return fail(tagId, "This tag is locked (read-only).")
                    if (it.maxSize < message.byteArrayLength) return fail(tagId, "This tag is too small (${it.maxSize} bytes).")
                    it.writeNdefMessage(message)
                }
            } else {
                val formatable = NdefFormatable.get(tag) ?: return fail(tagId, "This tag type can't be written.")
                formatable.connect()
                formatable.use { it.format(message) }
            }
            Log.i("TagWriter", "wrote ${ourTagUri(tagId)}")
            WriteResult(tagId, ok = true, message = "Tag written — it now opens Our Home.")
        } catch (e: Exception) {
            Log.w("TagWriter", "write failed", e)
            fail(tagId, "Couldn't write — hold the tag still against the phone and try again.")
        }
    }

    private fun fail(tagId: String, message: String) = WriteResult(tagId, ok = false, message = message)
}
