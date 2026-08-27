package io.github.shhhapp.shhh.ui

import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Pure math for the 24-hour schedule dial: minutes-after-midnight <-> angles,
 * with midnight at the top and time flowing clockwise.
 */
object DialGeometry {

    const val MINUTES_PER_DAY = 1440
    const val SNAP_MINUTES = 5

    /** Canvas angle in degrees (0° = +x axis) for a minutes-after-midnight value. */
    fun minutesToAngle(minutes: Int): Float =
        minutes.toFloat() / MINUTES_PER_DAY * 360f - 90f

    /** Converts a touch position (relative to the dial center) to snapped minutes. */
    fun pointToMinutes(dx: Float, dy: Float, snap: Int = SNAP_MINUTES): Int {
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val fromMidnight = (degrees + 90f + 360f) % 360f
        val raw = (fromMidnight / 360f * MINUTES_PER_DAY).roundToInt()
        val snapped = ((raw + snap / 2) / snap) * snap
        return snapped % MINUTES_PER_DAY
    }

    /** Clockwise sweep from start to end in degrees; equal times mean a full day. */
    fun sweepAngle(startMinutes: Int, endMinutes: Int): Float =
        windowMinutes(startMinutes, endMinutes).toFloat() / MINUTES_PER_DAY * 360f

    /** Window length in minutes; equal times mean a full day (matches QuietHours.endFor). */
    fun windowMinutes(startMinutes: Int, endMinutes: Int): Int =
        (endMinutes - startMinutes + MINUTES_PER_DAY - 1) % MINUTES_PER_DAY + 1

    /** Circular distance between two minute values (0..720). */
    fun circularDistance(a: Int, b: Int): Int {
        val d = Math.floorMod(a - b, MINUTES_PER_DAY)
        return minOf(d, MINUTES_PER_DAY - d)
    }
}
