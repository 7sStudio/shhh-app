package io.github.shhhapp.shhh.audio

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.notify.CountdownNotifier
import org.robolectric.Shadows.shadowOf

/**
 * Puts a notification in the headphones offer's slot, standing in for the one
 * [HeadsetReceiver] posts when Android drops its direct volume write. The
 * real post (text, tap target, auto-cancel) is pinned in HeadsetReceiverTest;
 * everything else only cares whether the slot gets cleared.
 */
fun postStandInHeadphonesOffer(context: Context) {
    CountdownNotifier.ensureChannels(context)
    NotificationManagerCompat.from(context).notify(
        HeadsetReceiver.NOTIFICATION_ID,
        NotificationCompat.Builder(context, CountdownNotifier.CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_volume_up)
            .build()
    )
    check(isHeadphonesOfferShown(context)) { "the stand-in offer was not posted" }
}

/** True while something sits in the headphones offer's notification slot. */
fun isHeadphonesOfferShown(context: Context): Boolean {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return shadowOf(manager).getNotification(HeadsetReceiver.NOTIFICATION_ID) != null
}
