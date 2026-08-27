package io.github.shhhapp.shhh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the dial actually paints. Native graphics is required so the Canvas
 * really rasterises instead of no-opping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuietHoursDialPaintTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val changes = mutableListOf<Pair<Int, Int>>()

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
                onChangeFinished = {},
                onStartClick = {},
                onEndClick = {},
                modifier = Modifier.testTag(DIAL)
            )
        }
    }

    private fun TouchInjectionScope.onRing(minutes: Int): Offset {
        val radius = width * (130f / 300f)
        val radians = Math.toRadians(DialGeometry.minutesToAngle(minutes).toDouble())
        return Offset(
            width / 2f + radius * cos(radians).toFloat(),
            height / 2f + radius * sin(radians).toFloat()
        )
    }

    private fun ringPixel(pixels: androidx.compose.ui.graphics.PixelMap, minutes: Int) =
        pixels[ringX(pixels, minutes), ringY(pixels, minutes)]

    private fun ringX(pixels: androidx.compose.ui.graphics.PixelMap, minutes: Int): Int {
        val radians = Math.toRadians(DialGeometry.minutesToAngle(minutes).toDouble())
        return (pixels.width / 2f + pixels.width * (130f / 300f) * cos(radians).toFloat())
            .roundToInt()
    }

    private fun ringY(pixels: androidx.compose.ui.graphics.PixelMap, minutes: Int): Int {
        val radians = Math.toRadians(DialGeometry.minutesToAngle(minutes).toDouble())
        return (pixels.height / 2f + pixels.width * (130f / 300f) * sin(radians).toFloat())
            .roundToInt()
    }

    @Test
    fun `the window arc is painted over the quiet hours only`() {
        setDial(23 * 60, 7 * 60)

        val pixels = composeTestRule.onNodeWithTag(DIAL).captureToImage().toPixelMap()

        val insideWindow = ringPixel(pixels, 2 * 60)   // 02:00, inside 23:00 -> 07:00
        val outsideWindow = ringPixel(pixels, 12 * 60) // noon, outside the window
        assertNotEquals(insideWindow, outsideWindow)
        // Both are painted: the arc over the window, the track everywhere else.
        assertEquals(1f, insideWindow.alpha)
        assertEquals(1f, outsideWindow.alpha)
    }

    @Test
    fun `moving a handle repaints the arc`() {
        setDial(0, 6 * 60)

        val before = composeTestRule.onNodeWithTag(DIAL).captureToImage().toPixelMap()
        val x = ringX(before, 9 * 60)
        val y = ringY(before, 9 * 60)

        composeTestRule.onNodeWithTag(DIAL).performTouchInput {
            down(onRing(6 * 60))
            moveTo(onRing(8 * 60))
            moveTo(onRing(11 * 60))
            up()
        }
        composeTestRule.waitForIdle()
        val after = composeTestRule.onNodeWithTag(DIAL).captureToImage().toPixelMap()

        assertEquals(11 * 60, changes.last().second)
        // 09:00 was outside the 00:00 -> 06:00 window and is inside the new one.
        assertNotEquals(before[x, y], after[x, y])
    }

    private companion object {
        const val DIAL = "dial_under_test"
    }
}
