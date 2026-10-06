package com.eosoclub.ourhome.nfc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.notifications.QuickScanNotifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible handler for tags scanned while the app isn't open (Our Home tags,
 * HA tags, and blank tags offered to the app). It looks the tag up, then either
 * posts the quick-scan notification (tag set to "notify") or opens the app on
 * the scan sheet — and finishes immediately. No UI of its own.
 *
 * Lock screen: like every activity here it never shows over the keyguard, so a
 * locked phone always asks for the PIN first. Don't add showWhenLocked etc.
 */
class QuickScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ref = NfcScans.readIntent(intent)
        if (ref == null) {
            finish()
            return
        }
        val api = (application as OurHomeApp).session.api
        lifecycleScope.launch {
            // Bounded: a slow network falls back to opening the app.
            val lookup = withTimeoutOrNull(5_000) { runCatching { api.nfcLookup(ref.tagId) }.getOrNull() }
            val item = lookup?.item
            if (lookup?.tag?.scanAction == "notify" && item != null) {
                Log.i("QuickScan", "${ref.tagId} → quick notification")
                QuickScanNotifier.show(applicationContext, ref.tagId, item)
            } else {
                Log.i("QuickScan", "${ref.tagId} → open app")
                startActivity(
                    Intent(this@QuickScanActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SCANNED_TAG, ref.tagId)
                        .putExtra(MainActivity.EXTRA_SCANNED_FORMAT, ref.format.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
            finish()
        }
    }
}
