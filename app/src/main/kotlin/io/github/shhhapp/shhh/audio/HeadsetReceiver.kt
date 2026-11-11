package io.github.shhhapp.shhh.audio

import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.notify.CountdownNotifier
import io.github.shhhapp.shhh.schedule.HushService

/** The "connection state changed" broadcast of each Bluetooth audio profile. */
private val CONNECTION_ACTIONS = setOf(
    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
    BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED
)

/**
 * Bluetooth headphones connecting. (Wired ACTION_HEADSET_PLUG cannot wake a
 * dead process — it is registerReceiver-only — so this feature covers
 * Bluetooth audio, which IS manifest-registerable. Receiving it requires the
 * user-granted BLUETOOTH_CONNECT permission, requested when the option is
 * turned on.)
 *
 * Earbuds announce themselves on different profiles: hands-free (calls),
 * A2DP (media) and LE Audio. A device may connect on one, two or all three,
 * so all three are listened for; a second announcement for the same earbuds
 * finds media already back and changes nothing.
 *
 * When headphones connect while hushed and the option is on, media volume
 * comes back — headphones bother nobody — while the ringer stays hushed.
 * A receiver is a background context, where Android 16+ audio hardening
 * drops volume writes, so the restore is handed to the momentary
 * [HushService], the same route the tile and the timers use. When Android
 * refuses that service start, the restore is attempted here anyway; and
 * whenever the volume did not come back, a one-tap notification offers it
 * through the visible [ToggleActivity] trampoline, which is always allowed.
 */
class HeadsetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in CONNECTION_ACTIONS) return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        if (state != BluetoothProfile.STATE_CONNECTED) return
        if (!HushManager(context).isHeadphoneRestoreWanted) return

        try {
            context.startForegroundService(
                HushService.intent(context, HushService.ACTION_HEADPHONES_CONNECTED)
            )
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            restoreMediaOrOffer(context)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 3

        /**
         * Brings media back for freshly connected headphones, or offers to
         * when the write did not land. Does nothing unless the option is on
         * and the phone is still hushed — checked again here because the
         * service runs a moment after the broadcast.
         */
        fun restoreMediaOrOffer(context: Context) {
            val manager = HushManager(context)
            if (!manager.isHeadphoneRestoreWanted) return
            if (manager.onHeadphonesConnected()) {
                manager.refreshSurfaces()
            } else {
                postRestoreOffer(context)
            }
        }

        internal fun postRestoreOffer(context: Context) {
            if (!CountdownNotifier.canNotify(context)) return
            CountdownNotifier.ensureChannels(context)

            val restore = PendingIntent.getActivity(
                context,
                2,
                Intent(context, ToggleActivity::class.java)
                    .setAction(ToggleActivity.ACTION_RESTORE_MEDIA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(context, CountdownNotifier.CHANNEL_COUNTDOWN)
                .setSmallIcon(R.drawable.ic_volume_up)
                .setContentTitle(context.getString(R.string.headphones_notification_title))
                .setContentText(context.getString(R.string.headphones_notification_text))
                .setContentIntent(restore)
                .setAutoCancel(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call; nothing to do.
            }
        }

        /**
         * Takes the "tap to bring media volume back" offer down. It is only
         * true while the phone is hushed with media still muted, and nothing
         * dismisses it for the user: left in the shade past an un-hush it
         * would keep offering a restore that already happened — and a tap on
         * it would then rewrite a media volume the user has since set
         * themselves. Safe to call when nothing is posted.
         */
        fun cancelRestoreOffer(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
