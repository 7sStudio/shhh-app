package io.github.shhhapp.shhh.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Snapshot of the tokens that must flip between the light and dark schemes. */
    private data class Snapshot(
        val background: Color,
        val onBackground: Color,
        val surface: Color,
        val primary: Color
    )

    @Composable
    private fun Capture(into: (Snapshot) -> Unit) {
        into(
            Snapshot(
                background = MaterialTheme.colorScheme.background,
                onBackground = MaterialTheme.colorScheme.onBackground,
                surface = MaterialTheme.colorScheme.surface,
                primary = MaterialTheme.colorScheme.primary
            )
        )
    }

    @Test
    fun `light and dark produce different color schemes`() {
        var light: Snapshot? = null
        var dark: Snapshot? = null

        composeTestRule.setContent {
            Column {
                ShhhTheme(darkTheme = false) {
                    Capture { light = it }
                    Text("light", Modifier.testTag("light"))
                }
                ShhhTheme(darkTheme = true) {
                    Capture { dark = it }
                    Text("dark", Modifier.testTag("dark"))
                }
            }
        }
        composeTestRule.waitForIdle()

        // The content lambda is actually composed under both configurations.
        composeTestRule.onNodeWithTag("light").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dark").assertIsDisplayed()

        val l = requireNotNull(light)
        val d = requireNotNull(dark)
        assertNotEquals("background must differ between light and dark", l.background, d.background)
        assertNotEquals("surface must differ between light and dark", l.surface, d.surface)
        assertNotEquals("primary must differ between light and dark", l.primary, d.primary)
        assertNotEquals(
            "onBackground must differ between light and dark",
            l.onBackground,
            d.onBackground
        )

        // A light scheme is dark-on-light; a dark scheme is light-on-dark.
        assertTrueMsg("light background brighter than its content", l.background.lum() > l.onBackground.lum())
        assertTrueMsg("dark background darker than its content", d.background.lum() < d.onBackground.lum())
    }

    @Test
    @Config(sdk = [34], qualifiers = "+night")
    fun `default darkTheme follows the night configuration`() {
        var default: Snapshot? = null
        var explicitDark: Snapshot? = null
        var explicitLight: Snapshot? = null

        composeTestRule.setContent {
            Column {
                ShhhTheme { Capture { default = it } }
                ShhhTheme(darkTheme = true) { Capture { explicitDark = it } }
                ShhhTheme(darkTheme = false) { Capture { explicitLight = it } }
            }
        }
        composeTestRule.waitForIdle()

        assertNotNull(default)
        assertEquals(explicitDark, default)
        assertNotEquals(explicitLight, default)
    }

    @Test
    @Config(sdk = [34], qualifiers = "+notnight")
    fun `default darkTheme follows the notnight configuration`() {
        var default: Snapshot? = null
        var explicitDark: Snapshot? = null
        var explicitLight: Snapshot? = null

        composeTestRule.setContent {
            Column {
                ShhhTheme { Capture { default = it } }
                ShhhTheme(darkTheme = true) { Capture { explicitDark = it } }
                ShhhTheme(darkTheme = false) { Capture { explicitLight = it } }
            }
        }
        composeTestRule.waitForIdle()

        assertNotNull(default)
        assertEquals(explicitLight, default)
        assertNotEquals(explicitDark, default)
    }

    @Test
    fun `flipping darkTheme swaps the scheme in place`() {
        var dark by mutableStateOf(false)
        var current: Snapshot? = null

        composeTestRule.setContent {
            ShhhTheme(darkTheme = dark) {
                Capture { current = it }
                Text("body", Modifier.testTag("body"))
            }
        }
        composeTestRule.waitForIdle()
        val asLight = requireNotNull(current)

        composeTestRule.runOnIdle { dark = true }
        composeTestRule.waitForIdle()
        val asDark = requireNotNull(current)

        assertNotEquals(asLight, asDark)
        composeTestRule.onNodeWithTag("body").assertIsDisplayed()

        composeTestRule.runOnIdle { dark = false }
        composeTestRule.waitForIdle()
        assertEquals(asLight, current)
    }

    @Test
    fun `theme is skipped when nothing it depends on changes`() {
        var tick by mutableStateOf(0)

        composeTestRule.setContent {
            Column {
                Text("tick $tick", Modifier.testTag("tick"))
                ShhhTheme(darkTheme = false) {
                    Text("themed", Modifier.testTag("themed"))
                }
            }
        }

        composeTestRule.runOnIdle { tick = 1 }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { tick = 2 }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tick").assertIsDisplayed()
        composeTestRule.onNodeWithTag("themed").assertIsDisplayed()
    }

    /**
     * The default [ShhhTheme] reads the night flag out of the configuration, so a
     * runtime night-mode switch (system theme change, "dark theme" toggle) has to
     * repaint the tree without the caller doing anything.
     */
    @Test
    @Config(sdk = [34], qualifiers = "+notnight")
    fun `theme repaints when night mode is switched at runtime`() {
        var night by mutableStateOf(false)

        composeTestRule.setContent {
            val base = LocalConfiguration.current
            val cfg = remember(night, base) {
                Configuration(base).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) {
                            Configuration.UI_MODE_NIGHT_YES
                        } else {
                            Configuration.UI_MODE_NIGHT_NO
                        }
                }
            }
            CompositionLocalProvider(LocalConfiguration provides cfg) {
                ThemedProbe()
            }
        }
        composeTestRule.waitForIdle()
        val byDay = requireNotNull(probe)

        composeTestRule.runOnIdle { night = true }
        composeTestRule.waitForIdle()
        val byNight = requireNotNull(probe)

        assertNotEquals(byDay, byNight)
        assertTrueMsg("night background is darker", byNight.background.lum() < byDay.background.lum())
        composeTestRule.onNodeWithTag("probe").assertIsDisplayed()

        composeTestRule.runOnIdle { night = false }
        composeTestRule.waitForIdle()
        assertEquals(byDay, probe)
    }

    private var probe: Snapshot? = null

    @Composable
    private fun ThemedProbe() {
        ShhhTheme {
            Capture { probe = it }
            Text("probe", Modifier.testTag("probe"))
        }
    }

    private fun Color.lum(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

    private fun assertTrueMsg(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)
}
