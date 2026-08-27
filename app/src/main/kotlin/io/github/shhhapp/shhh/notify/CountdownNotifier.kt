package io.github.shhhapp.shhh.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.core.TimeFormat

/**
 * The ongoing "Hushed · sound returns at HH:mm" countdown notification shown
 * while a hush timer runs. Silent, non-dismissable-looking but cancellable by
 * us, with a one-tap "Restore now" action (which routes through the visible
 * [ToggleActivity] trampoline so the change is allowed from the background).
 */
object CountdownNotifier {

    const val CHANNEL_COUNTDOWN = "hush_countdown"
    const val CHANNEL_SERVICE = "hush_service"
    private const val NOTIFICATION_ID = 1

    /** True when POST_NOTIFICATIONS is granted and notifications aren't blocked. */
    fun canNotify(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showIfEnabled(context: Context, endMillis: Long) {
        val settings = ShhhSettings(context)
        if (!settings.liveCountdownEnabled) return
        if (!canNotify(context)) return

        ensureChannels(context)

        val endText = TimeFormat.epochMillis(context, endMillis)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val restoreNow = PendingIntent.getActivity(
            context,
            1,
            Intent(context, ToggleActivity::class.java)
                .setAction(ToggleActivity.ACTION_UNHUSH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_vibration)
            .setContentTitle(context.getString(R.string.countdown_title))
            .setContentText(context.getString(R.string.countdown_text, endText))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(endMillis)
            .setShowWhen(true)
            .setOngoing(true)
            // Android 16+ Live Update: promoted ongoing notification with a
            // live status-bar countdown chip (no-op on older versions).
            .setRequestPromotedOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.countdown_action_restore), restoreNow)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing to do.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun ensureChannels(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COUNTDOWN,
                context.getString(R.string.channel_countdown_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_countdown_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.channel_service_description)
                setShowBadge(false)
            }
        )
    }
}
