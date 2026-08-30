package io.github.shhhapp.shhh

import android.app.NotificationManager
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
 * Integration tests against the REAL AudioManager on a device/emulator, with no
 * Do Not Disturb running and under plain (priority) Do Not Disturb — the state
 * the user's bug report was filed from.
 *
 * Do Not Disturb access is granted through the instrumentation shell identity,
 * the same switch a user flips in Settings — no root involved.
 */
@RunWith(AndroidJUnit4::class)
class QuietModeIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var audioManager: AudioManager
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController

    @Before
    fun setUp() {
        device = DeviceAudio()
        audioManager = device.audioManager
        settings = ShhhSettings(device.context)
        controller = QuietModeController(device.context, settings)
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
        assertTrue("DND access was not granted", controller.hasDndAccess)
    }

    @After
    fun tearDown() {
        // Leave the device sounding normal for the next test/user.
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    private fun enableDnd() = device.applyZen(ZenState.PRIORITY)

    // ---- No Do Not Disturb running ----

    @Test
    fun goQuiet_zeroesRingAndMedia_onRealDevice() {
        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(0, device.ringVolume)
        assertEquals(0, device.mediaVolume)
        // A ring volume of 0 IS vibrate on a device with a vibrator: the
        // ringer mode shhh leaves behind is a consequence of the slider, never
        // something it sets. SILENT is unreachable this way, which is exactly
        // why the "Silent" hush option was removed — reaching it needed
        // setRingerMode(SILENT), and that starts a zen mode.
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
    }

    @Test
    fun goQuiet_savesThePreviousRingVolume_andRestoreBringsItBack() {
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 6, 0)
        waitFor("ring at 6") { device.ringVolume == 6 }

        controller.goQuiet()
        assertEquals(6, settings.previousRingVolume)
        assertEquals(0, device.ringVolume)

        controller.restoreSound()
        assertEquals(6, device.ringVolume)
    }

    @Test
    fun restore_withNoSavedRingVolume_fallsBackToHalfOfMax() {
        // The phone arrives hushed with nothing remembered — a fresh install
        // whose first action is "un-hush", or a wiped preferences file.
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        waitFor("ring at 0") { device.ringVolume == 0 }
        settings.previousRingVolume = ShhhSettings.NO_SAVED_VOLUME

        controller.restoreSound()

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        assertEquals((max / 2).coerceAtLeast(1), device.ringVolume)
    }

    @Test
    fun fullToggleCycle_restoresPreviousVolume_onRealDevice() {
        controller.toggle()
        assertTrue(controller.isQuiet)
        assertEquals(0, device.ringVolume)
        assertEquals(0, device.mediaVolume)

        controller.toggle()
        assertFalse(controller.isQuiet)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
        assertEquals(DeviceAudio.DEFAULT_MEDIA_VOLUME, device.mediaVolume)
    }

    @Test
    fun isQuiet_followsTheRingSliderMovedOutsideShhh() {
        // isQuiet reads the same slider the volume keys and Settings move, so
        // the toggle can never drift out of sync with the phone.
        shell("cmd audio set-volume 2 0")
        waitFor("ring silenced from outside shhh") { device.ringVolume == 0 }
        assertTrue(controller.isQuiet)
        assertTrue("the observed state is remembered too", settings.lastKnownQuiet)

        shell("cmd audio set-volume 2 3")
        waitFor("ring audible again from outside shhh") { device.ringVolume == 3 }
        assertFalse(controller.isQuiet)
        assertFalse(settings.lastKnownQuiet)
    }

    // ---- Do Not Disturb ----

    @Test
    fun dnd_masksTheExternalRingerAsSilent_butLeavesTheRingVolumeTruthful() {
        // Pins the premise the whole design rests on: while zen is active,
        // AudioService reports the ringer MODE as SILENT to apps even though
        // the real ringer is untouched — which is why shhh stopped reading it
        // — while the ring VOLUME under plain (priority) Do Not Disturb keeps
        // reporting the user's real slider position, which is why shhh reads
        // that instead. The mask lands asynchronously a moment after the
        // filter flips, so wait for it rather than asserting a race.
        enableDnd()

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
    }

    @Test
    fun dndAlone_doesNotReadAsQuiet_onRealDevice() {
        enableDnd()

        assertFalse(controller.isQuiet)
    }

    @Test
    fun hushEngagedBeforeDnd_staysQuietDuringDnd_onRealDevice() {
        controller.goQuiet()

        enableDnd()

        assertTrue(controller.isQuiet)
    }

    @Test
    fun hushDuringDnd_zeroesTheRingVolume_andKeepsDndAlive_onRealDevice() {
        enableDnd()

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertTrue(controller.isQuiet)
        // Under priority Do Not Disturb the ring volume is a real, writable
        // slider: hushing genuinely zeroes it (the old ringer-mode
        // implementation left it untouched here).
        assertEquals(0, device.ringVolume)
        assertEquals(0, device.mediaVolume)
        // A ringer write here would make AudioService exit the user's DND.
        assertTrue("hushing must not end the user's DND mode", controller.isDndActive)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, device.filter)
    }

    @Test
    fun restoreDuringDnd_bringsSoundBack_andTheUsersDndSurvives_onRealDevice() {
        controller.goQuiet()
        enableDnd()

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertFalse(controller.isQuiet)
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
        // THE REPORTED BUG, inverted: restoring sound used to call
        // setRingerMode(NORMAL), AOSP's external ringer path, which ends
        // whatever zen mode the user has running. Sliders only now, so the
        // user's Do Not Disturb has to still be there afterwards.
        assertTrue("the user's DND must survive an un-hush", controller.isDndActive)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, device.filter)
        assertEquals("1", device.globalZenMode)
        // And it must stay: the zen exit used to land a few hundred ms late.
        Thread.sleep(1_500)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, device.filter)
    }

    @Test
    fun theReportedBug_dndOnHushOnHushOff_leavesDndExactlyAsItWas() {
        // The user's exact repro, through the shipping entry point every
        // surface uses: Do Not Disturb on, shhh off, toggle on, toggle off.
        // Before the fix this turned the user's Do Not Disturb off.
        val manager = HushManager(device.context)
        enableDnd()
        val filterBefore = device.filter
        val zenBefore = device.globalZenMode

        assertEquals(QuietModeController.Result.Success(quiet = true), manager.toggle())
        assertTrue(manager.isQuiet)

        assertEquals(QuietModeController.Result.Success(quiet = false), manager.toggle())

        assertFalse(manager.isQuiet)
        assertEquals("the interruption filter must be untouched", filterBefore, device.filter)
        assertEquals("zen_mode must be untouched", zenBefore, device.globalZenMode)
        assertEquals("1", device.globalZenMode)
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
        assertEquals(DeviceAudio.DEFAULT_MEDIA_VOLUME, device.mediaVolume)
        assertFalse("no stale hush left behind", settings.lastKnownQuiet)
    }

    @Test
    fun ringerModeBroadcast_stopsBeingEmittedWhileDndIsRunning() {
        // Pins a platform behaviour the live-updating surfaces depend on.
        // MainActivity and ShhhTileService keep the open UI in sync by
        // listening to RINGER_MODE_CHANGED_ACTION, which reports the EXTERNAL
        // ringer mode. With no zen running, a ring-volume change across 0 moves
        // that mode and the broadcast arrives. Under a zen the external mode is
        // pinned to SILENT, so the same change produces NO broadcast at all —
        // which is why those surfaces also listen to
        // ACTION_INTERRUPTION_FILTER_CHANGED, and why a volume change made
        // elsewhere during Do Not Disturb does not reach an already-open
        // screen until something else wakes it.
        val ringerBroadcasts = java.util.concurrent.atomic.AtomicInteger()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                ringerBroadcasts.incrementAndGet()
            }
        }
        device.context.registerReceiver(
            receiver,
            android.content.IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            android.content.Context.RECEIVER_NOT_EXPORTED
        )
        try {
            ringerBroadcasts.set(0)
            controller.goQuiet()
            assertTrue(
                "no zen running: the ringer-mode broadcast must arrive",
                waitUntilOrGiveUp { ringerBroadcasts.get() > 0 }
            )
            controller.restoreSound()

            enableDnd()
            Thread.sleep(500)
            ringerBroadcasts.set(0)
            controller.goQuiet()
            waitFor("hushed under DND") { device.ringVolume == 0 }
            assertFalse(
                "under DND the external ringer mode is pinned to SILENT, so no " +
                    "ringer-mode broadcast should be emitted",
                waitUntilOrGiveUp(timeoutMillis = 2_000) { ringerBroadcasts.get() > 0 }
            )
        } finally {
            device.context.unregisterReceiver(receiver)
        }
    }

    // ---- The invariant the fix rests on ----

    @Test
    fun shhhNeverCallsSetRingerMode_acrossFullCyclesWithAndWithoutDnd() {
        // AudioService's volume-event log attributes every ringer-mode change
        // to its caller: an app calling AudioManager.setRingerMode shows up as
        // "setRingerMode external to N, caller=<package>", while the internal
        // mode changes AudioService derives from a stream-volume write are
        // logged against AudioService itself. Shhh must never appear there —
        // the external path is the one that ends the user's zen mode.
        val before = device.ringerModeEventsFromThisApp()

        controller.goQuiet()
        controller.restoreSound()
        enableDnd()
        controller.goQuiet()
        controller.restoreSound()
        Thread.sleep(500)

        val after = device.ringerModeEventsFromThisApp()
        assertEquals(
            "shhh asked AudioService to change the ringer mode: ${after - before.toSet()}",
            emptyList<String>(),
            after - before.toSet()
        )
        // Proof the log would have caught it: shhh's stream writes ARE there.
        assertTrue(
            "the volume-event log did not record shhh's stream writes, so its " +
                "silence about setRingerMode proves nothing",
            device.volumeEvents().any {
                it.contains("setStreamVolume") && it.contains("from ${device.context.packageName}")
            }
        )
    }
}
