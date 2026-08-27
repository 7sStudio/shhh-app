package io.github.shhhapp.shhh.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shhhapp.shhh.core.ShhhSettings

/**
 * Alarms don't survive reboots (and drift on time/zone changes), so re-sync
 * them here. A hush timer that expired while the phone was off is simply
 * cleared — the reboot already reset the ringer.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                val settings = ShhhSettings(context)
                val timerEnd = settings.timerEndMillis
                if (timerEnd != 0L) {
                    if (timerEnd > System.currentTimeMillis()) {
                        HushAlarms.scheduleTimerRestore(context, timerEnd)
                    } else {
                        settings.timerEndMillis = 0L
                    }
                }
                HushAlarms.syncQuietHoursAlarm(context)
            }
        }
    }
}
