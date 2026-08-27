package io.github.shhhapp.shhh.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

class QuietHoursTest {

    // 2026-08-26 is a Wednesday.
    private val wednesdayNoon = LocalDateTime.of(2026, 8, 26, 12, 0)

    private val everyNight = QuietHours.Schedule(
        enabled = true,
        startMinutes = 23 * 60,
        endMinutes = 7 * 60,
        days = DayOfWeek.entries.toSet()
    )

    @Test
    fun `nextStart is tonight when before start time`() {
        assertEquals(
            LocalDateTime.of(2026, 8, 26, 23, 0),
            QuietHours.nextStart(wednesdayNoon, everyNight)
        )
    }

    @Test
    fun `nextStart is tomorrow when past start time`() {
        val lateNight = LocalDateTime.of(2026, 8, 26, 23, 30)
        assertEquals(
            LocalDateTime.of(2026, 8, 27, 23, 0),
            QuietHours.nextStart(lateNight, everyNight)
        )
    }

    @Test
    fun `nextStart skips days not in the schedule`() {
        val weekendsOnly = everyNight.copy(days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        assertEquals(
            LocalDateTime.of(2026, 8, 29, 23, 0), // Saturday
            QuietHours.nextStart(wednesdayNoon, weekendsOnly)
        )
    }

    @Test
    fun `nextStart is null when disabled or no days`() {
        assertNull(QuietHours.nextStart(wednesdayNoon, everyNight.copy(enabled = false)))
        assertNull(QuietHours.nextStart(wednesdayNoon, everyNight.copy(days = emptySet())))
    }

    @Test
    fun `overnight window ends the next day`() {
        val start = LocalDateTime.of(2026, 8, 26, 23, 0)
        assertEquals(
            LocalDateTime.of(2026, 8, 27, 7, 0),
            QuietHours.endFor(start, everyNight)
        )
    }

    @Test
    fun `same-day window ends the same day`() {
        val lunchBreak = everyNight.copy(startMinutes = 13 * 60, endMinutes = 14 * 60)
        val start = LocalDateTime.of(2026, 8, 26, 13, 0)
        assertEquals(
            LocalDateTime.of(2026, 8, 26, 14, 0),
            QuietHours.endFor(start, lunchBreak)
        )
    }

    @Test
    fun `activeWindowEnd found after midnight for yesterday's start`() {
        val threeAm = LocalDateTime.of(2026, 8, 27, 3, 0) // Thursday 03:00
        assertEquals(
            LocalDateTime.of(2026, 8, 27, 7, 0),
            QuietHours.activeWindowEnd(threeAm, everyNight)
        )
    }

    @Test
    fun `activeWindowEnd is null outside the window`() {
        assertNull(QuietHours.activeWindowEnd(wednesdayNoon, everyNight))
    }

    @Test
    fun `activeWindowEnd respects the start day, not the end day`() {
        // Window starts Friday night only; Saturday 03:00 is inside it,
        // Thursday 03:00 (started Wednesday, not scheduled) is not.
        val fridayNights = everyNight.copy(days = setOf(DayOfWeek.FRIDAY))
        assertEquals(
            LocalDateTime.of(2026, 8, 29, 7, 0),
            QuietHours.activeWindowEnd(LocalDateTime.of(2026, 8, 29, 3, 0), fridayNights)
        )
        assertNull(
            QuietHours.activeWindowEnd(LocalDateTime.of(2026, 8, 27, 3, 0), fridayNights)
        )
    }

    @Test
    fun `nextStart skips a start falling exactly on the current minute`() {
        val exactlyAtStart = LocalDateTime.of(2026, 8, 26, 23, 0)
        assertEquals(
            LocalDateTime.of(2026, 8, 27, 23, 0),
            QuietHours.nextStart(exactlyAtStart, everyNight)
        )
    }

    @Test
    fun `nextStart is today for a same-day window still to come`() {
        val lunchBreak = everyNight.copy(startMinutes = 13 * 60, endMinutes = 14 * 60)
        assertEquals(
            LocalDateTime.of(2026, 8, 26, 13, 0),
            QuietHours.nextStart(wednesdayNoon, lunchBreak)
        )
    }

    @Test
    fun `activeWindowEnd finds a same-day window that has not wrapped`() {
        val lunchBreak = everyNight.copy(startMinutes = 13 * 60, endMinutes = 14 * 60)
        assertEquals(
            LocalDateTime.of(2026, 8, 26, 14, 0),
            QuietHours.activeWindowEnd(LocalDateTime.of(2026, 8, 26, 13, 30), lunchBreak)
        )
        assertNull(
            QuietHours.activeWindowEnd(LocalDateTime.of(2026, 8, 26, 14, 0), lunchBreak)
        )
    }

    @Test
    fun `activeWindowEnd starts the window at its very first minute`() {
        assertEquals(
            LocalDateTime.of(2026, 8, 27, 7, 0),
            QuietHours.activeWindowEnd(LocalDateTime.of(2026, 8, 26, 23, 0), everyNight)
        )
    }

    @Test
    fun `activeWindowEnd is null when quiet hours are disabled`() {
        val insideWindow = LocalDateTime.of(2026, 8, 27, 3, 0)
        assertNull(QuietHours.activeWindowEnd(insideWindow, everyNight.copy(enabled = false)))
    }

    @Test
    fun `activeWindowEnd is null when no days are selected`() {
        val insideWindow = LocalDateTime.of(2026, 8, 27, 3, 0)
        assertNull(QuietHours.activeWindowEnd(insideWindow, everyNight.copy(days = emptySet())))
    }
}

/** [QuietHours.fromSettings] reads the persisted schedule verbatim. */
@RunWith(AndroidJUnit4::class)
class QuietHoursFromSettingsTest {

    @Test
    fun `fromSettings mirrors the stored schedule`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = ShhhSettings(context)
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = 21 * 60 + 45
        settings.quietEndMinutes = 6 * 60 + 30
        settings.quietDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)

        assertEquals(
            QuietHours.Schedule(
                enabled = true,
                startMinutes = 21 * 60 + 45,
                endMinutes = 6 * 60 + 30,
                days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
            ),
            QuietHours.fromSettings(settings)
        )
    }

    @Test
    fun `fromSettings reports the feature as off by default`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()

        val schedule = QuietHours.fromSettings(ShhhSettings(context))

        assertFalse(schedule.enabled)
        assertEquals(23 * 60, schedule.startMinutes)
        assertEquals(7 * 60, schedule.endMinutes)
        assertEquals(DayOfWeek.entries.toSet(), schedule.days)
    }
}
