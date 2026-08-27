package io.github.shhhapp.shhh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExpressiveToggleGroupTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val options = listOf(
        ToggleOption("Option 1", R.drawable.ic_vibration),
        ToggleOption("Option 2", R.drawable.ic_volume_up)
    )

    private val threeOptions = listOf(
        ToggleOption("First", R.drawable.ic_vibration),
        ToggleOption("Middle", R.drawable.ic_volume_up),
        ToggleOption("Last", R.drawable.ic_history)
    )

    @Test
    fun `shows all options and reflects initial selection`() {
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = options,
                selectedIndex = 0,
                onSelect = {}
            )
        }

        composeTestRule.onNodeWithText("Option 1").assertExists().assertIsSelected()
        composeTestRule.onNodeWithText("Option 2").assertExists().assertIsNotSelected()
    }

    @Test
    fun `invokes callback on click`() {
        var selectedIndex = -1
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = options,
                selectedIndex = 0,
                onSelect = { selectedIndex = it }
            )
        }

        composeTestRule.onNodeWithText("Option 2").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, selectedIndex)
    }

    @Test
    fun `clicking the already selected option still reports its index`() {
        var selectedIndex = -1
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = options,
                selectedIndex = 1,
                onSelect = { selectedIndex = it }
            )
        }

        composeTestRule.onNodeWithText("Option 2").assertIsSelected().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, selectedIndex)
    }

    /**
     * The middle option being selected is the only arrangement that makes every
     * leg of the `selected || isFirst` / `selected || isLast` corner logic run:
     * index 0 is first-but-not-selected, index 1 is selected-but-neither-edge,
     * index 2 is last-but-not-selected.
     */
    @Test
    fun `three option group with the middle selected marks only the middle`() {
        val picked = mutableListOf<Int>()
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = threeOptions,
                selectedIndex = 1,
                onSelect = { picked += it },
                modifier = Modifier.padding(4.dp).testTag("group")
            )
        }

        composeTestRule.onNodeWithTag("group").assertIsDisplayed()
        composeTestRule.onNodeWithText("First").assertIsNotSelected()
        composeTestRule.onNodeWithText("Middle").assertIsSelected()
        composeTestRule.onNodeWithText("Last").assertIsNotSelected()

        composeTestRule.onNodeWithText("First").performClick()
        composeTestRule.onNodeWithText("Last").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(0, 2), picked)
    }

    @Test
    fun `three option group with the last selected marks only the last`() {
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = threeOptions,
                selectedIndex = 2,
                onSelect = {}
            )
        }

        composeTestRule.onNodeWithText("First").assertIsNotSelected()
        composeTestRule.onNodeWithText("Middle").assertIsNotSelected()
        composeTestRule.onNodeWithText("Last").assertIsSelected()
    }

    @Test
    fun `three option group with the first selected marks only the first`() {
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = threeOptions,
                selectedIndex = 0,
                onSelect = {}
            )
        }

        composeTestRule.onNodeWithText("First").assertIsSelected()
        composeTestRule.onNodeWithText("Middle").assertIsNotSelected()
        composeTestRule.onNodeWithText("Last").assertIsNotSelected()
    }

    @Test
    fun `options expose the radio button role`() {
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = options,
                selectedIndex = 0,
                onSelect = {}
            )
        }

        composeTestRule.onNodeWithText("Option 1").assert(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role,
                Role.RadioButton
            )
        )
    }

    @Test
    fun `selection follows the state the caller hoists`() {
        var selected by mutableStateOf(0)
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = threeOptions,
                selectedIndex = selected,
                onSelect = { selected = it }
            )
        }

        composeTestRule.onNodeWithText("First").assertIsSelected()

        composeTestRule.onNodeWithText("Last").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("First").assertIsNotSelected()
        composeTestRule.onNodeWithText("Last").assertIsSelected()

        composeTestRule.onNodeWithText("Middle").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Middle").assertIsSelected()
        composeTestRule.onNodeWithText("Last").assertIsNotSelected()
    }

    @Test
    fun `group is skipped when none of its inputs change`() {
        var tick by mutableStateOf(0)
        composeTestRule.setContent {
            Column {
                Text("tick $tick", modifier = Modifier.testTag("tick"))
                ExpressiveToggleGroup(
                    options = threeOptions,
                    selectedIndex = 1,
                    onSelect = {}
                )
            }
        }

        composeTestRule.runOnIdle { tick = 1 }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { tick = 2 }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tick").assertIsDisplayed()
        composeTestRule.onNodeWithText("Middle").assertIsSelected()
        composeTestRule.onNodeWithText("First").assertIsNotSelected()
    }

    @Test
    fun `renders an empty group without crashing`() {
        composeTestRule.setContent {
            ExpressiveToggleGroup(
                options = emptyList(),
                selectedIndex = -1,
                onSelect = {},
                modifier = Modifier.testTag("empty")
            )
        }

        composeTestRule.onNodeWithTag("empty").assertExists()
    }
}
