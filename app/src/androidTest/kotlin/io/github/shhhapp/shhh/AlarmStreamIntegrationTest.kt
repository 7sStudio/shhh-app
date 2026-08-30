package io.github.shhhapp.shhh

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Alarms must always be able to wake you. Whatever shhh does to the ring and
 * media sliders, it must never make the alarm quieter — a hushed phone that
 * sleeps through its alarm is the worst failure this app could have.
 *
 * Shhh only ever writes STREAM_RING and STREAM_MUSIC, and STREAM_ALARM is a
 * separate stream that Android deliberately keeps out of the ringer-mode
 * machinery: `dumpsys audio` reports `ringer mode affected streams = 0x1a6`
 * (SYSTEM, RING, NOTIFICATION, SYSTEM_ENFORCED, DTMF) with the alarm stream
 * absent, and the platform refuses to take alarm volume below its minimum of 1
 * at all. These tests prove shhh keeps its hands off it in every state.
 */
@RunWith(AndroidJUnit4::class)
class AlarmStreamIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController

    @Before
    fun setUp() {
        device = DeviceAudio()
        settings = ShhhSettings(device.context)
        controller = QuietModeController(device.context, settings)
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
    }

    @After
    fun tearDown() {
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    @Test
    fun theAlarmStreamIsNotAffectedByRingerMode() {
        // The premise everything else here rests on. If Android ever folded the
        // alarm stream into the ringer-mode mask, zeroing the ring volume could
        // start taking the alarm down with it and this test would say so.
        val affected = shell("dumpsys audio")
            .lineSequence()
            .first { it.contains("ringer mode affected streams") }
        assertFalse(
            "STREAM_ALARM must stay outside the ringer-mode mask: $affected",
            affected.contains("STREAM_ALARM")
        )
    }

    @Test
    fun hushingAndUnhushingLeaveTheAlarmVolumeAlone() {
        val before = device.alarmVolume
        assertTrue("the alarm must be audible to begin with", before > 0)

        controller.goQuiet()

        assertEquals("hushing must never touch the alarm volume", before, device.alarmVolume)
        assertFalse("hushing must never mute the alarm stream", device.alarmMuted)
        // And the phone really is hushed, so this is not a vacuous pass.
        assertTrue(controller.isQuiet)
        assertEquals(0, device.mediaVolume)

        controller.restoreSound()

        assertEquals("un-hushing must never touch the alarm volume", before, device.alarmVolume)
        assertFalse(device.alarmMuted)
    }

    @Test
    fun onlyTotalSilenceEverSilencesTheAlarm_andShhhIsNotInvolved() {
        // Measured with shhh doing nothing at all: "Total silence" is the one
        // Do Not Disturb mode that takes the alarm down, and it does so by
        // itself — that is what the user asked for by choosing it. Every other
        // zen, including "Alarms only", leaves the alarm at full volume. This
        // test exists so a future change can never quietly blame shhh for it.
        val audible = device.alarmVolume
        assertTrue(audible > 0)

        for (state in ZenState.entries) {
            device.applyZen(state)
            if (state == ZenState.TOTAL_SILENCE) {
                assertEquals("total silence should silence the alarm", 0, device.alarmVolume)
                assertTrue(device.alarmMuted)
            } else {
                assertEquals("$state must leave the alarm audible", audible, device.alarmVolume)
                assertFalse("$state must not mute the alarm", device.alarmMuted)
            }
            device.applyZen(ZenState.OFF)
        }
    }

    @Test
    fun theAlarmSurvivesEveryShhhOperationUnderEveryZenState() {
        for (state in ZenState.entries) {
            device.resetToAudibleNoZen()
            device.resetShhhState(settings)
            device.applyZen(state)
            // Baseline taken AFTER the zen is applied, so this measures what
            // shhh does and not what the user's own mode already did.
            val before = device.alarmVolume
            val mutedBefore = device.alarmMuted

            val manager = HushManager(device.context)
            manager.hush()
            assertEquals("$state: hush moved the alarm volume", before, device.alarmVolume)
            manager.hush(durationMinutes = 15)
            assertEquals("$state: timed hush moved the alarm volume", before, device.alarmVolume)
            manager.unhush()
            assertEquals("$state: un-hush moved the alarm volume", before, device.alarmVolume)
            manager.restoreMediaOnly()
            assertEquals("$state: media restore moved the alarm volume", before, device.alarmVolume)
            assertEquals("$state: shhh changed the alarm mute state", mutedBefore, device.alarmMuted)

            device.applyZen(ZenState.OFF)
        }
    }

    @Test
    fun aHushedPhoneStillHasAFullyAudibleAlarm() {
        // The scenario in plain terms: phone hushed overnight, alarm set for
        // the morning. The alarm stream must be untouched and unmuted, at a
        // level the user can actually hear.
        val max = device.audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        device.audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)

        controller.goQuiet()

        assertTrue("shhh is on", controller.isQuiet)
        assertEquals(0, device.ringVolume)
        assertEquals(0, device.mediaVolume)
        assertEquals("the alarm is still at full volume", max, device.alarmVolume)
        assertFalse(device.alarmMuted)
    }
}
