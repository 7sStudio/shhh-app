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
        ring = 3
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
    }

    /** Hushed means "ring volume 0" — the slider shhh reads and writes. */
    private var ring: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_RING, value, 0)

    private fun service(): HushService = Robolectric.buildService(HushService::class.java).create().get()

    @Test
    fun `the service is not bindable`() {
        assertNull(service().onBind(Intent()))
    }

    @Test
    fun `ACTION_TIMER_RESTORE triggers unhush`() {
        // Setup: phone is quiet
        ring = 0
        settings.previousRingVolume = 4
        settings.timerEndMillis = System.currentTimeMillis() + 1000

        service().handleIntent(Intent(HushAlarms.ACTION_TIMER_RESTORE))

        assertEquals(4, ring)
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

    // ---- The tile's actions ----
    //
    // The Quick Settings tile hands its work here rather than to an activity:
    // a tile can only start an activity via startActivityAndCollapse, which
    // closes the shade, and a toggle tile must leave the panel open. Its own
    // process is background for audio purposes, so it cannot write the volume
    // itself; this momentary foreground service can.

    @Test
    fun `ACTION_TOGGLE hushes a phone that is making noise`() {
        val intent = HushService.intent(context, HushService.ACTION_TOGGLE)

        service().handleIntent(intent)

        assertEquals(0, ring)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(HushManager(context).isQuiet)
    }

    @Test
    fun `ACTION_TOGGLE restores a phone that is already hushed`() {
        settings.previousRingVolume = 4
        settings.previousMediaVolume = 7
        ring = 0
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        service().handleIntent(HushService.intent(context, HushService.ACTION_TOGGLE))

        assertEquals(4, ring)
        assertEquals(7, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse(HushManager(context).isQuiet)
    }

    @Test
    fun `ACTION_HUSH with a duration hushes and arms the timer`() {
        val intent = HushService.intent(context, HushService.ACTION_HUSH)
            .putExtra(HushService.EXTRA_DURATION_MINUTES, 30)

        service().handleIntent(intent)

        assertEquals(0, ring)
        assertTrue("the timer should be armed", settings.timerEndMillis > System.currentTimeMillis())
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test
    fun `ACTION_HUSH accepts a string duration extra like am and automation apps send`() {
        val intent = HushService.intent(context, HushService.ACTION_HUSH)
            .putExtra(HushService.EXTRA_DURATION_MINUTES, "15")

        service().handleIntent(intent)

        assertEquals(0, ring)
        assertTrue(settings.timerEndMillis > System.currentTimeMillis())
    }

    @Test
    fun `ACTION_HUSH without a duration hushes without arming anything`() {
        service().handleIntent(HushService.intent(context, HushService.ACTION_HUSH))

        assertEquals(0, ring)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `ACTION_UNHUSH restores sound and disarms a running timer`() {
        // The hush saves whatever level it finds, so that is what comes back.
        ring = 4
        service().handleIntent(
            HushService.intent(context, HushService.ACTION_HUSH)
                .putExtra(HushService.EXTRA_DURATION_MINUTES, 30)
        )
        assertEquals(0, ring)

        service().handleIntent(HushService.intent(context, HushService.ACTION_UNHUSH))

        assertEquals(4, ring)
        assertEquals("the timer must be disarmed", 0L, settings.timerEndMillis)
    }

    @Test
    fun `ACTION_RESTORE_MEDIA brings media back and leaves the ring hushed`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        service().handleIntent(HushService.intent(context, HushService.ACTION_TOGGLE))
        assertEquals(0, ring)

        service().handleIntent(HushService.intent(context, HushService.ACTION_RESTORE_MEDIA))

        assertEquals("the ring must stay hushed", 0, ring)
        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `a null intent changes nothing`() {
        ring = 0

        service().handleIntent(null)

        assertEquals(0, ring)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `an unknown action changes nothing`() {
        ring = 0

        service().handleIntent(Intent("io.github.shhhapp.shhh.alarm.NOPE"))

        assertEquals(0, ring)
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
