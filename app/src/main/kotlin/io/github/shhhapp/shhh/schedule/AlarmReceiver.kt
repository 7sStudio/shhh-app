package io.github.shhhapp.shhh.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a hush alarm goes off. All real work happens in [HushService]:
 * a foreground service is the only context allowed to change ringer/volume
 * in the background on Android 16+, and the alarm-clock delivery grants the
 * temporary allowance to start one.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        context.startForegroundService(
            Intent(context, HushService::class.java).setAction(action)
        )
    }
}
