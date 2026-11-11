package io.github.shhhapp.shhh.schedule

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
class ReceiverTests {

    private lateinit var context: Application
    private lateinit var alarmManager: AlarmManager
    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // SharedPreferences outlive a single test in a Robolectric class run.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ShhhSettings(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private val nextAlarm get() = shadowOf(alarmManager).peekNextScheduledAlarm()

    /**
     * Ring volume 0, i.e. the hush a stored timer belongs to is still on.
     * The receiver looks at the phone first: a timer whose hush is already
     * over is retired, not re-armed.
     */
    private fun phoneStillHushed() {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setStreamVolume(AudioManager.STREAM_RING, 0, 0)
    }

    @Test
    fun `AlarmReceiver starts HushService with same action`() {
        val receiver = AlarmReceiver()
        val intent = Intent(HushAlarms.ACTION_TIMER_RESTORE)

        receiver.onReceive(context, intent)

        val serviceIntent = shadowOf(context).nextStartedService
        assertNotNull("HushService should be started", serviceIntent)
        assertEquals(HushService::class.java.name, serviceIntent!!.component!!.className)
        assertEquals(HushAlarms.ACTION_TIMER_RESTORE, serviceIntent.action)
    }

    @Test
    fun `AlarmReceiver forwards quiet-hours starts too`() {
        AlarmReceiver().onReceive(context, Intent(HushAlarms.ACTION_QUIET_START))

        val serviceIntent = shadowOf(context).nextStartedService
        assertNotNull("HushService should be started", serviceIntent)
        assertEquals(HushAlarms.ACTION_QUIET_START, serviceIntent!!.action)
    }

