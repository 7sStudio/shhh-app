package io.github.shhhapp.shhh.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
class ReceiverTests {

    private lateinit var context: Application
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

    private val nextAlarm get() = shadowOf(alarmManager).peekNextScheduledAlarm()

    @Test
    fun `AlarmReceiver starts HushService with same action`() {
        val receiver = AlarmReceiver()
        val intent = Intent(HushAlarms.ACTION_TIMER_RESTORE)

        receiver.onReceive(context, intent)

        val serviceIntent = shadowOf(context).nextStartedService
        assertNotNull("HushService should be started", serviceIntent)
        assertEquals(HushService::class.java.name, serviceIntent!!.component!!.className)
        assertEquals(HushAlarms.ACTION_TIMER_RESTORE, serviceIntent.action)
    }

    @Test
    fun `AlarmReceiver forwards quiet-hours starts too`() {
        AlarmReceiver().onReceive(context, Intent(HushAlarms.ACTION_QUIET_START))

        val serviceIntent = shadowOf(context).nextStartedService
        assertNotNull("HushService should be started", serviceIntent)
        assertEquals(HushAlarms.ACTION_QUIET_START, serviceIntent!!.action)
    }

    @Test
    fun `AlarmReceiver ignores null action`() {
        val receiver = AlarmReceiver()
        receiver.onReceive(context, Intent())
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun `BootReceiver re-arms future timer`() {
        val future = System.currentTimeMillis() + 100_000L
        settings.timerEndMillis = future
        val receiver = BootReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val shadowAlarm = nextAlarm
        assertNotNull("Alarm should be re-scheduled", shadowAlarm)
        assertEquals(future, shadowAlarm!!.triggerAtMs)
        assertEquals(future, settings.timerEndMillis)
    }

    @Test
    fun `BootReceiver clears expired timer`() {
        val past = System.currentTimeMillis() - 100_000L
        settings.timerEndMillis = past
        val receiver = BootReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0L, settings.timerEndMillis)
        assertNull("nothing to restore any more", nextAlarm)
    }

    @Test
    fun `BootReceiver with no timer only syncs quiet hours`() {
        settings.timerEndMillis = 0L
        settings.quietHoursEnabled = false

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(0L, settings.timerEndMillis)
        assertNull(nextAlarm)
    }

    @Test
    fun `BootReceiver syncs quiet hours`() {
        settings.quietHoursEnabled = true
        val receiver = BootReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

        assertNotNull("Quiet hours alarm should be synced", nextAlarm)
    }

    @Test
    fun `BootReceiver syncs quiet hours after a timezone change`() {
        settings.quietHoursEnabled = true

        BootReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertNotNull("Quiet hours alarm should be synced", nextAlarm)
    }

    @Test
    fun `BootReceiver syncs quiet hours when the exact-alarm grant changes`() {
        settings.quietHoursEnabled = true

        BootReceiver().onReceive(
            context,
            Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        )

        assertNotNull("Quiet hours alarm should be synced", nextAlarm)
    }

    @Test
    fun `BootReceiver ignores unrelated broadcasts`() {
        val future = System.currentTimeMillis() + 100_000L
        settings.timerEndMillis = future
        settings.quietHoursEnabled = true

        BootReceiver().onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        assertNull("nothing should be scheduled", nextAlarm)
        assertEquals(future, settings.timerEndMillis)
    }
}
