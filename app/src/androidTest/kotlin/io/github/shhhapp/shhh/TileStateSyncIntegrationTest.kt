package io.github.shhhapp.shhh

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tile's SHOWN state must follow the phone while the shade stays open.
 *
 * The v1.4.0 report: with a Do Not Disturb mode running, tapping the tile
 * hushed the phone but the tile kept its old look until the panel was closed
 * and reopened. Nothing was left to refresh it — `requestListeningState` is a
 * documented no-op for a passive tile, and the RINGER_MODE_CHANGED_ACTION
 * fallback reflects the *external* ringer mode, which every zen pins at
 * SILENT, so a hush's internal VIBRATE↔NORMAL flip never broadcasts. The fix
 * pushes the state through a direct in-process call instead; these tests
 * assert against what SystemUI actually holds for the tile, which is what the
 * user sees.
 *
 * Deliberately NOT asserted here: that the shade stays open. That contract is
 * owned and proven by TileShadeIntegrationTest under every zen state;
 * repeating it here only re-exposes the panel-rebuild race SystemUI has right
 * after a tile is added, where an open panel can be cancelled for a beat.
 */
@RunWith(AndroidJUnit4::class)
class TileStateSyncIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController

    companion object {
        private const val TILE = "io.github.shhhapp.shhh/.tile.ShhhTileService"

        /** SystemUI's Tile.STATE_INACTIVE / STATE_ACTIVE as they land in its dump. */
        private const val SHOWN_OFF = 1
        private const val SHOWN_ON = 2

        /** Added once for the class: per-test add/remove churns the QS panel. */
        @JvmStatic
        @BeforeClass
        fun addTile() {
            shell("cmd statusbar collapse")
            // A leftover tile from a previous run makes add-tile a no-op, and
            // a no-op add never re-binds the service the wait below observes.
            shell("cmd statusbar remove-tile $TILE")
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
        shell("cmd statusbar collapse")
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
    }

    @After
    fun tearDown() {
        shell("cmd statusbar collapse")
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    /**
     * The state SystemUI holds for the tile — the rendered truth, read from
     * its own dump rather than from anything inside this app. Returns null
     * while the tile has no record yet.
     */
    private fun shownTileState(): Int? {
        val dump = shell("dumpsys activity service com.android.systemui/.SystemUIService")
        val record = dump.lineSequence().firstOrNull { it.contains("spec=custom($TILE)") }
            ?: return null
        return Regex(""",state=(\d+)""").find(record)?.groupValues?.get(1)?.toInt()
    }

    private fun openShade() {
        waitFor("shade open", timeoutMillis = 15_000) {
            if (!isShadeOpen()) shell("cmd statusbar expand-settings")
            if (!isShadeOpen()) return@waitFor false
            Thread.sleep(300)
            isShadeOpen()
        }
    }

    private fun awaitShownState(expected: Int, why: String) {
        waitFor("$why: shown tile state = $expected", timeoutMillis = 10_000) {
            shownTileState() == expected
        }
    }

    @Test
    fun theTileFollowsATap_underEveryZenState_withoutReopeningTheShade() {
        for (state in ZenState.entries) {
            device.resetToAudibleNoZen()
            device.resetShhhState(settings)
            device.applyZen(state)
            openShade()
            awaitShownState(SHOWN_OFF, "$state: settled before the tap")

            shell("cmd statusbar click-tile $TILE")

            waitFor("$state: the phone actually hushed") { controller.isQuiet }
            awaitShownState(SHOWN_ON, "$state: after hushing")

            shell("cmd statusbar click-tile $TILE")

            waitFor("$state: the phone actually un-hushed") { !controller.isQuiet }
            awaitShownState(SHOWN_OFF, "$state: after un-hushing")
            assertEquals("$state: shhh must not move the zen", state.filter, device.filter)
            device.applyZen(ZenState.OFF)
        }
    }

    /**
     * Like [awaitShownState], but tolerates the environment closing the shade
     * mid-wait (SystemUI's post-add panel rebuild can cancel it): a push while
     * nothing is listening lands nowhere BY DESIGN, so the shade is reopened
     * and the push re-driven through the same production call the surfaces
     * use. On the common path — shade stayed open — this still proves the
     * single original push was enough.
     */
    private fun awaitShownStateReopeningShade(
        expected: Int,
        manager: HushManager,
        why: String
    ) {
        waitFor("$why: shown tile state = $expected", timeoutMillis = 15_000) {
            if (shownTileState() == expected) return@waitFor true
            if (!isShadeOpen()) {
                openShade()
                manager.refreshSurfaces()
            }
            shownTileState() == expected
        }
    }

    @Test
    fun aTransitionFromAnotherSurface_refreshesTheTileInTheOpenShade() {
        // No tap and therefore no optimistic flip: a hush applied by the app,
        // a shortcut or an alarm must still reach the visible tile through the
        // in-process push alone. Run under Do Not Disturb, where the broadcast
        // fallback provably never fires.
        device.applyZen(ZenState.PRIORITY)
        openShade()
        awaitShownState(SHOWN_OFF, "settled before the hush")

        val manager = HushManager(device.context)
        manager.hush()

        awaitShownStateReopeningShade(SHOWN_ON, manager, "after a hush from the app surface")

        manager.unhush()

        awaitShownStateReopeningShade(SHOWN_OFF, manager, "after an un-hush from the app surface")
        assertEquals(ZenState.PRIORITY.filter, device.filter)
    }
}
