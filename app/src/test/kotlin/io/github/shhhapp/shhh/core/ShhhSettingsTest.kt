package io.github.shhhapp.shhh.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShhhSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: ShhhSettings

    /** A second instance over the same file: proves values were really persisted. */
    private val reloaded: ShhhSettings get() = ShhhSettings(context)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric keeps SharedPreferences between tests in a class; start clean
        // so the default-value assertions below mean something. "shhh" is the
        // preferences file ShhhSettings uses.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ShhhSettings(context)
    }

    @Test
    fun `defaults are a vibrate hush that restores the previous volume`() {
        assertEquals(ShhhSettings.HushRinger.VIBRATE, settings.hushRinger)
        assertEquals(ShhhSettings.RestoreMode.PREVIOUS, settings.restoreMode)
        assertEquals(50, settings.fixedRestorePercent)
        assertEquals(ShhhSettings.NO_SAVED_VOLUME, settings.previousMediaVolume)
        assertTrue(settings.liveCountdownEnabled)
        assertFalse(settings.headphonesAutoRestore)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `quiet hours default to every night from 23 to 07, switched off`() {
        assertFalse(settings.quietHoursEnabled)
        assertEquals(23 * 60, settings.quietStartMinutes)
        assertEquals(7 * 60, settings.quietEndMinutes)
        assertEquals(DayOfWeek.entries.toSet(), settings.quietDays)
    }

    @Test
    fun `hushRinger round-trips`() {
        settings.hushRinger = ShhhSettings.HushRinger.SILENT
        assertEquals(ShhhSettings.HushRinger.SILENT, reloaded.hushRinger)

        settings.hushRinger = ShhhSettings.HushRinger.VIBRATE
        assertEquals(ShhhSettings.HushRinger.VIBRATE, reloaded.hushRinger)
    }

    @Test
    fun `restoreMode round-trips`() {
        settings.restoreMode = ShhhSettings.RestoreMode.FIXED
        assertEquals(ShhhSettings.RestoreMode.FIXED, reloaded.restoreMode)

        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        assertEquals(ShhhSettings.RestoreMode.PREVIOUS, reloaded.restoreMode)
    }

    @Test
    fun `fixedRestorePercent is clamped to a usable range`() {
        settings.fixedRestorePercent = 42
        assertEquals(42, reloaded.fixedRestorePercent)

        settings.fixedRestorePercent = 0
        assertEquals("below 10% is inaudible", 10, reloaded.fixedRestorePercent)

        settings.fixedRestorePercent = 250
        assertEquals(100, reloaded.fixedRestorePercent)
    }

    @Test
    fun `previousMediaVolume round-trips`() {
        settings.previousMediaVolume = 9
        assertEquals(9, reloaded.previousMediaVolume)

        settings.previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
        assertEquals(ShhhSettings.NO_SAVED_VOLUME, reloaded.previousMediaVolume)
    }

    @Test
    fun `liveCountdownEnabled round-trips`() {
        settings.liveCountdownEnabled = false
        assertFalse(reloaded.liveCountdownEnabled)

        settings.liveCountdownEnabled = true
        assertTrue(reloaded.liveCountdownEnabled)
    }

    @Test
    fun `headphonesAutoRestore round-trips`() {
        settings.headphonesAutoRestore = true
        assertTrue(reloaded.headphonesAutoRestore)

        settings.headphonesAutoRestore = false
        assertFalse(reloaded.headphonesAutoRestore)
    }

    @Test
    fun `timerEndMillis round-trips`() {
        val end = System.currentTimeMillis() + 3_600_000L
        settings.timerEndMillis = end
        assertEquals(end, reloaded.timerEndMillis)

        settings.timerEndMillis = 0L
        assertEquals(0L, reloaded.timerEndMillis)
    }

    @Test
    fun `quietHoursEnabled round-trips`() {
        settings.quietHoursEnabled = true
        assertTrue(reloaded.quietHoursEnabled)

        settings.quietHoursEnabled = false
        assertFalse(reloaded.quietHoursEnabled)
    }

    @Test
    fun `quiet start and end are clamped to a single day`() {
        settings.quietStartMinutes = 21 * 60 + 30
        settings.quietEndMinutes = 6 * 60 + 15
        assertEquals(21 * 60 + 30, reloaded.quietStartMinutes)
        assertEquals(6 * 60 + 15, reloaded.quietEndMinutes)

        settings.quietStartMinutes = -30
        settings.quietEndMinutes = -1
        assertEquals(0, reloaded.quietStartMinutes)
        assertEquals(0, reloaded.quietEndMinutes)

        settings.quietStartMinutes = 24 * 60
        settings.quietEndMinutes = 5_000
        assertEquals(24 * 60 - 1, reloaded.quietStartMinutes)
        assertEquals(24 * 60 - 1, reloaded.quietEndMinutes)
    }

    @Test
    fun `quietDays round-trips an empty set, one day and the whole week`() {
        settings.quietDays = emptySet()
        assertEquals(emptySet<DayOfWeek>(), reloaded.quietDays)

        settings.quietDays = setOf(DayOfWeek.SUNDAY)
        assertEquals(setOf(DayOfWeek.SUNDAY), reloaded.quietDays)

        settings.quietDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), reloaded.quietDays)

        settings.quietDays = DayOfWeek.entries.toSet()
        assertEquals(DayOfWeek.entries.toSet(), reloaded.quietDays)
    }
}
