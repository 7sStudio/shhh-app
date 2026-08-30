package io.github.shhhapp.shhh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Updates and contact surfaces on a real device/emulator. */
@RunWith(AndroidJUnit4::class)
class UpdateSettingsIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        settings = ShhhSettings(ApplicationProvider.getApplicationContext())
        settings.autoUpdateCheckEnabled = false
        settings.lastUpdateCheckMillis = 0L
        settings.lastPromptedUpdateVersion = ""
    }

    private fun openSettings() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun updatesSection_isOfferedInSettings() {
        openSettings()

        composeRule.onNodeWithText("Check for updates automatically")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Contact the developer").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun autoUpdateToggle_persistsBothWays() {
        openSettings()

        composeRule.onNodeWithTag("toggle_auto_update").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(settings.autoUpdateCheckEnabled)

        composeRule.onNodeWithTag("toggle_auto_update").performClick()
        composeRule.waitForIdle()
        assertFalse(settings.autoUpdateCheckEnabled)
    }

    @Test
    fun manualCheck_againstTheRealGitHubApi_resolves() {
        openSettings()

        composeRule.onNodeWithText("Check for updates").performScrollTo().performClick()

        // Real network: succeed ("up to date" — CI builds carry the latest
        // version — or an update dialog for a newer release) or fail with the
        // offline message; the row must never be stuck on "Checking…".
        composeRule.waitUntil(timeoutMillis = 30_000) {
            listOf(
                "You're up to date",
                "Update available",
                "Couldn't reach GitHub — check your connection and try again"
            ).any { text ->
                composeRule.onAllNodes(hasText(text, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
