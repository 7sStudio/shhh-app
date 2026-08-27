package io.github.shhhapp.shhh.schedule

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietHours
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.notify.CountdownNotifier
import java.time.LocalDateTime

/**
 * Momentary foreground service that applies a scheduled hush transition and
 * stops. Runs for well under a second; its notification is silent and
 * minimum-importance, so it never visibly interrupts.
 *
 * Why an FGS at all: Android 16+ audio hardening silently drops ringer and
 * volume changes from background processes unless the app has a visible
 * activity or a running (non-short) foreground service.
 */
class HushService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        handleIntent(intent)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    internal fun handleIntent(intent: Intent?, now: LocalDateTime = LocalDateTime.now()) {
        val manager = HushManager(this)
        when (intent?.action) {
            HushAlarms.ACTION_TIMER_RESTORE -> manager.onTimerFired()

            HushAlarms.ACTION_QUIET_START -> {
                val schedule = QuietHours.fromSettings(ShhhSettings(this))
                QuietHours.activeWindowEnd(now, schedule)?.let { end ->
                    manager.hushUntil(end)
                }
                // Line up the next occurrence.
                HushAlarms.syncQuietHoursAlarm(this, now)
            }
        }
    }

    private fun startAsForeground() {
        CountdownNotifier.ensureChannels(this)
        val notification: Notification =
            NotificationCompat.Builder(this, CountdownNotifier.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_vibration)
                .setContentTitle(getString(R.string.service_notification_title))
                .setSilent(true)
                .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 2
    }
}
