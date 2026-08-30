package io.github.shhhapp.shhh

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.After
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Quick Settings toggle must behave like Wi-Fi, Bluetooth or the torch: it
 * flips, and it leaves the panel exactly where the user left it.
 *
 * Shhh used to fail that. A tile can only start an activity through
 * `startActivityAndCollapse`, which closes the shade by definition, and the
 * tile bounced every tap through the invisible ToggleActivity because its own
 * volume write is silently dropped — a tile's process is background for audio
 * purposes under Android 16+ audio hardening. Result: the panel slammed shut
 * on every single tap.
 *
 * The work now goes to a momentary foreground service, which is allowed to
 * change volume and has no UI at all, so nothing touches the shade.
 */
@RunWith(AndroidJUnit4::class)
class TileShadeIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController

    companion object {
        private const val TILE = "io.github.shhhapp.shhh/.tile.ShhhTileService"

        /**
         * The tile is added ONCE for the whole class, the way a user has it.
         *
         * Adding and removing it per test churns the Quick Settings panel:
         * SystemUI rebuilds its tile layout, and an expand issued during that
         * rebuild can be cancelled, which reads as "the tile closed the shade"
         * when nothing of the sort happened.
         *
         * `cmd statusbar add-tile` also returns before SystemUI has bound the
         * service — the ServiceRecord's `app=` field stays null until the
         * process attaches, which makes the bind observable instead of guessed
         * at.
         */
        @JvmStatic
        @BeforeClass
        fun addTile() {
            shell("cmd statusbar collapse")
            shell("cmd statusbar add-tile $TILE")
            waitFor("Quick Settings bound the tile service", timeoutMillis = 15_000) {
                shell("dumpsys activity services $TILE").contains("app=ProcessRecord")
            }
        }

        @JvmStatic
        @AfterClass
        fun removeTile() {
            shell("cmd statusbar collapse")
            shell("cmd statusbar remove-tile $TILE")
        }
    }

    @Before
    fun setUp() {
        device = DeviceAudio()
        settings = ShhhSettings(device.context)
        controller = QuietModeController(device.context, settings)
        closeShade()
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
    }

    @After
    fun tearDown() {
        closeShade()
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    /**
     * Opens the panel and waits until it is *stably* open — two reads a beat
     * apart — so a tap is never sent into the middle of the expand animation.
     */
    private fun openShade() {
        waitFor("shade open", timeoutMillis = 15_000) {
            if (!isShadeOpen()) shell("cmd statusbar expand-settings")
            if (!isShadeOpen()) return@waitFor false
            Thread.sleep(300)
            isShadeOpen()
        }
    }

    /**
     * A collapse issued while the expand animation is still running is dropped
     * by SystemUI, so the command is re-sent until the panel is actually down.
     */
    private fun closeShade() {
        waitFor("shade closed") {
            if (isShadeOpen()) shell("cmd statusbar collapse")
            !isShadeOpen()
        }
    }

    /**
     * Taps the tile and waits for the WHOLE transition to settle, without
     * touching the shade.
     *
     * Waiting on `isQuiet` alone is not enough: it reads the ring slider, and
     * goQuiet/restoreSound move the ring first and the media volume after it,
     * so there is a window where the hush reads as applied while media is
     * still mid-flight. Both halves have to land before a test may look.
     */
    private fun tapTileAndAwait(expectedQuiet: Boolean) {
        shell("cmd statusbar click-tile $TILE")
        waitFor("tile applied quiet=$expectedQuiet", timeoutMillis = 10_000) {
            controller.isQuiet == expectedQuiet &&
                if (expectedQuiet) device.mediaVolume == 0 else device.mediaVolume > 0
        }
    }

    @Test
    fun theShadeDetectorItselfWorks() {
        // A test that asserts "still open" is worthless if the detector can
        // only ever say "open", so prove it reports both states.
        openShade()
        assertTrue(isShadeOpen())
        closeShade()
        assertFalse("the detector must be able to report a closed shade", isShadeOpen())
    }

    @Test
    fun tappingTheTileTogglesShhhAndLeavesTheShadeOpen() {
        openShade()
        val ringBefore = device.ringVolume
        assertNotEquals("the phone must be audible to begin with", 0, ringBefore)

        tapTileAndAwait(expectedQuiet = true)

        assertEquals("the tap must hush the phone", 0, device.ringVolume)
        assertEquals(0, device.mediaVolume)
        assertTrue("the shade must stay open after hushing", isShadeOpen())

        tapTileAndAwait(expectedQuiet = false)

        assertEquals("the tap must restore the ring volume", ringBefore, device.ringVolume)
        assertTrue("the shade must stay open after un-hushing", isShadeOpen())
    }

    @Test
    fun repeatedTapsNeverCloseTheShade() {
        // The annoyance the user reported is cumulative: every tap re-opening
        // the panel is the thing that makes it unusable.
        openShade()
        var expected = true
        repeat(6) { tap ->
            tapTileAndAwait(expectedQuiet = expected)
            assertTrue("the shade closed on tap ${tap + 1}", isShadeOpen())
            expected = !expected
        }
    }

    @Test
    fun theShadeStaysOpenUnderEveryZenState() {
        // Under a zen mode the tile used to take the trampoline unconditionally,
        // so this was the worst case of all.
        for (state in ZenState.entries) {
            device.resetToAudibleNoZen()
            device.resetShhhState(settings)
            device.applyZen(state)
            openShade()
            val quietBefore = controller.isQuiet

            tapTileAndAwait(expectedQuiet = !quietBefore)

            assertTrue("$state closed the shade", isShadeOpen())
            assertEquals("$state: shhh must not move the zen", state.filter, device.filter)
            device.applyZen(ZenState.OFF)
        }
    }

    @Test
    fun aTileTapNeverStartsAnActivity() {
        // The direct cause of the collapse. Watching the activity stack is a
        // stronger assertion than watching the shade, because it fails even if
        // some future Android stops collapsing on activity start.
        openShade()
        val before = shell("dumpsys activity activities")
        assertTrue(
            "no shhh activity may be running before the tap",
            !before.contains("io.github.shhhapp.shhh/.ToggleActivity")
        )

        tapTileAndAwait(expectedQuiet = true)

        val after = shell("dumpsys activity activities")
        assertTrue(
            "the tile must not launch ToggleActivity",
            !after.contains("io.github.shhhapp.shhh/.ToggleActivity")
        )
        assertTrue(
            "the tile must not launch MainActivity either",
            !after.contains("io.github.shhhapp.shhh/.MainActivity")
        )
    }

    @Test
    fun aZenWithoutAccessStillOpensTheApp() {
        // The one case where collapsing is correct: Android refuses every write
        // until the grant exists, and opening the app is what a tile may
        // legitimately close the shade for.
        device.applyZen(ZenState.PRIORITY)
        device.revokeDndAccess()
        openShade()

        shell("cmd statusbar click-tile $TILE")
        waitFor("the app opened", timeoutMillis = 10_000) {
            shell("dumpsys activity activities").contains("io.github.shhhapp.shhh/.MainActivity")
        }

        device.grantDndAccess()
        // NOT `am force-stop`: instrumentation runs inside the app under test,
        // so that would kill the test runner. Going home is enough.
        shell("input keyevent KEYCODE_HOME")
    }
}
