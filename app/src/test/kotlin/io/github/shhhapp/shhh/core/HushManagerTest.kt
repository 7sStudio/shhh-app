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
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class HushManagerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings
    private lateinit var manager: HushManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // SharedPreferences outlive a single test in a Robolectric class run.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        // Shadow statics outlive one test too.
        ShadowRefusingAudioManager.refusedStreams = emptySet()

        settings = ShhhSettings(context)
        manager = HushManager(context)

        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        ring = 3
        media = 5
    }

    private var ring: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_RING, value, 0)

    private var media: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)

    /**
     * The one state Android really refuses: a zen mode is running and Do Not
     * Disturb access was never granted, so every ring-volume write throws
     * SecurityException("Not allowed to change Do Not Disturb state"). With no
     * zen running the same writes go through without any permission.
     */
    private fun refuseVolumeWritesUnderZen() {
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        ShadowRefusingAudioManager.refusedStreams =
            setOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)
    }

    // ------------------------------------------------------------- timers

    @Test
    fun `timed hush arms the timer and schedules an exact alarm`() {
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
        manager.hush(durationMinutes = 30)

        manager.onTimerFired()

        assertFalse(manager.isQuiet)
        assertEquals(3, ring)
        assertEquals(5, media)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `timer firing during priority dnd still restores and clears the timer`() {
        manager.hush(durationMinutes = 30)
        // Do Not Disturb turns on mid-timer. Priority zen leaves the ring
        // slider readable, so the restore is an ordinary one.
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )

        manager.onTimerFired()

        assertFalse(manager.isQuiet)
        assertEquals(3, ring)
        assertEquals(5, media)
        assertEquals(0L, settings.timerEndMillis)
        assertEquals(
            "the user's Do Not Disturb must survive the restore",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun `timer firing under alarms only still restores and clears the timer`() {
        manager.hush(durationMinutes = 30)
        // "Alarms only" pins the ring volume every app reads at 0, so only the
        // remembered state can tell onTimerFired that there is a hush to end.
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_ALARMS
        )

        manager.onTimerFired()

        assertFalse(manager.isQuiet)
        assertFalse(settings.lastKnownQuiet)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `timer firing after manual unhush changes nothing`() {
        manager.hush(durationMinutes = 30)
        manager.unhush()
        media = 9

        manager.onTimerFired()

        assertFalse(manager.isQuiet)
        assertEquals(9, media)
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
        assertFalse(manager.isQuiet)

        val result = manager.unhush()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertTrue(ring > 0)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `an elapsed timer end is not an active timer`() {
        settings.timerEndMillis = System.currentTimeMillis() - 1_000L

        assertEquals(0L, manager.activeTimerEnd)
    }

    // ------------------------------------------------------------- toggling

    @Test
    fun `toggle hushes, then restores`() {
        media = 8

        assertEquals(QuietModeController.Result.Success(quiet = true), manager.toggle())
        assertTrue(manager.isQuiet)
        assertEquals(0, ring)
        assertEquals(0, media)

        assertEquals(QuietModeController.Result.Success(quiet = false), manager.toggle())
        assertFalse(manager.isQuiet)
        assertEquals(3, ring)
        assertEquals(8, media)
    }

    /**
     * The reported bug, end to end: Do Not Disturb on, shhh off, one on/off
     * cycle — and the user's Do Not Disturb was gone. It went through
     * AudioManager.setRingerMode(RINGER_MODE_NORMAL), AOSP's external ringer
     * path, which ends any active zen mode.
     */
    @Test
    fun `hushing and un-hushing under priority dnd leaves dnd running`() {
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        // While a zen runs, the legacy ringer mode every app reads is masked to
        // SILENT. Shhh no longer reads it, and this proves it does not.
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertFalse("shhh is off to begin with", manager.isQuiet)

        assertEquals(QuietModeController.Result.Success(quiet = true), manager.toggle())
        assertEquals(0, ring)
        assertTrue(manager.isQuiet)

        assertEquals(QuietModeController.Result.Success(quiet = false), manager.toggle())
        assertEquals(3, ring)
        assertFalse(manager.isQuiet)

        assertEquals(
            "un-hushing must never end the user's Do Not Disturb",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            notificationManager.currentInterruptionFilter
        )
        assertTrue(manager.isDndActive)
        assertFalse(settings.lastKnownQuiet)
    }

    // ------------------------------------------------------------ headphones

    @Test
    fun `headphones restore only acts when enabled and hushed`() {
        manager.hush()

        settings.headphonesAutoRestore = false
        assertFalse(manager.onHeadphonesConnected())
        assertEquals(0, media)

        settings.headphonesAutoRestore = true
        assertTrue(manager.onHeadphonesConnected())
        assertEquals(5, media)
        assertTrue("the ringer stays hushed", manager.isQuiet)
    }

    @Test
    fun `isHeadphoneRestoreWanted needs both the option and a hushed phone`() {
        settings.headphonesAutoRestore = false
        ring = 3
        assertFalse(manager.isHeadphoneRestoreWanted)

        ring = 0
        assertFalse(manager.isHeadphoneRestoreWanted)

        settings.headphonesAutoRestore = true
        ring = 3
        assertFalse(manager.isHeadphoneRestoreWanted)

        ring = 0
        assertTrue(manager.isHeadphoneRestoreWanted)
    }

    @Test
    fun `restoreMediaOnly brings media back without touching the ringer`() {
        media = 7
        manager.hush()

        assertTrue(manager.restoreMediaOnly())

        assertEquals(7, media)
        assertEquals(0, ring)
        assertTrue("the ringer must stay hushed", manager.isQuiet)
    }

    @Test
    fun `restoreMediaOnly reports failure when the volume change does not take`() {
        manager.hush()
        shadowOf(audioManager).setStreamMaxVolume(0)

        assertFalse(manager.restoreMediaOnly())
    }

    // ------------------------------------------------------ permission gates

    @Test
    fun `hasDndAccess mirrors the notification policy grant`() {
        assertTrue(manager.hasDndAccess)

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)

        assertFalse(manager.hasDndAccess)
    }

    @Test
    fun `isDndActive and canChangeSound mirror the phone's zen state`() {
        assertFalse(manager.isDndActive)
        assertTrue(manager.canChangeSound)

        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        assertTrue(manager.isDndActive)
        assertTrue("access is granted, so shhh may still act", manager.canChangeSound)

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        assertFalse(manager.canChangeSound)
    }

    @Test
    fun `hush without dnd access works while no zen is running`() {
        // Volume writes need no permission at all outside a zen mode; the old
        // up-front permission check refused this case for nothing.
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)

        val result = manager.hush(durationMinutes = 30)

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertTrue(manager.isQuiet)
        assertEquals(0, ring)
        assertTrue(manager.activeTimerEnd > System.currentTimeMillis())
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused hush arms no timer and changes nothing`() {
        refuseVolumeWritesUnderZen()

        val result = manager.hush(durationMinutes = 30)

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertFalse(manager.isQuiet)
        assertEquals(3, ring)
        assertEquals(5, media)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused hushUntil arms no timer`() {
        refuseVolumeWritesUnderZen()

        val result = manager.hushUntil(LocalDateTime.now().plusHours(2))

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertFalse(manager.isQuiet)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused unhush keeps the timer running`() {
        manager.hush(durationMinutes = 30)
        val armedEnd = settings.timerEndMillis
        refuseVolumeWritesUnderZen()

        val result = manager.unhush()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertTrue(manager.isQuiet)
        assertEquals(armedEnd, settings.timerEndMillis)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused timer restore keeps the timer instead of dropping it`() {
        manager.hush(durationMinutes = 30)
        val armedEnd = settings.timerEndMillis
        refuseVolumeWritesUnderZen()

        val result = manager.onTimerFired()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertTrue("the ring volume could not be changed", manager.isQuiet)
        assertEquals(
            "a refused restore must not leave the phone hushed with no timer left",
            armedEnd,
            settings.timerEndMillis
        )
    }
}
