package io.github.shhhapp.shhh.audio

import android.app.PendingIntent
import android.bluetooth.BluetoothHeadset
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

/**
 * Bluetooth headset connections. (Wired ACTION_HEADSET_PLUG cannot wake a
 * dead process — it is registerReceiver-only — so this feature covers
 * Bluetooth audio, which IS manifest-registerable. Receiving it requires the
 * user-granted BLUETOOTH_CONNECT permission, requested when the option is
 * turned on.)
 *
 * When headphones connect while hushed and the option is on, media volume
 * comes back — headphones bother nobody — while the ringer stays hushed.
 * A direct volume change is attempted first; when Android 16+ audio hardening
 * drops it (we are a background process), we fall back to a one-tap
 * notification that routes through the visible [ToggleActivity] trampoline,
 * which is always allowed to change volume.
 */
class HeadsetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        if (state != BluetoothProfile.STATE_CONNECTED) return

        val manager = HushManager(context)
        if (!manager.isHeadphoneRestoreWanted) return

        if (manager.onHeadphonesConnected()) {
            manager.refreshSurfaces()
        } else {
            postRestoreOffer(context)
        }
    }

    private fun postRestoreOffer(context: Context) {
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

    companion object {
        const val NOTIFICATION_ID = 3
    }
}
