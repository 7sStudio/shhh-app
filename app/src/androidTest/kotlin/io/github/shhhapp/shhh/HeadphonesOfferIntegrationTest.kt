package io.github.shhhapp.shhh

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.audio.HeadsetReceiver
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
 * The reported leftover, against the real NotificationManagerService: hush,
 * connect headphones, leave the "tap to bring media volume back" offer
 * untapped, un-hush later — and the offer was still sitting in the shade.
 *
 * The offer is posted through the receiver's own code path (the Bluetooth
 * broadcast itself is protected and cannot be sent from a test), and every
 * un-hush goes through the same exported surfaces a user's tap does.
 */
@RunWith(AndroidJUnit4::class)
class HeadphonesOfferIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController

    private val shownIds: Set<Int>
        get() = device.notificationManager.activeNotifications.map { it.id }.toSet()

    private val offerShown: Boolean
        get() = HeadsetReceiver.NOTIFICATION_ID in shownIds

    @Before
    fun setUp() {
        device = DeviceAudio()
        settings = ShhhSettings(device.context)
        controller = QuietModeController(device.context, settings)
        shell("pm grant ${device.context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("cmd statusbar collapse")
        device.notificationManager.cancelAll()
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
        // Timed hushes need the grant (without it they change nothing).
        device.allowExactAlarms()
    }

    @After
    fun tearDown() {
        HushManager(device.context).unhush()
        device.notificationManager.cancelAll()
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    private fun postOffer() {
        HeadsetReceiver.postRestoreOffer(device.context)
        waitFor("headphones offer posted") { offerShown }
    }

    /** See SurfaceIntegrationTest.sendAction for why the wait comes first. */
    private fun sendAction(action: String, extras: String = "") {
        waitFor("no trampoline instance left running") {
            !isActivityAlive("ToggleActivity")
        }
        shell(
            "am start -a $action -n ${device.context.packageName}/.ToggleActivity $extras"
        )
    }

    @Test
    fun unhushAction_clearsAnUntappedOffer() {
        controller.goQuiet()
        postOffer()

        sendAction(ToggleActivity.ACTION_UNHUSH)

        device.awaitTransition(
            ring = DeviceAudio.DEFAULT_RING_VOLUME,
            media = DeviceAudio.DEFAULT_MEDIA_VOLUME,
            what = "restored by the UNHUSH action"
        )
        waitFor("headphones offer cleared by the un-hush") { !offerShown }
    }

    @Test
    fun toggleAction_clearsTheOfferOnTheWayOut_andKeepsItOnTheWayIn() {
        sendAction(ToggleActivity.ACTION_TOGGLE)
        device.awaitTransition(ring = 0, media = 0, what = "hushed by the TOGGLE action")
        postOffer()

        sendAction(ToggleActivity.ACTION_TOGGLE)

        device.awaitTransition(
            ring = DeviceAudio.DEFAULT_RING_VOLUME,
            media = DeviceAudio.DEFAULT_MEDIA_VOLUME,
            what = "restored by the TOGGLE action"
        )
        waitFor("headphones offer cleared by the toggle") { !offerShown }
    }

    @Test
    fun unhushingATimedHush_leavesNoShhhNotificationBehind() {
        sendAction(ToggleActivity.ACTION_HUSH, "--ei ${ToggleActivity.EXTRA_DURATION_MINUTES} 30")
        device.awaitTransition(ring = 0, media = 0, what = "hushed by the timed HUSH action")
        waitFor("countdown posted") { COUNTDOWN_NOTIFICATION_ID in shownIds }
        postOffer()

        sendAction(ToggleActivity.ACTION_UNHUSH)

        waitFor("every shhh notification cleared") { shownIds.isEmpty() }
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun restoreMediaAction_retiresTheOffer_andStaysHushed() {
        controller.goQuiet()
        postOffer()

        // What tapping the offer itself does.
        sendAction(ToggleActivity.ACTION_RESTORE_MEDIA)

        waitFor("media restored") { device.mediaVolume == DeviceAudio.DEFAULT_MEDIA_VOLUME }
        waitFor("headphones offer retired by the restore") { !offerShown }
        assertEquals("the ring must stay hushed", 0, device.ringVolume)
        assertTrue(controller.isQuiet)
    }

    @Test
    fun openingTheApp_afterAnUnhushShhhNeverSaw_clearsTheStaleOffer() {
        controller.goQuiet()
        postOffer()
        // The system volume panel, not shhh: no transition ran, nothing told
        // the notification to leave.
        device.audioManager.setStreamVolume(
            android.media.AudioManager.STREAM_RING, DeviceAudio.DEFAULT_RING_VOLUME, 0
        )
        waitFor("ring raised by hand") { device.ringVolume == DeviceAudio.DEFAULT_RING_VOLUME }
        assertTrue("nothing has re-read the phone yet", offerShown)

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("stale offer cleared when the app re-read the phone") { !offerShown }
        }
    }

    @Test
    fun openingTheApp_whileStillHushed_keepsTheOffer() {
        controller.goQuiet()
        postOffer()

        ActivityScenario.launch(MainActivity::class.java).use {
            // Give a wrong cancel every chance to land before asserting.
            assertFalse(
                "still hushed with media muted: the offer is still true",
                waitUntilOrGiveUp(timeoutMillis = 1_500) { !offerShown }
            )
        }
    }

    private companion object {
        /** CountdownNotifier's private slot; pinned here on purpose. */
        const val COUNTDOWN_NOTIFICATION_ID = 1
    }
}
