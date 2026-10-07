package com.eosoclub.ourhome.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import com.eosoclub.ourhome.R

// Lock-screen rules for every notification this app posts:
//  - On a secure (PIN/pattern/biometric) lock screen only [publicTitle] shows;
//    names, amounts and bug text appear once the phone is unlocked.
//  - Tapping a notification opens MainActivity, which never uses
//    showWhenLocked / turnScreenOn / requestDismissKeyguard, so Android always
//    asks for the PIN first. Don't add those flags anywhere.
//  - Any future action button that changes data must use
//    NotificationCompat.Action.Builder#setAuthenticationRequired(true) so it
//    can't run from a locked screen.

/** Hides the details of this notification on a secure lock screen. */
fun NotificationCompat.Builder.lockScreenSafe(
    context: Context,
    channelId: String,
    publicTitle: String,
): NotificationCompat.Builder = setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .setPublicVersion(
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_request)
            .setContentTitle(publicTitle)
            .setContentText("Unlock to see details")
            .build(),
    )
