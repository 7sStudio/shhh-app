package io.github.shhhapp.shhh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DialGeometryTest {

    @Test
    fun `midnight is at the top, time flows clockwise`() {
        assertEquals(-90f, DialGeometry.minutesToAngle(0))          // 12 AM top
        assertEquals(0f, DialGeometry.minutesToAngle(6 * 60))       // 6 AM right
        assertEquals(90f, DialGeometry.minutesToAngle(12 * 60))     // 12 PM bottom
        assertEquals(180f, DialGeometry.minutesToAngle(18 * 60))    // 6 PM left
    }

    @Test
    fun `touch positions map back to minutes`() {
        assertEquals(0, DialGeometry.pointToMinutes(0f, -100f))     // top
        assertEquals(6 * 60, DialGeometry.pointToMinutes(100f, 0f)) // right
        assertEquals(12 * 60, DialGeometry.pointToMinutes(0f, 100f))
        assertEquals(18 * 60, DialGeometry.pointToMinutes(-100f, 0f))
    }

    @Test
    fun `touch positions snap to five minutes`() {
        // 1 degree past the top = 4 minutes -> snaps to 5.
        val dx = Math.sin(Math.toRadians(1.0)).toFloat() * 100f
        val dy = -Math.cos(Math.toRadians(1.0)).toFloat() * 100f
        assertEquals(5, DialGeometry.pointToMinutes(dx, dy))
    }

    @Test
    fun `window length matches QuietHours semantics`() {
        assertEquals(480, DialGeometry.windowMinutes(23 * 60, 7 * 60)) // overnight
        assertEquals(60, DialGeometry.windowMinutes(13 * 60, 14 * 60)) // same day
        assertEquals(1440, DialGeometry.windowMinutes(600, 600))       // equal = full day
    }

    @Test
    fun `sweep angle covers the window`() {
        assertEquals(120f, DialGeometry.sweepAngle(23 * 60, 7 * 60))
        assertEquals(360f, DialGeometry.sweepAngle(600, 600))
    }

    @Test
    fun `circular distance wraps around midnight`() {
        assertEquals(20, DialGeometry.circularDistance(10, 1430))
        assertEquals(720, DialGeometry.circularDistance(0, 720))
        assertEquals(0, DialGeometry.circularDistance(100, 100))
    }

    // ---- Edge cases ----

    /** A point on the ring for [minutes], relative to the dial center. */
    private fun pointFor(minutes: Int, radius: Double = 100.0): Pair<Float, Float> {
        val radians = Math.toRadians(DialGeometry.minutesToAngle(minutes).toDouble())
        return (radius * Math.cos(radians)).toFloat() to (radius * Math.sin(radians)).toFloat()
    }

    @Test
    fun `snapping past the last bucket wraps back to midnight`() {
        // A quarter of a degree before the top is 23:59, which snaps up to 1440
        // and must come back out as 0, not 1440.
        val theta = Math.toRadians(-0.25)
        val dx = (100.0 * Math.sin(theta)).toFloat()
        val dy = (-100.0 * Math.cos(theta)).toFloat()
        assertEquals(0, DialGeometry.pointToMinutes(dx, dy))
    }

    @Test
    fun `the last snap bucket before the wrap stays on the same day`() {
        val (dx, dy) = pointFor(23 * 60 + 55)
        assertEquals(23 * 60 + 55, DialGeometry.pointToMinutes(dx, dy))
    }

    @Test
    fun `a custom snap rounds to that granularity`() {
        val (dx, dy) = pointFor(100)
        assertEquals(120, DialGeometry.pointToMinutes(dx, dy, snap = 60))
        assertEquals(100, DialGeometry.pointToMinutes(dx, dy, snap = 1))

        val (dx2, dy2) = pointFor(20)
        assertEquals(0, DialGeometry.pointToMinutes(dx2, dy2, snap = 60))
    }

    @Test
    fun `the dial radius does not affect the mapping`() {
        val (nearDx, nearDy) = pointFor(9 * 60 + 30, radius = 12.0)
        val (farDx, farDy) = pointFor(9 * 60 + 30, radius = 900.0)
        assertEquals(9 * 60 + 30, DialGeometry.pointToMinutes(nearDx, nearDy))
        assertEquals(9 * 60 + 30, DialGeometry.pointToMinutes(farDx, farDy))
    }

    @Test
    fun `angles cover the whole ring without reaching 270 degrees`() {
        assertEquals(269.75, DialGeometry.minutesToAngle(1439).toDouble(), 0.001)
        assertEquals(-90.0, DialGeometry.minutesToAngle(0).toDouble(), 0.001)
    }

    @Test
    fun `the shortest window is one minute and the longest is a full day`() {
        assertEquals(1, DialGeometry.windowMinutes(100, 101))
        assertEquals(1439, DialGeometry.windowMinutes(101, 100))
        assertEquals(0.25, DialGeometry.sweepAngle(100, 101).toDouble(), 0.001)
    }

    @Test
    fun `circular distance is symmetric and never exceeds half a day`() {
        assertEquals(20, DialGeometry.circularDistance(1430, 10))
        assertEquals(20, DialGeometry.circularDistance(10, 1430))
        assertEquals(720, DialGeometry.circularDistance(720, 0))
        assertEquals(1, DialGeometry.circularDistance(0, 1439))
    }
}
