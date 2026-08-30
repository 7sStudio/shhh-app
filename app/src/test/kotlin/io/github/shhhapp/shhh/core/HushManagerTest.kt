package io.github.shhhapp.shhh.core

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class HushManagerTest {

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
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // SharedPreferences outlive a single test in a Robolectric class run.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ShhhSettings(context)
        manager = HushManager(context)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun `timed hush arms the timer and schedules an exact alarm`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)

        manager.hush(durationMinutes = 30)

        assertTrue(manager.isQuiet)
        assertTrue(manager.activeTimerEnd > System.currentTimeMillis())
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `unhush cancels the timer and the alarm`() {
        manager.hush(durationMinutes = 30)

        manager.unhush()

        assertFalse(manager.isQuiet)
        assertEquals(0L, manager.activeTimerEnd)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `untimed hush schedules nothing`() {
        manager.hush()

        assertTrue(manager.isQuiet)
        assertEquals(0L, manager.activeTimerEnd)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `timer firing restores sound`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        manager.hush(durationMinutes = 30)

        manager.onTimerFired()

        assertFalse(manager.isQuiet)
        assertEquals(5, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `timer firing during dnd still restores and clears the timer`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        manager.hush(durationMinutes = 30)
        // DND turns on mid-timer: the interruption filter goes active and the
        // readable ringer is masked to SILENT (Android 17 behavior).
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        manager.onTimerFired()

        assertFalse(manager.isQuiet)
        assertEquals(5, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `timer firing after manual unhush changes nothing`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        manager.hush(durationMinutes = 30)
        manager.unhush()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)

        manager.onTimerFired()

        assertFalse(manager.isQuiet)
        assertEquals(9, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `headphones restore only acts when enabled and hushed`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        manager.hush()

        settings.headphonesAutoRestore = false
        assertFalse(manager.onHeadphonesConnected())
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        settings.headphonesAutoRestore = true
        assertTrue(manager.onHeadphonesConnected())
        assertEquals(5, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(manager.isQuiet) // ringer stays hushed
    }

    @Test
    fun `toggle hushes, then restores`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)

        assertEquals(QuietModeController.Result.Success(quiet = true), manager.toggle())
        assertTrue(manager.isQuiet)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        assertEquals(QuietModeController.Result.Success(quiet = false), manager.toggle())
        assertFalse(manager.isQuiet)
        assertEquals(8, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `restoreMediaOnly brings media back without touching the ringer`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        manager.hush()

        assertTrue(manager.restoreMediaOnly())

        assertEquals(7, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue("the ringer must stay hushed", manager.isQuiet)
    }

    @Test
    fun `restoreMediaOnly reports failure when the volume change does not take`() {
        manager.hush()
        shadowOf(audioManager).setStreamMaxVolume(0)

        assertFalse(manager.restoreMediaOnly())
    }

    private fun revokeDndAccess() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
    }

    @Test
    fun `hush without dnd access arms no timer and changes nothing`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        revokeDndAccess()

        val result = manager.hush(durationMinutes = 30)

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertFalse(manager.isQuiet)
        assertEquals(5, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `hushUntil without dnd access arms no timer`() {
        revokeDndAccess()

        val result = manager.hushUntil(LocalDateTime.now().plusHours(2))

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertFalse(manager.isQuiet)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `unhush without dnd access keeps the timer running`() {
        manager.hush(durationMinutes = 30)
        val armedEnd = settings.timerEndMillis
        revokeDndAccess()

        val result = manager.unhush()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertTrue(manager.isQuiet)
        assertEquals(armedEnd, settings.timerEndMillis)
    }

    @Test
    fun `timer firing without dnd access keeps the timer instead of dropping it`() {
        manager.hush(durationMinutes = 30)
        val armedEnd = settings.timerEndMillis
        revokeDndAccess()

        val result = manager.onTimerFired()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertTrue("the ringer could not be changed", manager.isQuiet)
        assertEquals(
            "a refused restore must not leave the phone hushed with no timer left",
            armedEnd,
            settings.timerEndMillis
        )
    }

    @Test
    fun `unhush with no armed timer leaves the alarm untouched`() {
        manager.hush()
        assertEquals(0L, settings.timerEndMillis)

        assertEquals(QuietModeController.Result.Success(quiet = false), manager.unhush())

        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `unhush when the phone is already audible is a harmless success`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
        assertFalse(manager.isQuiet)

        val result = manager.unhush()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `an elapsed timer end is not an active timer`() {
        settings.timerEndMillis = System.currentTimeMillis() - 1_000L

        assertEquals(0L, manager.activeTimerEnd)
    }

    @Test
    fun `isHeadphoneRestoreWanted needs both the option and a hushed phone`() {
        settings.headphonesAutoRestore = false
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        assertFalse(manager.isHeadphoneRestoreWanted)

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertFalse(manager.isHeadphoneRestoreWanted)

        settings.headphonesAutoRestore = true
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        assertFalse(manager.isHeadphoneRestoreWanted)

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(manager.isHeadphoneRestoreWanted)
    }

    @Test
    fun `hasDndAccess mirrors the notification policy grant`() {
        assertTrue(manager.hasDndAccess)

        revokeDndAccess()

        assertFalse(manager.hasDndAccess)
    }
}
