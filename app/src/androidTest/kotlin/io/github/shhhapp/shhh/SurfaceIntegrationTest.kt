package io.github.shhhapp.shhh

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.widget.WidgetUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The surfaces a user actually taps, driven the way the system drives them:
 * the exported automation actions on [ToggleActivity], a real Quick Settings
 * tile click through `cmd statusbar click-tile`, and the snapshot state the
 * widget composition reads.
 *
 * Most of it runs while a Do Not Disturb mode is active, because that is where
 * the reported bug lived: whichever surface the user taps, the zen mode must be
 * exactly where they left it afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController

    private val tileComponent get() = "${device.context.packageName}/.tile.ShhhTileService"

    @Before
    fun setUp() {
        device = DeviceAudio()
        settings = ShhhSettings(device.context)
        controller = QuietModeController(device.context, settings)
        // Start every test with no tile in Quick Settings and the shade shut:
        // SystemUI holds on to a click it could not deliver and hands it over
        // the moment the tile comes back, which would toggle the phone in the
        // middle of an unrelated test.
        shell("cmd statusbar remove-tile $tileComponent")
        shell("cmd statusbar collapse")
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
    }

    @After
    fun tearDown() {
        shell("cmd statusbar remove-tile $tileComponent")
        shell("cmd statusbar collapse")
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    /**
     * Puts the tile in Quick Settings and waits until SystemUI has bound the
     * service. `cmd statusbar add-tile` returns before that: the
     * ServiceRecord's `app=` field stays null until the process is attached,
     * which makes the bind observable instead of guessed at.
     */
    private fun addTile() {
        shell("cmd statusbar add-tile $tileComponent")
        waitFor("Quick Settings bound the tile service", timeoutMillis = 10_000) {
            shell("dumpsys activity services $tileComponent").contains("app=ProcessRecord")
        }
    }

    /**
     * Taps the tile the way a user does: with the shade open. The tile hands
     * the work to a momentary foreground service rather than writing the
     * volume itself (its own process is background for audio purposes), and
     * that hand-off leaves the shade untouched — see TileShadeIntegrationTest,
     * which owns the shade-stays-open contract.
     */
    private fun clickTile() {
        shell("cmd statusbar expand-settings")
        shell("cmd statusbar click-tile $tileComponent")
    }

    /**
     * Taps the tile until the phone reaches [target], then leaves it there.
     *
     * A shell-driven click is not always one tap: SystemUI can both queue a
     * click for a pending binding AND dispatch it, and two deliveries toggle
     * the phone straight back. A real finger never does that, so the retry is
     * about the harness, not about the tile — a tile that does not act still
     * fails here, and every state in between is a settled one.
     */
    private fun clickTileUntilRingVolumeIs(target: Int) {
        repeat(TILE_CLICK_ATTEMPTS) {
            if (awaitSettledRingVolume() == target) return
            clickTile()
        }
        assertEquals(
            "the tile never brought the ring volume to $target",
            target,
            awaitSettledRingVolume()
        )
    }

    /**
     * Blocks until the ring volume has held the same value for a moment and
     * returns it. A tile click can produce more than one write (the in-place
     * attempt plus the trampoline retry), and asserting between them reads a
     * value nobody is meant to see.
     */
    private fun awaitSettledRingVolume(): Int {
        val deadline = System.currentTimeMillis() + 10_000
        var last = device.ringVolume
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - stableSince < STABLE_MILLIS) {
            Thread.sleep(50)
            val now = device.ringVolume
            if (now != last) {
                last = now
                stableSince = System.currentTimeMillis()
            }
            assertTrue(
                "the ring volume never stopped moving",
                System.currentTimeMillis() < deadline
            )
        }
        return last
    }

    /**
     * Starts the trampoline exactly as an automation app, launcher shortcut or
     * `am start` would: by its exported action, with no component-private API.
     *
     * Waits for any previous instance to be gone first. ToggleActivity is
     * `noHistory` and finishes itself in the same frame, and a second `am start`
     * that lands while the previous instance is still finishing is dropped by
     * the activity manager — the action never runs. A real user cannot tap
     * twice inside that window, so this is a harness concern, not a product
     * one; without the wait the second action in a flip-both-ways test
     * silently goes missing.
     */
    private fun sendAction(action: String, extras: String = "") {
        waitFor("no trampoline instance left running") {
            !shell("dumpsys activity activities").contains("/.ToggleActivity")
        }
        shell(
            "am start -a $action -n ${device.context.packageName}/.ToggleActivity $extras"
        )
    }

    // ---- ToggleActivity: the public automation surface ----

    @Test
    fun toggleActivity_hushAction_hushesWithoutTouchingTheUsersDnd() {
        device.applyZen(ZenState.PRIORITY)

        sendAction(ToggleActivity.ACTION_HUSH)

        device.awaitTransition(ring = 0, media = 0, what = "hushed by the HUSH action")
        assertTrue(controller.isQuiet)
        assertEquals("the user's DND must survive", ZenState.PRIORITY.filter, device.filter)
    }

    @Test
    fun toggleActivity_unhushAction_restoresWithoutTouchingTheUsersDnd() {
        controller.goQuiet()
        device.applyZen(ZenState.PRIORITY)

        sendAction(ToggleActivity.ACTION_UNHUSH)

        device.awaitTransition(
            ring = DeviceAudio.DEFAULT_RING_VOLUME,
            media = DeviceAudio.DEFAULT_MEDIA_VOLUME,
            what = "restored by the UNHUSH action"
        )
        assertFalse(controller.isQuiet)
        assertEquals("the user's DND must survive", ZenState.PRIORITY.filter, device.filter)
        assertEquals("1", device.globalZenMode)
    }

    @Test
    fun toggleActivity_toggleAction_flipsBothWays_andTheDndNeverMoves() {
        device.applyZen(ZenState.PRIORITY)
        val filter = device.filter

        sendAction(ToggleActivity.ACTION_TOGGLE)
        // Both sliders, not just the ring: the second tap must not be sent
        // while the first trampoline activity is still working, or `am start`
        // can drop it and the restore never happens.
        device.awaitTransition(ring = 0, media = 0, what = "hushed by the TOGGLE action")
        assertEquals(filter, device.filter)

        sendAction(ToggleActivity.ACTION_TOGGLE)
        device.awaitTransition(
            ring = DeviceAudio.DEFAULT_RING_VOLUME,
            media = DeviceAudio.DEFAULT_MEDIA_VOLUME,
            what = "restored by the TOGGLE action"
        )
        assertEquals(filter, device.filter)
        // The user-visible state, not the raw remembered flag: HushManager
        // fires every surface refresh off asynchronously, and those concurrent
        // reads can momentarily write the remembered flag back (see
        // BedtimeModeIntegrationTest.unhushDuringBedtime_leavesNoStaleQuietState,
        // which owns that assertion).
        assertFalse(controller.isQuiet)
    }

    @Test
    fun toggleActivity_restoreMediaAction_bringsMediaBackAndKeepsTheRingHushed() {
        // The headphones case: media should come back, the phone should stay
        // hushed for calls and notifications.
        controller.goQuiet()
        device.applyZen(ZenState.PRIORITY)

        sendAction(ToggleActivity.ACTION_RESTORE_MEDIA)

        waitFor("media restored") { device.mediaVolume == DeviceAudio.DEFAULT_MEDIA_VOLUME }
        assertEquals("the ring must stay hushed", 0, device.ringVolume)
        assertTrue(controller.isQuiet)
        assertEquals(ZenState.PRIORITY.filter, device.filter)
    }

    @Test
    fun toggleActivity_hushActionWithADuration_armsTheTimer() {
        sendAction(ToggleActivity.ACTION_HUSH, "--ei ${ToggleActivity.EXTRA_DURATION_MINUTES} 30")

        waitFor("hushed by the timed HUSH action") { device.ringVolume == 0 }
        waitFor("timer armed") { settings.timerEndMillis > System.currentTimeMillis() }

        sendAction(ToggleActivity.ACTION_UNHUSH)
        waitFor("timer disarmed by the un-hush") { settings.timerEndMillis == 0L }
    }

    // ---- Quick Settings tile ----

    @Test
    fun quickSettingsTile_hushesAndUnhushes_underDnd_withoutEndingIt() {
        addTile()
        device.applyZen(ZenState.PRIORITY)
        val filter = device.filter

        // Under an active zen the tile deliberately routes through the visible
        // ToggleActivity trampoline: Android 16+ audio hardening silently drops
        // volume writes made from a background TileService.
        clickTileUntilRingVolumeIs(0)
        assertTrue(controller.isQuiet)
        assertEquals(0, device.mediaVolume)
        assertEquals("the tile must not end the user's DND", filter, device.filter)

        clickTileUntilRingVolumeIs(DeviceAudio.DEFAULT_RING_VOLUME)
        assertFalse(controller.isQuiet)
        assertEquals(DeviceAudio.DEFAULT_MEDIA_VOLUME, device.mediaVolume)
        assertEquals("the tile must not end the user's DND", filter, device.filter)
        assertEquals("1", device.globalZenMode)
    }

    @Test
    fun quickSettingsTile_hushesAndUnhushes_withNoZenRunning() {
        addTile()

        clickTileUntilRingVolumeIs(0)
        assertTrue(controller.isQuiet)
        assertEquals(0, device.mediaVolume)

        clickTileUntilRingVolumeIs(DeviceAudio.DEFAULT_RING_VOLUME)
        assertFalse(controller.isQuiet)
        assertEquals(DeviceAudio.DEFAULT_MEDIA_VOLUME, device.mediaVolume)
    }

    // ---- Widget ----

    @Test
    fun widgetUiState_mirrorsTheHushState_andIsNotFooledByDnd() {
        WidgetUiState.refreshFrom(device.context)
        assertFalse(WidgetUiState.quiet)
        assertTrue(WidgetUiState.canChangeSound)

        controller.goQuiet()
        WidgetUiState.refreshFrom(device.context)
        assertTrue(WidgetUiState.quiet)

        // A Do Not Disturb mode arriving on its own must not flip the widget:
        // the user did not hush anything.
        controller.restoreSound()
        device.applyZen(ZenState.PRIORITY)
        WidgetUiState.refreshFrom(device.context)
        assertFalse("DND alone must not light the widget up", WidgetUiState.quiet)
        assertTrue("access is granted, so the widget still acts", WidgetUiState.canChangeSound)
    }

    @Test
    fun widgetUiState_routesToTheAppWhenAZenRunsWithoutAccess() {
        // canChangeSound is what picks the widget's click target: the toggle
        // trampoline when shhh can act, the app (to explain and offer the
        // grant) when Android would refuse every write.
        device.applyZen(ZenState.PRIORITY)
        device.revokeDndAccess()
        try {
            WidgetUiState.refreshFrom(device.context)
            assertFalse(WidgetUiState.canChangeSound)
        } finally {
            device.grantDndAccess()
        }

        WidgetUiState.refreshFrom(device.context)
        assertTrue(WidgetUiState.canChangeSound)
    }

    private companion object {
        /** How long the ring volume must hold still to count as settled. */
        const val STABLE_MILLIS = 800L

        /** Taps allowed before the tile is declared unresponsive. */
        const val TILE_CLICK_ATTEMPTS = 4
    }
}
