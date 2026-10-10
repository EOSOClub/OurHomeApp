package com.eosoclub.ourhome.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.MainActivity
import com.eosoclub.ourhome.nfc.ScanHandoff
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.InventoryItem
import com.eosoclub.ourhome.nfc.parseScanAmount
import com.eosoclub.ourhome.ui.formatQuantity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The notification for a tag set to "Quick notification": scanning it (app
 * closed) shows the item with −1 / type an amount / add to shopping list —
 * no app opens. Every action requires the device to be unlocked
 * (setAuthenticationRequired), and the lock screen shows no item details.
 */
object QuickScanNotifier {
    private const val CHANNEL_ID = "tag_scans"
    private const val TAG = "QuickScan"
    const val ACTION_USE_ONE = "com.eosoclub.ourhome.QUICK_USE_ONE"
    const val ACTION_AMOUNT = "com.eosoclub.ourhome.QUICK_AMOUNT"
    const val ACTION_ADD_TO_LIST = "com.eosoclub.ourhome.QUICK_ADD_TO_LIST"
    const val EXTRA_TAG_ID = "tag_id"
    const val EXTRA_ITEM_NAME = "item_name"
    const val KEY_AMOUNT = "amount"

    /** One notification per tag, so re-scanning replaces it. */
    private fun notificationId(tagId: String) = 2000 + (tagId.hashCode() and 0xFFFF)

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            // High importance: you just scanned, so it pops up immediately.
            NotificationChannel(CHANNEL_ID, "Tag scans", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Quick actions after scanning an inventory tag"
            },
        )
    }

    fun show(context: Context, tagId: String, item: InventoryItem) {
        if (!RequestReminders.canNotify(context)) {
            Log.w(TAG, "notifications are off; can't show quick scan for $tagId")
            return
        }
        ensureChannel(context)
        val unit = item.unit?.let { " $it" }.orEmpty()
        val builder = base(context, tagId)
            .setContentTitle(item.name)
            .setContentText("${formatQuantity(item.quantity)}$unit on hand${if (item.isLow) " · running low" else ""}")
            .addAction(action(context, tagId, item.name, ACTION_USE_ONE, "−1"))
            .addAction(amountAction(context, tagId, item.name))
            .addAction(action(context, tagId, item.name, ACTION_ADD_TO_LIST, "Add to list"))
        notify(context, tagId, builder)
    }

    /** Replaces the scan notification with what happened (silently), then times out. */
    fun showResult(context: Context, tagId: String, itemName: String, text: String, offerAmount: Boolean = false) {
        ensureChannel(context)
        val builder = base(context, tagId)
            .setContentTitle(itemName)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(15_000)
            .addAction(action(context, tagId, itemName, ACTION_ADD_TO_LIST, "Add to list"))
        if (offerAmount) builder.addAction(amountAction(context, tagId, itemName))
        notify(context, tagId, builder)
    }

    private fun base(context: Context, tagId: String): NotificationCompat.Builder {
        // Tapping opens the scan sheet (PIN first if locked — no lock bypass).
        val open = PendingIntent.getActivity(
            context,
            notificationId(tagId),
            ScanHandoff.stamp(context, Intent(context, MainActivity::class.java))
                .putExtra(MainActivity.EXTRA_SCANNED_TAG, tagId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_inventory)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .lockScreenSafe(context, CHANNEL_ID, "Tag scanned")
    }

    private fun actionIntent(context: Context, tagId: String, itemName: String, action: String, mutable: Boolean) =
        PendingIntent.getBroadcast(
            context,
            (action + tagId).hashCode(),
            Intent(context, QuickScanReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TAG_ID, tagId)
                .putExtra(EXTRA_ITEM_NAME, itemName),
            // RemoteInput needs a mutable PendingIntent to receive the typed text.
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(context: Context, tagId: String, itemName: String, action: String, title: String) =
        NotificationCompat.Action.Builder(0, title, actionIntent(context, tagId, itemName, action, mutable = false))
            .setAuthenticationRequired(true) // never from a locked screen
            .build()

    private fun amountAction(context: Context, tagId: String, itemName: String) =
        NotificationCompat.Action.Builder(0, "Amount…", actionIntent(context, tagId, itemName, ACTION_AMOUNT, mutable = true))
            .addRemoteInput(RemoteInput.Builder(KEY_AMOUNT).setLabel("−2 used · 3 restocked").build())
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .build()

    private fun notify(context: Context, tagId: String, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(notificationId(tagId), builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }
}

/** Runs the quick-scan notification's actions against the site, in the background. */
class QuickScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tagId = intent.getStringExtra(QuickScanNotifier.EXTRA_TAG_ID) ?: return
        val itemName = intent.getStringExtra(QuickScanNotifier.EXTRA_ITEM_NAME) ?: "Item"
        val api = (context.applicationContext as OurHomeApp).session.api
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    QuickScanNotifier.ACTION_USE_ONE -> applyAmount(context, api, tagId, itemName, -1.0)
                    QuickScanNotifier.ACTION_AMOUNT -> {
                        val typed = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(QuickScanNotifier.KEY_AMOUNT)?.toString()
                        val amount = parseScanAmount(typed)
                        if (amount == null) {
                            QuickScanNotifier.showResult(
                                context, tagId, itemName,
                                "Couldn't read “${typed.orEmpty()}” — type e.g. -1 (used) or 3 (restocked).",
                                offerAmount = true,
                            )
                        } else {
                            applyAmount(context, api, tagId, itemName, amount)
                        }
                    }
                    QuickScanNotifier.ACTION_ADD_TO_LIST -> {
                        val r = api.nfcAddToShopping(tagId)
                        QuickScanNotifier.showResult(
                            context, tagId, r.itemName,
                            if (r.added) "Added to ${r.listName}" else "Already on ${r.listName}",
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("QuickScan", "action ${intent.action} failed for $tagId", e)
                QuickScanNotifier.showResult(context, tagId, itemName, e.message ?: "That didn't work — try again.")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun applyAmount(
        context: Context,
        api: com.eosoclub.ourhome.data.ApiClient,
        tagId: String,
        itemName: String,
        amount: Double,
    ) {
        val item = api.nfcScan(tagId, amount)
        val unit = item.unit?.let { " $it" }.orEmpty()
        val verb = if (amount < 0) "Used ${formatQuantity(-amount)}" else "Restocked ${formatQuantity(amount)}"
        QuickScanNotifier.showResult(
            context, tagId, item.name,
            "$verb · now ${formatQuantity(item.quantity)}$unit${if (item.isLow) " · running low" else ""}",
        )
    }
}
