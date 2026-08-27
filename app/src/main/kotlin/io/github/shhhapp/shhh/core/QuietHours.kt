package io.github.shhhapp.shhh.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pure quiet-hours window math. A window starts at [Schedule.startMinutes] on
 * each enabled day and ends at [Schedule.endMinutes] — on the NEXT day when
 * the end time is not after the start time (e.g. 23:00 → 07:00).
 */
object QuietHours {

    data class Schedule(
        val enabled: Boolean,
        val startMinutes: Int,
        val endMinutes: Int,
        val days: Set<DayOfWeek>
    )

    fun fromSettings(settings: ShhhSettings) = Schedule(
        enabled = settings.quietHoursEnabled,
        startMinutes = settings.quietStartMinutes,
        endMinutes = settings.quietEndMinutes,
        days = settings.quietDays
    )

    /** The first window start strictly after [now], or null when disabled/no days. */
    fun nextStart(now: LocalDateTime, schedule: Schedule): LocalDateTime? {
        if (!schedule.enabled || schedule.days.isEmpty()) return null
        // Today plus a full week: with a non-empty day set this always finds a
        // match, since every weekday recurs within the next seven days and any
        // start from tomorrow on is necessarily after [now].
        return (0..7L).firstNotNullOfOrNull { dayOffset ->
            now.toLocalDate().plusDays(dayOffset)
                .takeIf { it.dayOfWeek in schedule.days }
                ?.atTime(toTime(schedule.startMinutes))
                ?.takeIf { it.isAfter(now) }
        }
    }

    /** The end of the window that starts at [start]. */
    fun endFor(start: LocalDateTime, schedule: Schedule): LocalDateTime {
        val end = start.toLocalDate().atTime(toTime(schedule.endMinutes))
        return if (schedule.endMinutes <= schedule.startMinutes) end.plusDays(1) else end
    }

    /**
     * When [now] falls inside an active window, that window's end; else null.
     * Used to hush immediately when quiet hours are enabled mid-window.
     */
    fun activeWindowEnd(now: LocalDateTime, schedule: Schedule): LocalDateTime? {
        if (!schedule.enabled) return null
        for (dayOffset in 0..1L) {
            val date = now.toLocalDate().minusDays(dayOffset)
            if (date.dayOfWeek !in schedule.days) continue
            val start = date.atTime(toTime(schedule.startMinutes))
            val end = endFor(start, schedule)
            if (!now.isBefore(start) && now.isBefore(end)) return end
        }
        return null
    }

    private fun toTime(minutes: Int): LocalTime = LocalTime.of(minutes / 60, minutes % 60)
}
