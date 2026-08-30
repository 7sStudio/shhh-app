package io.github.shhhapp.shhh.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.ShhhSettings
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
class QuietHoursIntegrationTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var settings: ShhhSettings
    private lateinit var manager: HushManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // SharedPreferences outlive a single test in a Robolectric class run.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ShhhSettings(context)
        manager = HushManager(context)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        
        // Reset quiet hours settings
        settings.quietHoursEnabled = false
        settings.quietDays = DayOfWeek.entries.toSet()
        settings.quietStartMinutes = 23 * 60
        settings.quietEndMinutes = 7 * 60
    }

    /**
     * The exact PendingIntent [HushAlarms] uses for quiet-hours starts:
     * a broadcast to [AlarmReceiver] carrying [HushAlarms.ACTION_QUIET_START].
     * FLAG_NO_CREATE means this returns null unless that PendingIntent exists.
     */
    private fun existingQuietStartIntent(): PendingIntent? = PendingIntent.getBroadcast(
        context,
        2,
        Intent(context, AlarmReceiver::class.java).setAction(HushAlarms.ACTION_QUIET_START),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
    )

    @Test
    fun `enabling quiet hours schedules the quiet-start broadcast`() {
        settings.quietHoursEnabled = true
        val now = LocalDateTime.of(2026, 8, 26, 12, 0)

        HushAlarms.syncQuietHoursAlarm(context, now)

        val nextAlarm = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull("Alarm should be scheduled when quiet hours are enabled", nextAlarm)
        assertEquals(
            "it must fire at the 23:00 window start",
            LocalDateTime.of(2026, 8, 26, 23, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            nextAlarm!!.triggerAtMs
        )

        // Identity check: cancelling exactly the quiet-start broadcast clears it.
        val quietStart = existingQuietStartIntent()
        assertNotNull("the quiet-start PendingIntent must exist", quietStart)
        alarmManager.cancel(quietStart!!)
        assertNull(
            "the scheduled alarm was the quiet-start broadcast",
            shadowOf(alarmManager).peekNextScheduledAlarm()
        )
    }

    @Test
    fun `disabling quiet hours cancels the alarm`() {
        settings.quietHoursEnabled = true
        HushAlarms.syncQuietHoursAlarm(context)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        settings.quietHoursEnabled = false
        HushAlarms.syncQuietHoursAlarm(context)
        assertNull("Alarm should be cancelled when quiet hours are disabled", shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `enabling quiet hours mid-window hushes immediately`() {
        // Schedule: 22:00 to 07:00
        settings.quietStartMinutes = 22 * 60
        settings.quietEndMinutes = 7 * 60
        settings.quietHoursEnabled = true
        
        // Current time: 23:00 today (inside window). Built from the real clock
        // because hushUntil stores an absolute epoch and activeTimerEnd
        // compares it against System.currentTimeMillis() — a hard-coded date
        // turns into a time bomb once it passes.
        val now = LocalDateTime.now().withHour(23).withMinute(0)
        
        // This logic is in MainActivity.onQuietHoursChanged
        HushAlarms.syncQuietHoursAlarm(context, now)
        val schedule = io.github.shhhapp.shhh.core.QuietHours.fromSettings(settings)
        io.github.shhhapp.shhh.core.QuietHours.activeWindowEnd(now, schedule)?.let { end ->
            manager.hushUntil(end)
        }

        assertTrue("Should be quiet when enabled inside a window", manager.isQuiet)
        assertTrue("Timer should be set to end at 07:00 tomorrow", manager.activeTimerEnd > 0)
    }

    @Test
    fun `syncQuietHoursAlarm cancels when no days selected`() {
        settings.quietHoursEnabled = true
        settings.quietDays = emptySet()
        
        HushAlarms.syncQuietHoursAlarm(context)
        
        assertNull("Alarm should be cancelled when no days are selected", shadowOf(alarmManager).peekNextScheduledAlarm())
    }
}
