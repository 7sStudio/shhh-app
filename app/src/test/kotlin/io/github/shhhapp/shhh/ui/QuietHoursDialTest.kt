package io.github.shhhapp.shhh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dial is a Canvas driven by raw pointer input, so every test here injects
 * touches at a real angle on the ring and checks the minutes that come back
 * out of [DialGeometry].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuietHoursDialTest {

    private val context: android.content.Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()

    /** The pure formatter behind the dial's centre readouts. */
    private fun clock(minutesOfDay: Int) = TimeFormat.minutesOfDay(context, minutesOfDay)

    @get:Rule
    val composeTestRule = createComposeRule()

    private val changes = mutableListOf<Pair<Int, Int>>()
    private var finished = 0
    private var startClicks = 0
    private var endClicks = 0

    /**
     * Renders the dial with live state, the way [SettingsScreen] does, so a
     * drag that reports new minutes actually moves the handle.
     */
    private fun setDial(startMinutes: Int, endMinutes: Int) {
        composeTestRule.setContent {
            var start by remember { mutableIntStateOf(startMinutes) }
            var end by remember { mutableIntStateOf(endMinutes) }
            QuietHoursDial(
                startMinutes = start,
                endMinutes = end,
                onChange = { newStart, newEnd ->
                    start = newStart
                    end = newEnd
                    changes += newStart to newEnd
                },
                onChangeFinished = { finished++ },
                onStartClick = { startClicks++ },
                onEndClick = { endClicks++ },
                modifier = Modifier.testTag(DIAL)
            )
        }
    }

    /** A point on the ring for [minutes], in the tagged node's own coordinates. */
    private fun TouchInjectionScope.onRing(minutes: Int): Offset {
        // The ring sits at (dialSize / 2 - handleRadius) = 130dp of the 300dp box.
        val radius = width * (130f / 300f)
        val radians = Math.toRadians(DialGeometry.minutesToAngle(minutes).toDouble())
        return Offset(
            width / 2f + radius * cos(radians).toFloat(),
            height / 2f + radius * sin(radians).toFloat()
        )
    }

    // ---- Rendering ----

    @Test
    fun `shows start and end readouts and both handles`() {
        setDial(22 * 60, 7 * 60)

        composeTestRule.onNodeWithText("Starts").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ends").assertIsDisplayed()
        composeTestRule.onNodeWithText(clock(22 * 60)).assertIsDisplayed()
        composeTestRule.onNodeWithText(clock(7 * 60)).assertIsDisplayed()
        // The two draggable handles carry the same labels as content descriptions.
        composeTestRule.onNodeWithContentDescription("Starts").assertExists()
        composeTestRule.onNodeWithContentDescription("Ends").assertExists()
        // Orientation labels around the ring.
        composeTestRule.onNodeWithText("12 AM").assertIsDisplayed()
        composeTestRule.onNodeWithText("6 AM").assertIsDisplayed()
        composeTestRule.onNodeWithText("12 PM").assertIsDisplayed()
        composeTestRule.onNodeWithText("6 PM").assertIsDisplayed()
    }

    @Test
    fun `whole-hour window is shown in hours only`() {
        setDial(22 * 60, 7 * 60)
        composeTestRule.onNodeWithText("9 hr").assertIsDisplayed()
    }

    @Test
    fun `sub-hour window is shown in minutes only`() {
        setDial(10 * 60, 10 * 60 + 45)
        composeTestRule.onNodeWithText("45 min").assertIsDisplayed()
    }

    @Test
    fun `mixed window is shown in hours and minutes`() {
        setDial(10 * 60, 11 * 60 + 30)
        composeTestRule.onNodeWithText("1 hr 30 min").assertIsDisplayed()
    }

    @Test
    fun `equal start and end reads as a full day`() {
        setDial(10 * 60, 10 * 60)
        composeTestRule.onNodeWithText("24 hr").assertIsDisplayed()
    }

    @Test
    fun `renders without an explicit modifier`() {
        composeTestRule.setContent {
            QuietHoursDial(
                startMinutes = 22 * 60,
                endMinutes = 7 * 60,
                onChange = { _, _ -> },
                onChangeFinished = {},
                onStartClick = {},
                onEndClick = {}
            )
        }

        composeTestRule.onNodeWithText("Starts").assertIsDisplayed()
        composeTestRule.onNodeWithText("9 hr").assertIsDisplayed()
    }

    @Test
    fun `an unrelated recomposition does not disturb the dial`() {
        composeTestRule.setContent {
            var ticks by remember { mutableIntStateOf(0) }
            val onChange = remember<(Int, Int) -> Unit> { { _, _ -> } }
            val onFinished = remember<() -> Unit> { { finished++ } }
            val onStart = remember<() -> Unit> { { startClicks++ } }
            val onEnd = remember<() -> Unit> { { endClicks++ } }
            Column {
                Text(
                    text = "ticks: $ticks",
                    modifier = Modifier.testTag(TICKER).clickable { ticks++ }
                )
                QuietHoursDial(
                    startMinutes = 22 * 60,
                    endMinutes = 7 * 60,
                    onChange = onChange,
                    onChangeFinished = onFinished,
                    onStartClick = onStart,
                    onEndClick = onEnd,
                    modifier = Modifier.testTag(DIAL)
                )
            }
        }

        composeTestRule.onNodeWithTag(TICKER).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("ticks: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("9 hr").assertIsDisplayed()
        composeTestRule.onNodeWithText(clock(22 * 60)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Starts").performClick()
        assertEquals(1, startClicks)
    }

    // ---- Center readouts ----

    @Test
    fun `tapping the start readout fires onStartClick only`() {
        setDial(22 * 60, 7 * 60)

        composeTestRule.onNodeWithText("Starts").performClick()

        assertEquals(1, startClicks)
        assertEquals(0, endClicks)
    }

    @Test
    fun `tapping the end readout fires onEndClick only`() {
        setDial(22 * 60, 7 * 60)

        composeTestRule.onNodeWithText("Ends").performClick()

        assertEquals(1, endClicks)
        assertEquals(0, startClicks)
    }

    // ---- Dragging ----

    @Test
    fun `dragging near the start handle moves the start time`() {
        setDial(23 * 60, 7 * 60)

        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(23 * 60))
            moveTo(onRing(22 * 60)) // crosses touch slop next to the start handle
            moveTo(onRing(20 * 60))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(20 * 60 to 7 * 60, changes.last())
        assertEquals(1, finished)
        composeTestRule.onNodeWithText(clock(20 * 60)).assertIsDisplayed()
    }

    @Test
    fun `dragging near the end handle moves the end time`() {
        setDial(23 * 60, 7 * 60)

        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(7 * 60))
            moveTo(onRing(8 * 60)) // crosses touch slop next to the end handle
            moveTo(onRing(9 * 60))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(23 * 60 to 9 * 60, changes.last())
        assertEquals(1, finished)
        composeTestRule.onNodeWithText(clock(9 * 60)).assertIsDisplayed()
    }

    @Test
    fun `a touch exactly between the handles grabs the start handle`() {
        // circularDistance is equal to both ends at 05:00, and the tie goes to
        // START because the comparison is <=.
        setDial(0, 10 * 60)

        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(6 * 60))
            moveTo(onRing(5 * 60)) // equidistant from 00:00 and 10:00
            moveTo(onRing(4 * 60))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(4 * 60 to 10 * 60, changes.last())
        assertEquals(1, finished)
    }

    @Test
    fun `a cancelled drag still finishes the change`() {
        setDial(23 * 60, 7 * 60)

        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(7 * 60))
            moveTo(onRing(8 * 60))
            cancel()
        }
        composeTestRule.waitForIdle()

        assertEquals(23 * 60 to 8 * 60, changes.last())
        assertEquals(1, finished)
    }

    @Test
    fun `a tap that never crosses touch slop changes nothing`() {
        setDial(23 * 60, 7 * 60)

        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(7 * 60))
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(changes.isEmpty())
        assertEquals(0, finished)
    }

    @Test
    fun `a second drag picks the handle up again`() {
        setDial(23 * 60, 7 * 60)

        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(7 * 60))
            moveTo(onRing(8 * 60))
            up()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(23 * 60))
            moveTo(onRing(21 * 60))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(21 * 60 to 8 * 60, changes.last())
        assertEquals(2, finished)
    }

    private companion object {
        const val DIAL = "dial_under_test"
        const val TICKER = "ticker"
    }
}
