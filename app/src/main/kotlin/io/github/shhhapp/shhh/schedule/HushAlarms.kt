package io.github.shhhapp.shhh.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.shhhapp.shhh.core.QuietHours
import io.github.shhhapp.shhh.core.ShhhSettings
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Exact scheduling for hush timers and quiet hours.
 *
 * Uses [AlarmManager.setExactAndAllowWhileIdle], which requires the user-granted
 * "Alarms & reminders" special access (SCHEDULE_EXACT_ALARM) on modern Android.
 * That grant is required for these features to work at all: receiving an exact
 * alarm is also what allows starting the momentary foreground service that
 * Android 16+ demands for background ringer/volume changes — inexact alarms
 * grant no such allowance. The UI walks the user through granting it.
 */
object HushAlarms {

    const val ACTION_TIMER_RESTORE = "io.github.shhhapp.shhh.alarm.TIMER_RESTORE"
    const val ACTION_QUIET_START = "io.github.shhhapp.shhh.alarm.QUIET_START"

    private const val REQUEST_TIMER = 1
    private const val REQUEST_QUIET_START = 2

    /** True when the "Alarms & reminders" special access has been granted. */
    fun canScheduleExact(context: Context): Boolean =
        context.alarmManager.canScheduleExactAlarms()

    /** Returns false when the exact-alarm permission is missing. */
    fun scheduleTimerRestore(context: Context, endMillis: Long): Boolean =
        setExact(context, endMillis, timerIntent(context))

    fun cancelTimerRestore(context: Context) {
        context.alarmManager.cancel(timerIntent(context))
    }

    /**
     * (Re)schedules the next quiet-hours start alarm from current settings;
     * cancels it when the feature is off. Call after any settings change, on
     * boot, on permission grant, and after each start fires.
     */
    fun syncQuietHoursAlarm(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val schedule = QuietHours.fromSettings(ShhhSettings(context))
        val nextStart = QuietHours.nextStart(now, schedule)
        if (nextStart == null) {
            context.alarmManager.cancel(quietStartIntent(context))
        } else {
            setExact(context, nextStart.toEpochMillis(), quietStartIntent(context))
        }
    }

    private fun setExact(
        context: Context,
        triggerAtMillis: Long,
        operation: PendingIntent
    ): Boolean {
        val alarmManager = context.alarmManager
        if (!alarmManager.canScheduleExactAlarms()) return false
        return try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, operation
            )
            true
        } catch (_: SecurityException) {
            // Grant revoked between the check and the call.
            false
        }
    }

    private fun timerIntent(context: Context): PendingIntent =
        broadcast(context, REQUEST_TIMER, ACTION_TIMER_RESTORE)

    private fun quietStartIntent(context: Context): PendingIntent =
        broadcast(context, REQUEST_QUIET_START, ACTION_QUIET_START)

    private fun broadcast(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private val Context.alarmManager: AlarmManager
        get() = getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun LocalDateTime.toEpochMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
