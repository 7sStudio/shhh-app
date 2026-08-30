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
 * Every Do Not Disturb state the phone can be in, crossed with every shhh
 * operation. Two things are checked throughout: shhh reports the right hush
 * state, and shhh never moves the user's zen mode.
 *
 * The zen states differ in one way that drives all of it. Under plain
 * (priority) Do Not Disturb the ring stream is left alone, so the volume shhh
 * reads and writes is the user's real slider. Under "Alarms only" and "Total
 * silence" the zen mutes the ring stream and pins the INTERNAL ringer mode to
 * SILENT — which is both where [QuietModeController]'s remembered-state
 * fallback comes in and why it refuses to write the ring slider at all under
 * those two. See the tests at the bottom of this file for the mechanism.
 */
@RunWith(AndroidJUnit4::class)
class ZenStateMatrixIntegrationTest {

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

    // ---- isQuiet across every zen state ----

    @Test
    fun everyZenState_withShhhOff_neverReportsAFalseHush() {
        for (state in ZenState.entries) {
            device.resetShhhState(settings)
            device.resetToAudibleNoZen()

            device.applyZen(state)
            // "Alarms only" and "Total silence" drive the ring volume every
            // app reads to 0 even though the user never hushed anything; the
            // remembered state is the only truthful answer there.
            if (state.ownsRingVolume) {
                assertEquals("$state should mask the ring volume", 0, device.ringVolume)
            } else {
                assertEquals(
                    "$state must leave the ring volume readable",
                    DeviceAudio.DEFAULT_RING_VOLUME,
                    device.ringVolume
                )
            }
            assertFalse("$state alone must not read as hushed", controller.isQuiet)

            device.applyZen(ZenState.OFF)
            waitFor("ring volume handed back after $state") {
                device.ringVolume == DeviceAudio.DEFAULT_RING_VOLUME
            }
            assertFalse("$state ending must not read as hushed", controller.isQuiet)
        }
    }

    @Test
    fun everyZenState_hushedBeforeTheZen_staysHushedThroughItAndAfterIt() {
        for (state in ZenState.entries) {
            device.resetShhhState(settings)
            device.resetToAudibleNoZen()
            controller.goQuiet()
            assertTrue(controller.isQuiet)

            device.applyZen(state)
            assertTrue("the hush must survive $state starting", controller.isQuiet)

            device.applyZen(ZenState.OFF)
            assertTrue("the hush must survive $state ending", controller.isQuiet)
            assertEquals("still really hushed after $state", 0, device.ringVolume)
        }
    }

    // ---- The regression guard ----

    @Test
    fun hushAndUnhush_leaveTheInterruptionFilterUntouched() {
        // The reported bug in one assertion, for every zen state the phone
        // can be in — including "Alarms only" and "Total silence", where shhh
        // reaches the same result by not writing the ring slider at all.
        for (state in ZenState.entries) {
            device.resetShhhState(settings)
            device.resetToAudibleNoZen()
            device.applyZen(state)
            val filter = device.filter
            val zenMode = device.globalZenMode

            controller.goQuiet()
            Thread.sleep(SETTLE_MILLIS)
            assertEquals("hushing changed the filter under $state", filter, device.filter)
            assertEquals("hushing changed zen_mode under $state", zenMode, device.globalZenMode)

            controller.restoreSound()
            Thread.sleep(SETTLE_MILLIS)
            assertEquals("un-hushing changed the filter under $state", filter, device.filter)
            assertEquals("un-hushing changed zen_mode under $state", zenMode, device.globalZenMode)

            controller.toggle()
            controller.toggle()
            Thread.sleep(SETTLE_MILLIS)
            assertEquals("a toggle cycle changed the filter under $state", filter, device.filter)
            assertEquals(
                "a toggle cycle changed zen_mode under $state",
                zenMode,
                device.globalZenMode
            )
        }
    }

    @Test
    fun readingTheStateNeverMovesAnything() {
        // Every surface polls isQuiet/canChangeSound whenever it is drawn. A
        // read must be free of side effects on the phone's audio or zen state.
        for (state in ZenState.entries) {
            device.resetShhhState(settings)
            device.resetToAudibleNoZen()
            device.applyZen(state)
            val filter = device.filter
            val ring = device.ringVolume
            val media = device.mediaVolume

            repeat(5) {
                controller.isQuiet
                controller.canChangeSound
                controller.isDndActive
                HushManager(device.context).isQuiet
            }

            assertEquals("reading the state changed the filter under $state", filter, device.filter)
            assertEquals("reading the state changed the ring volume", ring, device.ringVolume)
            assertEquals("reading the state changed the media volume", media, device.mediaVolume)
            device.applyZen(ZenState.OFF)
        }
    }

    // ---- The remembered-state fallback ----

