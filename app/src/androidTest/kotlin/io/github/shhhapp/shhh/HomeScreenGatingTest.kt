package io.github.shhhapp.shhh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home screen's two Do Not Disturb gates, on a real device.
 *
 * `hasDndAccess` decides whether the permission card is offered at all;
 * `canChangeSound` decides whether the big toggle and the timer chips do
 * anything. They are deliberately different: with no zen mode running shhh
 * works without the permission, so the card can be showing while the toggle is
 * still live. Only a zen mode running WITHOUT the grant makes Android refuse
 * every write, and that is the case this test drives.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenGatingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        device = DeviceAudio()
        settings = ShhhSettings(device.context)
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
    }

    @After
    fun tearDown() {
        device.grantDndAccess()
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    @Test
    fun aZenModeWithoutAccess_makesTheToggleAndTheChipsInert_untilAccessIsGranted() {
        // Revoke first, then start the zen: the interruption-filter broadcast
        // is what wakes the open screen up, and it must find both facts true.
        device.revokeDndAccess()
        device.applyZen(ZenState.PRIORITY)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Optional: Do Not Disturb access")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Optional: Do Not Disturb access")
            .performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Toggle quiet mode").performClick()
        composeRule.waitForIdle()
        assertEquals(
            "a refused toggle must leave the ring volume alone",
            DeviceAudio.DEFAULT_RING_VOLUME,
            device.ringVolume
        )

        composeRule.onNodeWithTag("chip_30").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("a refused timer chip must arm nothing", 0L, settings.timerEndMillis)
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)

        // Positive control: the same tap works the moment access is granted,
        // so the assertions above are about the gate and not about a toggle
        // the test simply failed to hit. Granting the access emits no
        // broadcast of its own, so the screen is woken the way it is in real
        // life — by the next zen change — and the permission card vanishing is
        // the signal that the refresh has landed.
        device.grantDndAccess()
        device.applyZen(ZenState.OFF)
        device.applyZen(ZenState.PRIORITY)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Optional: Do Not Disturb access")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("Toggle quiet mode").performClick()
        composeRule.waitForIdle()
        waitFor("hushed once access came back") { device.ringVolume == 0 }
        assertEquals(
            "and the user's Do Not Disturb is still theirs",
            ZenState.PRIORITY.filter,
            device.filter
        )
    }
}
