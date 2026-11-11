package io.github.shhhapp.shhh.core

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.audio.isHeadphonesOfferShown
import io.github.shhhapp.shhh.audio.postStandInHeadphonesOffer
import io.mockk.every
import io.mockk.mockk
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
    fun `an absurd duration is cut to a day instead of overflowing into the past`() {
        // Long.MAX_VALUE minutes times 60 000 wraps negative: the timer would
        // "end" before it started and the phone would un-hush at once.
        val before = System.currentTimeMillis()

        manager.hush(durationMinutes = Long.MAX_VALUE)

        assertTrue(manager.isQuiet)
        val day = HushManager.MAX_TIMED_HUSH_MINUTES * 60_000L
        assertTrue(settings.timerEndMillis >= before + day)
        assertTrue(settings.timerEndMillis <= System.currentTimeMillis() + day)
    }

    @Test
    fun `a zero or negative duration still gets a real timer`() {
        val before = System.currentTimeMillis()

        manager.hush(durationMinutes = -5)

        assertTrue(settings.timerEndMillis >= before + 60_000L)
    }

    // ---------------------------------------------------- until a set time

    private fun LocalDateTime.epochMillis(): Long =
        atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `hushing until a time later today ends today at that minute`() {
        val noon = LocalDateTime.of(2026, 8, 26, 12, 0)

        manager.hushUntilTimeOfDay(13 * 60 + 30, now = noon)

        assertTrue(manager.isQuiet)
        assertEquals(LocalDateTime.of(2026, 8, 26, 13, 30).epochMillis(), settings.timerEndMillis)
    }

    @Test
    fun `hushing until a time already past means that time tomorrow`() {
        val noon = LocalDateTime.of(2026, 8, 26, 12, 0)

        manager.hushUntilTimeOfDay(7 * 60, now = noon)

        assertEquals(LocalDateTime.of(2026, 8, 27, 7, 0).epochMillis(), settings.timerEndMillis)
    }

    @Test
    fun `hushing until the current minute means a full day, not now`() {
        val noonAndABit = LocalDateTime.of(2026, 8, 26, 12, 0, 40)

        manager.hushUntilTimeOfDay(12 * 60, now = noonAndABit)

        assertEquals(LocalDateTime.of(2026, 8, 27, 12, 0).epochMillis(), settings.timerEndMillis)
    }

    @Test
    fun `hushing until a time without the alarm grant changes nothing`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = manager.hushUntilTimeOfDay(13 * 60)

        assertEquals(QuietModeController.Result.NeedsExactAlarmAccess, result)
        assertFalse(manager.isQuiet)
    }

    // ------------------------------------------------- quiet-hours windows

    /** A window from one hour ago to one hour ahead, every day. */
    private fun quietWindowAroundNow() {
        val nowMinutes = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = Math.floorMod(nowMinutes - 60, 24 * 60)
        settings.quietEndMinutes = Math.floorMod(nowMinutes + 60, 24 * 60)
        settings.quietDays = java.time.DayOfWeek.entries.toSet()
    }

    @Test
    fun `hushForQuietWindow hushes until the window ends and marks it handled`() {
        quietWindowAroundNow()
        assertTrue("its start has not been acted on yet", manager.isQuietWindowStartMissed())

        manager.hushForQuietWindow()

        assertTrue(manager.isQuiet)
        assertTrue(manager.activeTimerEnd > System.currentTimeMillis())
        assertFalse(manager.isQuietWindowStartMissed())
    }

    @Test
    fun `a handled window stays handled after the user un-hushes`() {
        // Otherwise a reboot during quiet hours would hush again a phone its
        // owner deliberately made audible.
        quietWindowAroundNow()
        manager.hushForQuietWindow()

        manager.unhush()

        assertFalse(manager.isQuietWindowStartMissed())
    }

    @Test
    fun `hushForQuietWindow outside a window does nothing`() {
        settings.quietHoursEnabled = false

        manager.hushForQuietWindow()

        assertFalse(manager.isQuiet)
        assertEquals(0L, settings.lastQuietWindowStart)
        assertFalse(manager.isQuietWindowStartMissed())
    }

    @Test
    fun `hushForQuietWindow asked to spare a hushed phone arms no timer but marks the window`() {
        quietWindowAroundNow()
        manager.hush()

        manager.hushForQuietWindow(onlyIfAudible = true)

        assertEquals("the user's own hush gets no timer", 0L, settings.timerEndMillis)
        assertFalse(manager.isQuietWindowStartMissed())
    }

    @Test
    fun `hushForQuietWindow asked to spare a hushed phone still hushes an audible one`() {
        quietWindowAroundNow()

        manager.hushForQuietWindow(onlyIfAudible = true)

        assertTrue(manager.isQuiet)
        assertTrue(manager.activeTimerEnd > System.currentTimeMillis())
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

    // ---------------------------------------------------------- notifications
    // The headphones offer is only true while hushed with media muted, and
    // nothing dismisses it for the user: every way a hush ends has to.

    @Test
    fun `un-hushing takes an untapped headphones offer down`() {
        manager.hush()
        postStandInHeadphonesOffer(context)

        manager.unhush()

        assertFalse(isHeadphonesOfferShown(context))
    }

    @Test
    fun `toggling off takes the headphones offer down`() {
        manager.toggle()
        postStandInHeadphonesOffer(context)

        manager.toggle()

        assertFalse(isHeadphonesOfferShown(context))
    }

    @Test
    fun `un-hushing a timed hush leaves no shhh notification behind`() {
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notificationManager).setNotificationsEnabled(true)
        manager.hush(durationMinutes = 30)
        postStandInHeadphonesOffer(context)
        assertEquals(
            "the countdown and the offer are both up",
            2,
            shadowOf(notificationManager).allNotifications.size
        )

        manager.unhush()

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `the timer firing takes the headphones offer down`() {
        manager.hush(durationMinutes = 30)
        postStandInHeadphonesOffer(context)

        manager.onTimerFired()

        assertFalse(isHeadphonesOfferShown(context))
    }

    @Test
    fun `a timer firing after sound came back some other way still clears the offer`() {
        manager.hush(durationMinutes = 30)
        postStandInHeadphonesOffer(context)
        // The ring slider, moved by hand: shhh was never asked to un-hush.
        ring = 4

        manager.onTimerFired()

        assertFalse(isHeadphonesOfferShown(context))
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused un-hush keeps the headphones offer`() {
        manager.hush()
        postStandInHeadphonesOffer(context)
        refuseVolumeWritesUnderZen()

        assertEquals(QuietModeController.Result.NeedsDndAccess, manager.unhush())

        assertTrue(
            "still hushed, so the offer is still true",
            isHeadphonesOfferShown(context)
        )
    }

    @Test
    fun `hushing again keeps a standing headphones offer`() {
        manager.hush()
        postStandInHeadphonesOffer(context)

        manager.hush(durationMinutes = 30)

        assertTrue(isHeadphonesOfferShown(context))
    }

    @Test
    fun `restoring media retires the headphones offer`() {
        manager.hush()
        postStandInHeadphonesOffer(context)

        assertTrue(manager.restoreMediaOnly())

        assertFalse(isHeadphonesOfferShown(context))
    }

    @Test
    fun `a media restore that did not take keeps the headphones offer`() {
        manager.hush()
        postStandInHeadphonesOffer(context)
        shadowOf(audioManager).setStreamMaxVolume(0)

        assertFalse(manager.restoreMediaOnly())

        assertTrue(isHeadphonesOfferShown(context))
    }

    @Test
    fun `retireStaleHush clears the offer once the phone is audible again`() {
        manager.hush()
        postStandInHeadphonesOffer(context)
        // Un-hushed behind shhh's back, e.g. from the system volume panel.
        ring = 4

        manager.retireStaleHush()

        assertFalse(isHeadphonesOfferShown(context))
    }

    @Test
    fun `retireStaleHush leaves the offer alone while hushed`() {
        manager.hush()
        postStandInHeadphonesOffer(context)

        manager.retireStaleHush()

        assertTrue(isHeadphonesOfferShown(context))
    }

    // --------------------------------------------- an un-hush shhh never saw
    // A timed hush, then the ring raised from the system volume panel: shhh
    // reads as off, so the timer, its alarm and its countdown are all lies.

    @Test
    fun `retireStaleHush disarms a timer whose hush ended behind shhh's back`() {
        allowNotifications()
        manager.hush(durationMinutes = 30)
        assertTrue(isCountdownShown())
        ring = 4

        manager.retireStaleHush()

        assertEquals(0L, settings.timerEndMillis)
        assertEquals(0L, manager.activeTimerEnd)
        assertNull("the alarm must be cancelled", shadowOf(alarmManager).peekNextScheduledAlarm())
        assertFalse("the countdown must come down", isCountdownShown())
        assertEquals("the user's own ring level stays", 4, ring)
    }

    @Test
    fun `retireStaleHush leaves a running timer alone while hushed`() {
        allowNotifications()
        manager.hush(durationMinutes = 30)
        val armedEnd = settings.timerEndMillis

        manager.retireStaleHush()

        assertEquals(armedEnd, settings.timerEndMillis)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
        assertTrue(isCountdownShown())
    }

    @Test
    fun `retireStaleHush trusts the remembered hush under alarms only`() {
        // "Alarms only" reads every ring volume as 0, so only the remembered
        // state can say a hush is still on; the timer must survive the zen.
        manager.hush(durationMinutes = 30)
        val armedEnd = settings.timerEndMillis
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)

        manager.retireStaleHush()

        assertEquals(armedEnd, settings.timerEndMillis)
    }

    // ------------------------------------------------- exact-alarm access
    // A timed hush is only honest with the alarm that ends it. Without the
    // "Alarms & reminders" grant (the default on a fresh install) it used to
    // hush anyway and show a countdown that ran past zero forever.

    @Test
    fun `a timed hush without exact alarm access changes nothing`() {
        allowNotifications()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = manager.hush(durationMinutes = 1)

        assertEquals(QuietModeController.Result.NeedsExactAlarmAccess, result)
        assertFalse(manager.isQuiet)
        assertEquals(3, ring)
        assertEquals(5, media)
        assertEquals("nothing may be saved either", ShhhSettings.NO_SAVED_VOLUME, settings.previousRingVolume)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
        assertFalse(isCountdownShown())
    }

    @Test
    fun `hushUntil without exact alarm access changes nothing`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = manager.hushUntil(LocalDateTime.now().plusHours(2))

        assertEquals(QuietModeController.Result.NeedsExactAlarmAccess, result)
        assertFalse(manager.isQuiet)
        assertEquals(3, ring)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `an untimed hush needs no exact alarm access`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertEquals(QuietModeController.Result.Success(quiet = true), manager.hush())
        assertEquals(0, ring)
        assertEquals(0, media)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `a grant that vanishes after the check leaves the hush untimed with no countdown`() {
        // canScheduleExactAlarms says yes, then the schedule call is refused:
        // the hush already happened, but no timer or countdown may claim a
        // restore that no alarm will perform.
        allowNotifications()
        val refusing = mockk<AlarmManager>(relaxed = true)
        every { refusing.canScheduleExactAlarms() } returns true
        every {
            refusing.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>())
        } throws SecurityException("SCHEDULE_EXACT_ALARM revoked")
        val refusingContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.ALARM_SERVICE) refusing else super.getSystemService(name)
        }

        val result = HushManager(refusingContext).hush(durationMinutes = 30)

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(0, ring)
        assertEquals(0L, settings.timerEndMillis)
        assertFalse(isCountdownShown())
    }

    // --------------------------------------- restores respect audible sliders

    @Test
    fun `un-hushing a phone that is already audible keeps the user's volumes`() {
        // Measured: shhh off, media at 7, UNHUSH sent -> media dropped to the
        // 5 saved at the last hush.
        manager.hush()
        manager.unhush()
        media = 7
        ring = 6

        assertEquals(QuietModeController.Result.Success(quiet = false), manager.unhush())

        assertEquals(7, media)
        assertEquals(6, ring)
    }

    @Test
    fun `restoring now after raising the ring by hand still brings media back`() {
        manager.hush(durationMinutes = 30)
        ring = 6

        manager.unhush()

        assertEquals("the hand-set ring level stays", 6, ring)
        assertEquals("media still comes back", 5, media)
        assertEquals(0L, settings.timerEndMillis)
    }

    private fun allowNotifications() {
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notificationManager).setNotificationsEnabled(true)
    }

    /** CountdownNotifier posts under id 1. */
    private fun isCountdownShown(): Boolean =
        shadowOf(notificationManager).getNotification(1) != null

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