    @Test
    fun `AlarmReceiver ignores null action`() {
        val receiver = AlarmReceiver()
        receiver.onReceive(context, Intent())
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun `BootReceiver re-arms future timer`() {
        phoneStillHushed()
        val future = System.currentTimeMillis() + 100_000L
        settings.timerEndMillis = future
        val receiver = BootReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val shadowAlarm = nextAlarm
        assertNotNull("Alarm should be re-scheduled", shadowAlarm)
        assertEquals(future, shadowAlarm!!.triggerAtMs)
        assertEquals(future, settings.timerEndMillis)
    }

    // ---- a timer that ran out while the phone was off ----
    // The ring volume survives a reboot (measured), so an expired timer still
    // has a hush to end. It used to be cleared on the assumption that the
    // reboot had reset the ringer, leaving the phone hushed indefinitely.

    @Test
    fun `BootReceiver hands an expired timer to the service for the normal restore`() {
        phoneStillHushed()
        val past = System.currentTimeMillis() - 100_000L
        settings.timerEndMillis = past

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(context).nextStartedService
        assertEquals(HushService::class.java.name, started?.component?.className)
        assertEquals(HushAlarms.ACTION_TIMER_RESTORE, started?.action)
        assertEquals("kept until the restore has really happened", past, settings.timerEndMillis)
        assertNull(nextAlarm)
    }

    @Test
    fun `an expired timer found at boot brings the sound back`() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 4, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        HushManager(context).hush(durationMinutes = 1)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_RING))
        // The phone was off when the minute ran out.
        settings.timerEndMillis = System.currentTimeMillis() - 60_000L

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = requireNotNull(shadowOf(context).nextStartedService)
        Robolectric.buildService(HushService::class.java, started).create().get()
            .handleIntent(started)

        assertEquals(4, audioManager.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(0L, settings.timerEndMillis)
    }

    /** A context whose background foreground-service starts are refused. */
    private val refusingServiceStarts = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun startForegroundService(service: Intent): ComponentName? =
            throw IllegalStateException("ForegroundServiceStartNotAllowedException")
    }

    @Test
    fun `a refused service start falls back to an alarm that fires at once`() {
        // The exact-alarm-grant broadcast is no exemption from the background
        // foreground-service start ban.
        phoneStillHushed()
        val past = System.currentTimeMillis() - 100_000L
        settings.timerEndMillis = past

        BootReceiver().onReceive(
            refusingServiceStarts,
            Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        )

        assertEquals("the elapsed end, so it fires immediately", past, nextAlarm?.triggerAtMs)
        assertEquals(past, settings.timerEndMillis)
    }

    @Test
    fun `an expired timer nothing can restore yet is kept, not dropped`() {
        phoneStillHushed()
        val past = System.currentTimeMillis() - 100_000L
        settings.timerEndMillis = past
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        BootReceiver().onReceive(refusingServiceStarts, Intent(Intent.ACTION_TIME_CHANGED))

        assertNull(nextAlarm)
        assertEquals(past, settings.timerEndMillis)
    }

    @Test
    fun `BootReceiver re-posts the countdown of a timer still running`() {
        // Notifications do not survive a reboot.
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationsEnabled(true)
        phoneStillHushed()
        settings.timerEndMillis = System.currentTimeMillis() + 100_000L

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        // CountdownNotifier posts under id 1.
        assertNotNull(shadowOf(notificationManager).getNotification(1))
    }

    @Test
    fun `BootReceiver posts no countdown when the alarm behind it cannot be set`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationsEnabled(true)
        phoneStillHushed()
        val future = System.currentTimeMillis() + 100_000L
        settings.timerEndMillis = future
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull("nothing may promise a restore no alarm will make",
            shadowOf(notificationManager).getNotification(1))
        assertEquals("kept for the re-sync when the grant comes back", future, settings.timerEndMillis)
    }

    @Test
    fun `a running timer whose hush already ended is retired at boot, not re-armed`() {
        // Ring raised by hand before the phone went down: shhh reads as off.
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationsEnabled(true)
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setStreamVolume(AudioManager.STREAM_RING, 4, 0)
        settings.timerEndMillis = System.currentTimeMillis() + 100_000L

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0L, settings.timerEndMillis)
        assertNull(nextAlarm)
        assertNull(shadowOf(notificationManager).getNotification(1))
    }

    @Test
    fun `an expired timer whose hush already ended is dropped without a restore`() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 4, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        settings.timerEndMillis = System.currentTimeMillis() - 100_000L

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0L, settings.timerEndMillis)
        assertNull("no service start: there is nothing to restore",
            shadowOf(context).nextStartedService)
        assertEquals(9, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // ---- a quiet-hours start that came due while the phone was off ----

    /** Quiet hours on, with a window from one hour ago to one hour ahead. */
    private fun insideAQuietWindow() {
        val nowMinutes = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = Math.floorMod(nowMinutes - 60, 24 * 60)
        settings.quietEndMinutes = Math.floorMod(nowMinutes + 60, 24 * 60)
        settings.quietDays = java.time.DayOfWeek.entries.toSet()
    }

    @Test
    fun `booting inside a quiet window whose start was missed hushes until it ends`() {
        insideAQuietWindow()

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = requireNotNull(shadowOf(context).nextStartedService)
        assertEquals(HushService::class.java.name, started.component?.className)
        assertEquals(HushAlarms.ACTION_QUIET_START, started.action)
        Robolectric.buildService(HushService::class.java, started).create().get()
            .handleIntent(started)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(true, settings.timerEndMillis > System.currentTimeMillis())
    }

    @Test
    fun `booting inside a window that already started normally hushes nothing again`() {
        // The start alarm fired, then the user un-hushed: a reboot must not
        // undo that.
        insideAQuietWindow()
        val manager = HushManager(context)
        manager.hushForQuietWindow()
        manager.unhush()

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun `booting outside any quiet window starts nothing`() {
        insideAQuietWindow()
        settings.quietHoursEnabled = false

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun `a refused service start for a missed quiet window is survived`() {
        insideAQuietWindow()

        BootReceiver().onReceive(
            refusingServiceStarts,
            Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        )

        assertEquals("left for the next look", 0L, settings.lastQuietWindowStart)
    }

    @Test
    fun `BootReceiver with no timer only syncs quiet hours`() {
        settings.timerEndMillis = 0L
        settings.quietHoursEnabled = false

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0L, settings.timerEndMillis)
        assertNull(nextAlarm)
    }

    @Test
    fun `BootReceiver syncs quiet hours`() {
        settings.quietHoursEnabled = true
        val receiver = BootReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

        assertNotNull("Quiet hours alarm should be synced", nextAlarm)
    }

    @Test
    fun `BootReceiver syncs quiet hours after a timezone change`() {
        settings.quietHoursEnabled = true

        BootReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertNotNull("Quiet hours alarm should be synced", nextAlarm)
    }

    @Test
    fun `BootReceiver syncs quiet hours when the exact-alarm grant changes`() {
        settings.quietHoursEnabled = true

        BootReceiver().onReceive(
            context,
            Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        )

        assertNotNull("Quiet hours alarm should be synced", nextAlarm)
    }

    @Test
    fun `BootReceiver ignores unrelated broadcasts`() {
        val future = System.currentTimeMillis() + 100_000L
        settings.timerEndMillis = future
        settings.quietHoursEnabled = true

        BootReceiver().onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        assertNull("nothing should be scheduled", nextAlarm)
        assertEquals(future, settings.timerEndMillis)
    }
}
