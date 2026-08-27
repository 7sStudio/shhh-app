package io.github.shhhapp.shhh.core

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Clock-face formatting that follows the phone rather than the JVM.
 *
 * `DateTimeFormatter.ofLocalizedTime(SHORT)` resolves against
 * `Locale.getDefault()` and always uses that locale's own clock convention, so
 * it ignores both a per-app language and the system "Use 24-hour format"
 * switch — a phone set to 24-hour would still read "11:00 PM" on the dial while
 * the settings time picker (which asks [DateFormat.is24HourFormat]) showed
 * 23:00. [DateFormat.getTimeFormat] asks the context for both the configuration
 * locale and that switch, so every clock face in the app goes through here.
 */
object TimeFormat {

    /** Formats minutes-after-midnight, e.g. 1380 -> "11:00 PM" or "23:00". */
    fun minutesOfDay(context: Context, minutesOfDay: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            set(Calendar.MINUTE, minutesOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return DateFormat.getTimeFormat(context).format(calendar.time)
    }

    /** Formats an instant as a wall-clock time in the device time zone. */
    fun epochMillis(context: Context, millis: Long): String =
        DateFormat.getTimeFormat(context).format(Date(millis))
}
