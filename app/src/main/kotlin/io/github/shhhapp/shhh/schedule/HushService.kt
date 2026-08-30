package io.github.shhhapp.shhh.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
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
 *
 * It also serves the Quick Settings tile. A tile's own process is background
 * for audio purposes, so its writes are dropped; the obvious workaround —
 * bouncing through a visible activity — is worse, because
 * [android.service.quicksettings.TileService.startActivityAndCollapse] closes
 * the shade, and a toggle tile must behave like Wi-Fi or the torch and leave
 * it open. Handing the work to this service applies the change with no UI at
 * all, so the shade stays exactly where the user left it.
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

            ACTION_TOGGLE -> manager.toggle()
            ACTION_HUSH -> manager.hush(durationMinutes = intent.durationExtra())
            ACTION_UNHUSH -> manager.unhush()
            ACTION_RESTORE_MEDIA -> if (manager.restoreMediaOnly()) manager.refreshSurfaces()

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

    /** Accepts both int and string extras, matching ToggleActivity. */
    private fun Intent.durationExtra(): Long? {
        val fromInt = getIntExtra(EXTRA_DURATION_MINUTES, -1)
        if (fromInt > 0) return fromInt.toLong()
        return getStringExtra(EXTRA_DURATION_MINUTES)?.toLongOrNull()?.takeIf { it > 0 }
    }

    companion object {
        private const val NOTIFICATION_ID = 2

        const val ACTION_TOGGLE = "io.github.shhhapp.shhh.service.TOGGLE"
        const val ACTION_HUSH = "io.github.shhhapp.shhh.service.HUSH"
        const val ACTION_UNHUSH = "io.github.shhhapp.shhh.service.UNHUSH"
        const val ACTION_RESTORE_MEDIA = "io.github.shhhapp.shhh.service.RESTORE_MEDIA"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"

        /** The intent a surface with no UI of its own uses to change hush state. */
        fun intent(context: Context, action: String): Intent =
            Intent(context, HushService::class.java).setAction(action)
    }
}
