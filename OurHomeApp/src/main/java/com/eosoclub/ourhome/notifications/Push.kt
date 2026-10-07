package com.eosoclub.ourhome.notifications

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eosoclub.ourhome.OurHomeApp
import com.eosoclub.ourhome.data.UnauthorizedException
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Instant alerts via Firebase Cloud Messaging.
 *
 * The server's pushes carry no content, just "something changed" (see the web
 * repo's pushService). A push runs the same check as the hourly
 * [RequestReminders.Worker], right away, so every alert is still built here
 * from server data and goes through [lockScreenSafe]. Nothing Android shows on
 * its own: the messages are data-only.
 *
 * Optional: with no google-services.json in the build, Firebase never
 * initialises, [isAvailable] is false, and the hourly check carries on alone.
 */
object Push {
    private const val TAG = "Push"
    private const val REGISTER_WORK = "push-register"

    fun isAvailable(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** Registers this install's token with the server (sign-in, launch, token rotation). */
    fun register(context: Context) {
        if (!isAvailable(context)) return
        val request = OneTimeWorkRequestBuilder<RegisterWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REGISTER_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    class RegisterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val session = (applicationContext as OurHomeApp).session
            return try {
                val token = FirebaseMessaging.getInstance().token.await()
                session.api.registerPushDevice(token)
                session.rememberPushToken(token)
                Log.i(TAG, "registered for instant alerts")
                Result.success()
            } catch (e: UnauthorizedException) {
                Log.i(TAG, "not signed in; not registering")
                Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "registration failed; will retry", e)
                Result.retry()
            }
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val error = task.exception
            if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
        }
    }
}

/** Receives FCM token changes and the server's "sync" pushes. */
class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.register(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "sync") return
        Log.i("Push", "sync push (${message.data["reason"] ?: "unknown"}); checking now")
        RequestReminders.checkNow(applicationContext)
    }
}
