package io.github.shhhapp.shhh

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * [ToggleActivity] is the app's automation surface: every other surface (tile,
 * widget, notification action, `am start`, Tasker) routes its sound changes
 * through it, so every action and extra shape is covered here against the real
 * ringer/media state rather than against a mock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToggleActivityTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var alarmManager: AlarmManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        settings = ShhhSettings(context)
        settings.timerEndMillis = 0L
        settings.previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.lastKnownQuiet = false
        settings.hushRinger = ShhhSettings.HushRinger.VIBRATE
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        settings.liveCountdownEnabled = false

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
    }

    private fun launch(action: String?, extras: Intent.() -> Unit = {}): ToggleActivity {
        val intent = Intent(context, ToggleActivity::class.java).apply {
            if (action != null) setAction(action)
            extras()
        }
        return Robolectric.buildActivity(ToggleActivity::class.java, intent).setup().get()
    }

    private val mediaVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    // ---- Do Not Disturb ----
    // Under an active DND mode the tile hands every tap to this activity,
    // because only a visible activity's audio writes are honored then.

    /** What the phone looks like to an app while Do Not Disturb is active. */
    private fun simulateDnd() {
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    @Test
    fun `toggling during dnd hushes without touching the ringer`() {
        simulateDnd()

        launch(null)

        // No ringer write (one would exit the user's DND); media muted and
        // the hush remembered for the masked state reads.
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(0, mediaVolume)
        assertTrue(settings.lastKnownQuiet)
    }

    @Test
    fun `toggling twice during dnd hushes then restores sound`() {
        simulateDnd()

        launch(null)
        launch(null)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(8, mediaVolume)
        assertTrue(!settings.lastKnownQuiet)
    }

    // ---- ACTION_HUSH ----

    @Test
    fun `hush action mutes media and switches the ringer to vibrate`() {
        val activity = launch(ToggleActivity.ACTION_HUSH)

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, mediaVolume)
        assertEquals(8, settings.previousMediaVolume)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `hush action honours the silent ringer setting`() {
        settings.hushRinger = ShhhSettings.HushRinger.SILENT

        launch(ToggleActivity.ACTION_HUSH)

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
    }

    @Test
    fun `hush action with an int duration extra arms a timer`() {
        val before = System.currentTimeMillis()

        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, 30)
        }

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertTrue(settings.timerEndMillis >= before + 30 * 60_000L)
        assertTrue(settings.timerEndMillis <= System.currentTimeMillis() + 30 * 60_000L)
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `hush action accepts a numeric string duration extra`() {
        val before = System.currentTimeMillis()

        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, "45")
        }

        assertTrue(settings.timerEndMillis >= before + 45 * 60_000L)
        assertTrue(settings.timerEndMillis <= System.currentTimeMillis() + 45 * 60_000L)
    }

    @Test
    fun `hush action ignores an unparseable string duration extra`() {
        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, "not a number")
        }

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `hush action ignores a non-positive string duration extra`() {
        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, "-5")
        }

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `hush action ignores a non-positive int duration extra`() {
        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, 0)
        }

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0L, settings.timerEndMillis)
    }

    // ---- ACTION_UNHUSH ----

    @Test
    fun `unhush action restores the ringer and the saved media volume`() {
        settings.previousMediaVolume = 7
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        val activity = launch(ToggleActivity.ACTION_UNHUSH)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(7, mediaVolume)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `unhush action cancels a running timer`() {
        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, 30)
        }
        assertNotNull(shadowOf(alarmManager).peekNextScheduledAlarm())

        launch(ToggleActivity.ACTION_UNHUSH)

        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    // ---- ACTION_TOGGLE / no action ----

    @Test
    fun `toggle action hushes when the phone is loud`() {
        launch(ToggleActivity.ACTION_TOGGLE)

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, mediaVolume)
    }

    @Test
    fun `no action defaults to toggling back to sound`() {
        settings.previousMediaVolume = 6
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        launch(action = null)

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(6, mediaVolume)
    }

    // ---- ACTION_RESTORE_MEDIA ----

    @Test
    fun `restore media action brings media back and leaves the ringer hushed`() {
        settings.previousMediaVolume = 9
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        val activity = launch(ToggleActivity.ACTION_RESTORE_MEDIA)

        assertEquals(9, mediaVolume)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `restore media action tolerates a device that refuses the volume change`() {
        // Max media volume of 0 makes every setStreamVolume land on 0, which is
        // how QuietModeController reports "the change did not take".
        shadowOf(audioManager).setStreamMaxVolume(0)
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        val activity = launch(ToggleActivity.ACTION_RESTORE_MEDIA)

        assertEquals(0, mediaVolume)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // ---- Missing DND access ----

    @Test
    fun `without DND access the hush action falls through to MainActivity`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)

        val activity = launch(ToggleActivity.ACTION_HUSH)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(8, mediaVolume)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `without DND access the toggle action falls through to MainActivity`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        val activity = launch(ToggleActivity.ACTION_TOGGLE)

        assertEquals(
            MainActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className
        )
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
    }

    // ---- Transition suppression fork ----

    @Test
    fun `on Android 14 and up the close transition is overridden`() {
        val activity = launch(ToggleActivity.ACTION_TOGGLE)

        val override = requireNotNull(
            shadowOf(activity)
                .getOverriddenActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE)
        )
        assertEquals(0, override.enterAnim)
        assertEquals(0, override.exitAnim)
    }

}