    @Test
    fun maskingZen_startingAndEndingWhileHushed_keepsTheHush() {
        // Shhh ON, then the user's "Alarms only" / "Total silence" comes and
        // goes on its own. The ring volume reads 0 the whole time — before,
        // during and after — so the remembered state must not drift.
        for (state in listOf(ZenState.ALARMS, ZenState.TOTAL_SILENCE)) {
            device.resetShhhState(settings)
            device.resetToAudibleNoZen()
            controller.goQuiet()

            device.applyZen(state)
            assertTrue(settings.lastKnownQuiet)
            assertTrue("hush lost while $state was running", controller.isQuiet)

            device.applyZen(ZenState.OFF)
            assertTrue("hush lost when $state ended", controller.isQuiet)
            assertTrue(settings.lastKnownQuiet)
            assertEquals(0, device.ringVolume)
        }
    }

    @Test
    fun maskingZen_startingAndEndingWhileAudible_neverInventsAHush() {
        // The mirror image: shhh OFF while "Alarms only" / "Total silence"
        // comes and goes. The zen zeroes the ring volume all by itself, and
        // reading that as a hush would leave the tile and widget stuck ON with
        // nothing to turn off.
        for (state in listOf(ZenState.ALARMS, ZenState.TOTAL_SILENCE)) {
            device.resetShhhState(settings)
            device.resetToAudibleNoZen()

            device.applyZen(state)
            assertFalse("$state invented a hush", controller.isQuiet)
            assertFalse(settings.lastKnownQuiet)

            device.applyZen(ZenState.OFF)
            waitFor("ring volume handed back after $state") {
                device.ringVolume == DeviceAudio.DEFAULT_RING_VOLUME
            }
            assertFalse("$state left a phantom hush behind", controller.isQuiet)
            assertFalse(settings.lastKnownQuiet)
        }
    }

    // ---- The zen modes that own the ring stream ----

    @Test
    fun hushingUnderAlarmsOnly_leavesTheZenRunning() {
        assertHushLeavesTheZenAlone(ZenState.ALARMS)
    }

    @Test
    fun hushingUnderTotalSilence_leavesTheZenRunning() {
        assertHushLeavesTheZenAlone(ZenState.TOTAL_SILENCE)
    }

    @Test
    fun unhushingUnderAlarmsOnly_leavesTheZenRunning() {
        assertRestoreLeavesTheZenAlone(ZenState.ALARMS)
    }

    @Test
    fun unhushingUnderTotalSilence_leavesTheZenRunning() {
        assertRestoreLeavesTheZenAlone(ZenState.TOTAL_SILENCE)
    }

    /**
     * "Alarms only" and "Total silence" own the ring stream outright, and shhh
     * must not write it while they run.
     *
     * Why: these two pin the INTERNAL ringer mode to SILENT (verified in
     * `dumpsys audio`: `mode (internal) = SILENT`, where priority Do Not
     * Disturb leaves it NORMAL and masks only the external mode). Writing the
     * ring volume makes AudioService recompute that internal mode — 0 becomes
     * VIBRATE on a device with a vibrator, anything above 0 becomes NORMAL —
     * and ZenModeHelper.onSetRingerModeInternal ends the zen the moment the
     * internal mode leaves SILENT under ZEN_MODE_ALARMS or
     * ZEN_MODE_NO_INTERRUPTIONS. The volume-slider path, safe under every other
     * zen, would still have killed these two.
     *
     * So QuietModeController.applyRingVolume skips the write entirely here.
     * Nothing is lost: the zen already silences the ring more deeply than a
     * hush would, and the platform hands the user's own ring volume back
     * untouched when the zen ends.
     */
    private fun assertHushLeavesTheZenAlone(state: ZenState) {
        device.applyZen(state)
        val filterBefore = device.filter
        val zenBefore = device.globalZenMode

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        // Give a zen exit every chance to land before declaring it did not.
        Thread.sleep(1_000)
        assertEquals("$state must survive the hush", filterBefore, device.filter)
        assertEquals(zenBefore, device.globalZenMode)
        assertTrue("the hush is still reported", controller.isQuiet)
        // Media is the only slider shhh may move here, and it did.
        assertEquals(0, device.mediaVolume)
    }

    /** Mirror of [assertHushLeavesTheZenAlone] for the un-hush direction. */
    private fun assertRestoreLeavesTheZenAlone(state: ZenState) {
        controller.goQuiet()
        device.applyZen(state)
        val filterBefore = device.filter
        val zenBefore = device.globalZenMode

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        Thread.sleep(1_000)
        assertEquals("$state must survive the un-hush", filterBefore, device.filter)
        assertEquals(zenBefore, device.globalZenMode)
        assertFalse("the hush is cleared", controller.isQuiet)
    }

    private companion object {
        /**
         * A zen change lands within a few hundred ms of the write that caused
         * it, so "nothing happened" has to be given the same chance to show up
         * as "something happened" would get from a polling wait.
         */
        const val SETTLE_MILLIS = 1_200L
    }
}
