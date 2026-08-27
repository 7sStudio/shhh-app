package io.github.shhhapp.shhh.core

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Regression cover for every clock face in the app. These used to go through
 * `DateTimeFormatter.ofLocalizedTime(SHORT)`, which resolves against
 * `Locale.getDefault()` and always uses that locale's own clock convention — so
 * a phone set to 24-hour still read "11:00 PM" on the quiet-hours dial while
 * the settings time picker, which asks the platform, showed 23:00.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimeFormatTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `honours the system 24-hour setting`() {
        ShadowSettings.set24HourTimeFormat(true)

        assertEquals("23:00", TimeFormat.minutesOfDay(context, 23 * 60))
        assertEquals("07:05", TimeFormat.minutesOfDay(context, 7 * 60 + 5))
        assertEquals("00:00", TimeFormat.minutesOfDay(context, 0))
    }

    @Test
    fun `honours a 12-hour system setting`() {
        ShadowSettings.set24HourTimeFormat(false)

        val evening = TimeFormat.minutesOfDay(context, 23 * 60)
        assertTrue("expected a 12-hour clock face, got $evening", evening.startsWith("11:00"))
        assertTrue("expected a day-period marker, got $evening", evening.any { it.isLetter() })
    }

    @Test
    fun `the same wall clock formats identically from minutes and from millis`() {
        ShadowSettings.set24HourTimeFormat(true)
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 21)
            set(java.util.Calendar.MINUTE, 30)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        assertEquals(
            TimeFormat.minutesOfDay(context, 21 * 60 + 30),
            TimeFormat.epochMillis(context, calendar.timeInMillis)
        )
    }

    @Test
    fun `formats with the configuration locale, not the JVM default`() {
        // Only the 12-hour clock exposes the locale: the day-period marker is
        // "PM" in English but "\u0645" in Arabic, one of the app's own languages.
        // If the formatter read Locale.getDefault() — still English here — the
        // two would come out identical.
        ShadowSettings.set24HourTimeFormat(false)
        val arabic = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("ar"))
            }
        )

        val english = TimeFormat.minutesOfDay(context, 13 * 60)
        val translated = TimeFormat.minutesOfDay(arabic, 13 * 60)

        assertTrue("expected an English day period, got $english", english.contains("PM"))
        assertNotEquals(english, translated)
    }
}
