package io.github.shhhapp.shhh.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import io.mockk.every
import io.mockk.mockk
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
class HushAlarmsTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // SharedPreferences outlive a single test in a Robolectric class run.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ShhhSettings(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun LocalDateTime.toMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `canScheduleExact returns false when permission missing`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(HushAlarms.canScheduleExact(context))
    }

    @Test
    fun `canScheduleExact returns true when permission granted`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(HushAlarms.canScheduleExact(context))
    }

    @Test
    fun `scheduleTimerRestore returns false when permission missing`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(HushAlarms.scheduleTimerRestore(context, System.currentTimeMillis() + 1000))
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `scheduleTimerRestore returns true when permission granted`() {
        val end = System.currentTimeMillis() + 1000
        assertTrue(HushAlarms.scheduleTimerRestore(context, end))

        val alarm = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertEquals(end, alarm!!.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        assertTrue("must fire even in doze", alarm.isAllowWhileIdle)
    }

    @Test
    fun `cancelTimerRestore removes a pending restore`() {
        HushAlarms.scheduleTimerRestore(context, System.currentTimeMillis() + 1000)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        HushAlarms.cancelTimerRestore(context)

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `cancelTimerRestore is harmless when nothing is armed`() {
        HushAlarms.cancelTimerRestore(context)

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `syncQuietHoursAlarm schedules the next start`() {
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = 23 * 60
        settings.quietDays = DayOfWeek.entries.toSet()

        // Wednesday 2026-08-26, before the 23:00 start.
        HushAlarms.syncQuietHoursAlarm(context, LocalDateTime.of(2026, 8, 26, 12, 0))

        val alarm = shadowOf(alarmManager).peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertEquals(LocalDateTime.of(2026, 8, 26, 23, 0).toMillis(), alarm!!.triggerAtMs)
    }

    @Test
    fun `syncQuietHoursAlarm cancels the alarm when quiet hours are off`() {
        settings.quietHoursEnabled = true
        HushAlarms.syncQuietHoursAlarm(context, LocalDateTime.of(2026, 8, 26, 12, 0))
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        settings.quietHoursEnabled = false
        HushAlarms.syncQuietHoursAlarm(context, LocalDateTime.of(2026, 8, 26, 12, 0))

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `syncQuietHoursAlarm schedules nothing without the exact-alarm grant`() {
        settings.quietHoursEnabled = true
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        HushAlarms.syncQuietHoursAlarm(context, LocalDateTime.of(2026, 8, 26, 12, 0))

        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `scheduleTimerRestore returns false when the grant is revoked mid-call`() {
        val refusing = mockk<AlarmManager>(relaxed = true)
        every { refusing.canScheduleExactAlarms() } returns true
        every {
            refusing.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>())
        } throws SecurityException("SCHEDULE_EXACT_ALARM revoked")
        val refusingContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.ALARM_SERVICE) refusing else super.getSystemService(name)
        }

        assertFalse(HushAlarms.scheduleTimerRestore(refusingContext, System.currentTimeMillis()))
    }
}
