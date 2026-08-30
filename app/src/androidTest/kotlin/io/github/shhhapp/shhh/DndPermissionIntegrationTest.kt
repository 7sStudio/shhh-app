package io.github.shhhapp.shhh

import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * What shhh can and cannot do without the optional Do Not Disturb access.
 *
 * The grant unlocks exactly one thing: moving the ring volume across the silent
 * boundary WHILE a zen mode is running. AudioService throws
 * SecurityException("Not allowed to change Do Not Disturb state") there. With no
 * zen running the same writes go through with no permission at all, which is
 * why the permission is optional and why [QuietModeController.canChangeSound]
 * is `hasDndAccess || !isDndActive` rather than just `hasDndAccess`.
 */
@RunWith(AndroidJUnit4::class)
class DndPermissionIntegrationTest {

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
        // The grant is global device state: hand it back however the test ended.
        device.grantDndAccess()
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    @Test
    fun withoutAccess_andNoZenRunning_hushAndUnhushBothSucceed() {
        device.revokeDndAccess()
        assertFalse(controller.hasDndAccess)
        assertTrue("no zen is running, so nothing is refused", controller.canChangeSound)

        assertEquals(QuietModeController.Result.Success(quiet = true), controller.goQuiet())
        assertEquals(0, device.ringVolume)
        assertEquals(0, device.mediaVolume)

        assertEquals(QuietModeController.Result.Success(quiet = false), controller.restoreSound())
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
        assertEquals(DeviceAudio.DEFAULT_MEDIA_VOLUME, device.mediaVolume)
    }

    @Test
    fun withoutAccess_duringDnd_hushIsRefusedAndChangesNothing() {
        device.applyZen(ZenState.PRIORITY)
        device.revokeDndAccess()
        assertFalse(controller.canChangeSound)

        assertEquals(QuietModeController.Result.NeedsDndAccess, controller.goQuiet())

        assertEquals(
            "the ring volume must be untouched",
            DeviceAudio.DEFAULT_RING_VOLUME,
            device.ringVolume
        )
        assertEquals(
            "the media volume must be untouched",
            DeviceAudio.DEFAULT_MEDIA_VOLUME,
            device.mediaVolume
        )
        assertFalse("a refused hush must not be remembered as applied", settings.lastKnownQuiet)
        assertFalse(controller.isQuiet)
        assertEquals("and the refusal must not disturb the zen", ZenState.PRIORITY.filter, device.filter)
    }

    @Test
    fun withoutAccess_duringDnd_unhushIsRefusedAndChangesNothing() {
        controller.goQuiet()
        device.applyZen(ZenState.PRIORITY)
        device.revokeDndAccess()

        assertEquals(QuietModeController.Result.NeedsDndAccess, controller.restoreSound())

        assertEquals("the phone must still be hushed", 0, device.ringVolume)
        assertTrue("a refused un-hush must not be remembered as applied", settings.lastKnownQuiet)
        assertTrue(controller.isQuiet)
        assertEquals(ZenState.PRIORITY.filter, device.filter)
    }

    @Test
    fun withoutAccess_underEveryZenState_theRefusalIsCorrectlyPredicted() {
        // canChangeSound is what the toggle, the timer chips, the tile and the
        // widget all gate on; it has to agree with what the write actually
        // does under every zen state.
        for (state in ZenState.entries) {
            device.resetShhhState(settings)
            device.resetToAudibleNoZen()
            device.applyZen(state)
            device.revokeDndAccess()

            // Android refuses a ring-volume write across the silent boundary
            // only while a zen is running. "Alarms only" and "Total silence"
            // never reach that write — shhh must not touch the ring under them
            // at all, or it would end the zen — so the media-only hush goes
            // through with no grant at all. Refusal is therefore exactly the
            // zen states that are active AND do not own the ring.
            val expectRefusal = state != ZenState.OFF && !state.ownsRingVolume
            assertEquals(
                "canChangeSound disagrees with reality under $state",
                !expectRefusal,
                controller.canChangeSound
            )
            val result = controller.goQuiet()
            if (expectRefusal) {
                assertEquals("$state should refuse", QuietModeController.Result.NeedsDndAccess, result)
            } else {
                assertEquals(
                    "$state should allow",
                    QuietModeController.Result.Success(quiet = true),
                    result
                )
            }

            device.grantDndAccess()
            device.applyZen(ZenState.OFF)
        }
    }

    @Test
    fun grantingAccessBackMakesTheRefusedHushWork() {
        device.applyZen(ZenState.PRIORITY)
        device.revokeDndAccess()
        assertEquals(QuietModeController.Result.NeedsDndAccess, controller.goQuiet())

        device.grantDndAccess()

        assertTrue(controller.canChangeSound)
        assertEquals(QuietModeController.Result.Success(quiet = true), controller.goQuiet())
        assertEquals(0, device.ringVolume)
        assertEquals("and still without touching the user's zen", ZenState.PRIORITY.filter, device.filter)
    }
}
