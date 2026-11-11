package io.github.shhhapp.shhh.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.notify.CountdownNotifier

/**
 * Alarms don't survive reboots (and drift on time/zone changes), so re-sync
 * them here.
 *
 * A hush timer still running is re-armed, and its countdown re-posted:
 * notifications do not survive a reboot either. Unless the hush itself is
 * already over (the ring raised by hand before the phone went down): this is
 * one more place shhh looks at the phone, so that timer is retired instead.
 *
 * A hush timer that expired while the phone was off (or that a clock change
 * pushed into the past) still has a hush to end: the ring volume survives a
 * reboot (measured), so the phone would otherwise stay hushed with no timer
 * left. It gets the normal restore, which only a foreground context may make
 * (Android 16+ drops background volume writes) — so [HushService] does it,
 * exactly as when the alarm fires on time.
 *
 * The same goes for a quiet-hours start that came due while the phone was
 * off: coming back up inside the window, the phone is hushed until the
 * window ends, as if the start alarm had fired. A window whose start was
 * already handled is left alone, so a user who un-hushed during quiet hours
 * is not hushed again by a reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                // Reading the phone only; no volume is written from here.
                HushManager(context).retireStaleHush()
                val timerEnd = ShhhSettings(context).timerEndMillis
                if (timerEnd != 0L) {
                    if (timerEnd > System.currentTimeMillis()) {
                        // No countdown without the alarm that makes it true.
                        if (HushAlarms.scheduleTimerRestore(context, timerEnd)) {
                            CountdownNotifier.showIfEnabled(context, timerEnd)
                        }
                    } else {
                        restoreExpiredTimer(context, timerEnd)
                    }
                }
                HushAlarms.syncQuietHoursAlarm(context)
                if (HushManager(context).isQuietWindowStartMissed()) {
                    startService(context, HushAlarms.ACTION_QUIET_START)
                }
            }
        }
    }

    /** False when Android refuses a background foreground-service start. */
    private fun startService(context: Context, action: String): Boolean = try {
        context.startForegroundService(
            Intent(context, HushService::class.java).setAction(action)
        )
        true
    } catch (_: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is an IllegalStateException.
        false
    }

    /**
     * Boot, time-set and timezone-change broadcasts are documented exemptions
     * from the background foreground-service start ban; the exact-alarm-grant
     * broadcast is not. When the start is refused, the exact alarm is set for
     * the elapsed end instead: it fires at once and takes the usual
     * [AlarmReceiver] -> [HushService] path. If even that cannot be scheduled,
     * the stored timer is kept, not dropped, so a later re-sync (the grant
     * arriving, the next boot) still ends the hush. The service clears it once
     * the restore has really happened.
     */
    private fun restoreExpiredTimer(context: Context, timerEnd: Long) {
        if (!startService(context, HushAlarms.ACTION_TIMER_RESTORE)) {
            HushAlarms.scheduleTimerRestore(context, timerEnd)
        }
    }
}
