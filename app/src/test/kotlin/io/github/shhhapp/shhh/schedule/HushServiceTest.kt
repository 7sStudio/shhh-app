package io.github.shhhapp.shhh.schedule

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.notify.CountdownNotifier
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

private fun LocalDateTime.toEpochMillis(): Long =
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HushServiceTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var settings: ShhhSettings

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
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    private fun service(): HushService = Robolectric.buildService(HushService::class.java).create().get()

    @Test
    fun `the service is not bindable`() {
        assertNull(service().onBind(Intent()))
    }

    @Test
    fun `ACTION_TIMER_RESTORE triggers unhush`() {
        // Setup: phone is quiet
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        settings.timerEndMillis = System.currentTimeMillis() + 1000

        service().handleIntent(Intent(HushAlarms.ACTION_TIMER_RESTORE))

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `ACTION_QUIET_START triggers hush until end`() {
        // Setup: 22:00 to 07:00 quiet hours.
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = 22 * 60
        settings.quietEndMinutes = 7 * 60
        settings.quietDays = DayOfWeek.entries.toSet()

        // Mock time to 23:00 (inside the window)
        val insideWindow = LocalDateTime.of(2026, 8, 27, 23, 0)

        service().handleIntent(Intent(HushAlarms.ACTION_QUIET_START), now = insideWindow)

        assertTrue("Should be quiet when started inside a window", HushManager(context).isQuiet)
        assertEquals(
            "the hush must end when the window does",
            LocalDateTime.of(2026, 8, 28, 7, 0).toEpochMillis(),
            settings.timerEndMillis
        )
    }

    @Test
    fun `ACTION_QUIET_START outside any window hushes nothing but re-arms the next start`() {
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = 22 * 60
        settings.quietEndMinutes = 7 * 60
        settings.quietDays = DayOfWeek.entries.toSet()

        val outsideWindow = LocalDateTime.of(2026, 8, 27, 12, 0)

        service().handleIntent(Intent(HushAlarms.ACTION_QUIET_START), now = outsideWindow)

        assertFalse("nothing to hush at noon", HushManager(context).isQuiet)
        assertEquals(0L, settings.timerEndMillis)
        val nextAlarm = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull("the next start must still be scheduled", nextAlarm)
        assertEquals(
            LocalDateTime.of(2026, 8, 27, 22, 0).toEpochMillis(),
            nextAlarm!!.triggerAtMs
        )
    }

    @Test
    fun `a null intent changes nothing`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        service().handleIntent(null)

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `an unknown action changes nothing`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        service().handleIntent(Intent("io.github.shhhapp.shhh.alarm.NOPE"))

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `the service goes foreground as special use, then stops itself`() {
        val intent = Intent(context, HushService::class.java).setAction("SOME_ACTION")

        val service = Robolectric.buildService(HushService::class.java, intent).create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 7))

        val shadowService = shadowOf(service)
        val notification = shadowService.lastForegroundNotification
        assertNotNull("Foreground notification should be recorded", notification)
        assertEquals(
            CountdownNotifier.CHANNEL_SERVICE,
            notification.channelId
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            service.foregroundServiceType
        )
        assertTrue(shadowService.isStoppedBySelf)
        assertEquals(7, shadowService.stopSelfId)
    }

    @Test
    fun `going foreground creates the notification channels first`() {
        val intent = Intent(context, HushService::class.java).setAction("SOME_ACTION")

        Robolectric.buildService(HushService::class.java, intent).create().startCommand(0, 0)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(
            notificationManager.getNotificationChannel(CountdownNotifier.CHANNEL_SERVICE)
        )
        assertNotNull(
            notificationManager.getNotificationChannel(CountdownNotifier.CHANNEL_COUNTDOWN)
        )
    }
}
