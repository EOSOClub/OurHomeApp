package com.eosoclub.ourhome.notifications

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A supervising adult ringing this phone (web supervisionService.ringPhone).
 * The server's high-priority push starts [RingService], which plays the alarm
 * sound on the alarm stream at full volume, so it rings even on silent, and
 * vibrates, for [RING_MS] or until Stop. The volume goes back afterward.
 *
 * Stop works from the lock screen: it only quiets the phone (and says so to
 * the adult who rang); it changes no household data. Who is ringing shows once
 * the phone is unlocked (the push carries only
 * their id; the name comes from the server).
 *
 * The phone tells the server it's ringing, then that Stop was tapped or that
 * it rang out ([report]), so the adult who rang sees how it went. Pushes from
 * servers before that carry no ring id, and nothing is sent.
 */
object Ring {
    private const val TAG = "Ring"
    internal const val CHANNEL_ID = "ring"
    internal const val NOTIFICATION_ID = 1009
    internal const val ACTION_START = "com.eosoclub.ourhome.RING_START"
    internal const val ACTION_STOP = "com.eosoclub.ourhome.RING_STOP"
    internal const val EXTRA_RINGER_ID = "ringerId"
    internal const val EXTRA_RING_ID = "ringId"
    internal const val RING_MS = 30_000L

    /** Outlives [RingService], which stops as soon as the ring ends. */
    private val reports = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, ringerId: String?, ringId: String?) {
        val intent = Intent(context, RingService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RINGER_ID, ringerId)
            .putExtra(EXTRA_RING_ID, ringId)
        try {
            context.startForegroundService(intent)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // The push arrived at normal priority (Android lowered it): no way to start.
            Log.w(TAG, "Android didn't allow ringing from the background", e)
        }
    }

    /**
     * Tells the server what this phone did with ring [ringId]: "ringing", then
     * "stopped" (Stop tapped) or "rang_out". Once, the best effort: the adult who
     * rang sees "it rang on their phone" if the ending never arrives.
     */
    internal fun report(context: Context, ringId: String?, state: String) {
        if (ringId == null) return
        val api = (context.applicationContext as OurHomeApp).session.api
        reports.launch {
            runCatching { api.reportRing(ringId, state) }
                .onFailure { Log.w(TAG, "couldn't tell the server the ring is $state", it) }
        }
    }

    internal fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ringing your phone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When an adult who looks after you rings your phone"
                // The service plays the sound itself, on the alarm stream.
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}

class RingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timer: Job? = null
    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null

    /** The ring playing now, to say how it ended; null once said (or from an older server). */
    private var ringId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Ring.ACTION_START -> {
                if (!enterForeground(notification(null))) return START_NOT_STICKY
                startSound()
                // A second ring while ringing starts the time again, and is the one reported.
                ringId = intent.getStringExtra(Ring.EXTRA_RING_ID)
                Ring.report(this, ringId, "ringing")
                timer?.cancel()
                timer = scope.launch {
                    intent.getStringExtra(Ring.EXTRA_RINGER_ID)?.let { id ->
                        val name = runCatching {
                            (applicationContext as OurHomeApp).session.api.householdMembers().firstOrNull { it.id == id }?.name
                        }.getOrNull()
                        if (name != null) show(notification(name))
                    }
                    delay(Ring.RING_MS)
                    ended("rang_out")
                }
            }
            // Someone tapped Stop.
            Ring.ACTION_STOP -> ended("stopped")
            else -> finish()
        }
        // Not restarted by Android after being killed: a ring is only for now.
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Ring.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        true
    } catch (e: Exception) {
        Log.w("Ring", "couldn't show the ring notification; not ringing", e)
        stopSelf()
        false
    }

    private fun startSound() {
        if (player != null) return
        val audio = getSystemService(AudioManager::class.java)
        // Full alarm volume while ringing; put back in [stopSound].
        try {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: SecurityException) {
            // Do Not Disturb settings can refuse it: ring at the volume set.
            savedAlarmVolume = null
            Log.w("Ring", "couldn't raise the alarm volume", e)
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@RingService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w("Ring", "couldn't play the alarm sound", e)
            null
        }
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator?.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun stopSound() {
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        savedAlarmVolume?.let { volume ->
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        savedAlarmVolume = null
    }

    /** The ring ended as [state] says ("stopped" / "rang_out"): tell the server, then go quiet. */
    private fun ended(state: String) {
        Ring.report(this, ringId, state)
        ringId = null
        finish()
    }

    private fun finish() {
        timer?.cancel()
        timer = null
        stopSound()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        scope.cancel()
        super.onDestroy()
    }

    private fun show(notification: Notification) {
        if (!RequestReminders.canNotify(this)) return
        try {
            NotificationManagerCompat.from(this).notify(Ring.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("Ring", "notification permission revoked", e)
        }
    }

    /** [ringerName] null: before the server has said who it is. */
    private fun notification(ringerName: String?): Notification {
        Ring.ensureChannel(this)
        val stop = PendingIntent.getService(
            this,
            10,
            Intent(this, RingService::class.java).setAction(Ring.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Quieting the phone changes no data, so Stop works without unlocking.
        val stopAction = NotificationCompat.Action.Builder(R.drawable.ic_ring, "Stop", stop).build()
        val base = { title: String, text: String ->
            NotificationCompat.Builder(this, Ring.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ring)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(stopAction)
        }
        val title = ringerName?.let { "$it is ringing your phone" } ?: "Your phone is being rung"
        // The lock screen gets the same Stop, without the name.
        return base(title, "Tap Stop when you've seen it.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base("Your phone is being rung", "Tap Stop when you've seen it.").build())
            .build()
    }
}
