package io.github.shhhapp.shhh

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShadowRefusingAudioManager
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.widget.WidgetUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * volume state rather than against a mock.
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
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowRefusingAudioManager.refusedStreams = emptySet()

        settings = ShhhSettings(context)
        settings.timerEndMillis = 0L
        settings.previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.previousRingVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.lastKnownQuiet = false
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        settings.liveCountdownEnabled = false

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        ringVolume = 3
        mediaVolume = 8
    }

    private fun launch(action: String?, extras: Intent.() -> Unit = {}): ToggleActivity {
        val intent = Intent(context, ToggleActivity::class.java).apply {
            if (action != null) setAction(action)
            extras()
        }
        return Robolectric.buildActivity(ToggleActivity::class.java, intent).setup().get()
    }

    private var mediaVolume: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)

    private var ringVolume: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_RING, value, 0)

    // ---- Do Not Disturb ----
    // Under an active zen mode the tile hands every tap to this activity,
    // because only a visible activity's audio writes are honored then.

    /** What the phone looks like to an app while a zen mode is running. */
    private fun simulateDnd(
        filter: Int = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    ) {
        notificationManager.setInterruptionFilter(filter)
        // Every zen masks the legacy ringer mode to SILENT; shhh must ignore it.
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    @Test
    fun `toggling during dnd hushes the sliders and leaves the zen running`() {
        simulateDnd()

        launch(null)

        assertEquals(0, ringVolume)
        assertEquals(0, mediaVolume)
        assertTrue(settings.lastKnownQuiet)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun `toggling twice during dnd hushes then restores without ending the zen`() {
        simulateDnd()

        launch(null)
        launch(null)

        assertEquals(3, ringVolume)
        assertEquals(8, mediaVolume)
        assertFalse(settings.lastKnownQuiet)
        assertEquals(
            "this exact sequence used to turn the user's Do Not Disturb off",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun `toggling under alarms only hushes rather than restoring sound`() {
        // "Alarms only" hands every app a ring volume of 0 even with shhh off,
        // so only the remembered state can say which way the toggle should go.
        simulateDnd(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        ringVolume = 0

        launch(null)

        assertTrue(settings.lastKnownQuiet)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            notificationManager.currentInterruptionFilter
        )
    }

    // ---- ACTION_HUSH ----

    @Test
    fun `hush action zeroes both sliders and saves where they were`() {
        val activity = launch(ToggleActivity.ACTION_HUSH)

        assertEquals(0, ringVolume)
        assertEquals(0, mediaVolume)
        assertEquals(3, settings.previousRingVolume)
        assertEquals(8, settings.previousMediaVolume)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `hush action with an int duration extra arms a timer`() {
        val before = System.currentTimeMillis()

        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, 30)
        }

        assertEquals(0, ringVolume)
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

        assertEquals(0, ringVolume)
        assertEquals(0L, settings.timerEndMillis)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun `hush action ignores a non-positive string duration extra`() {
        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, "-5")
        }

        assertEquals(0, ringVolume)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `hush action ignores a non-positive int duration extra`() {
        launch(ToggleActivity.ACTION_HUSH) {
            putExtra(ToggleActivity.EXTRA_DURATION_MINUTES, 0)
        }

        assertEquals(0, ringVolume)
        assertEquals(0L, settings.timerEndMillis)
    }

    // ---- ACTION_UNHUSH ----

    @Test
    fun `unhush action restores both saved volumes`() {
        settings.previousMediaVolume = 7
        settings.previousRingVolume = 4
        ringVolume = 0
        mediaVolume = 0

        val activity = launch(ToggleActivity.ACTION_UNHUSH)

        assertEquals(4, ringVolume)
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

        assertEquals(0, ringVolume)
        assertEquals(0, mediaVolume)
    }

    @Test
    fun `no action defaults to toggling back to sound`() {
        settings.previousMediaVolume = 6
        settings.previousRingVolume = 5
        ringVolume = 0
        mediaVolume = 0

        launch(action = null)

        assertEquals(5, ringVolume)
        assertEquals(6, mediaVolume)
    }

    // ---- ACTION_RESTORE_MEDIA ----

    @Test
    fun `restore media action brings media back and leaves the ringer hushed`() {
        settings.previousMediaVolume = 9
        ringVolume = 0
        mediaVolume = 0

        val activity = launch(ToggleActivity.ACTION_RESTORE_MEDIA)

        assertEquals(9, mediaVolume)
        assertEquals("the ringer must stay hushed", 0, ringVolume)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `restore media action tolerates a device that refuses the volume change`() {
        // Max media volume of 0 makes every setStreamVolume land on 0, which is
        // how QuietModeController reports "the change did not take".
        ringVolume = 0
        shadowOf(audioManager).setStreamMaxVolume(0)

        val activity = launch(ToggleActivity.ACTION_RESTORE_MEDIA)

        assertEquals(0, mediaVolume)
        assertEquals(0, ringVolume)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // ---- Missing DND access ----

    @Test
    fun `without DND access and no zen running the hush action just works`() {
        // Outside a zen mode a volume write needs no permission at all, so
        // there is nothing to send the user to MainActivity for.
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)

        val activity = launch(ToggleActivity.ACTION_HUSH)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, ringVolume)
        assertEquals(0, mediaVolume)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused hush action falls through to MainActivity`() {
        simulateDnd()
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        ShadowRefusingAudioManager.refusedStreams =
            setOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)

        val activity = launch(ToggleActivity.ACTION_HUSH)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(3, ringVolume)
        assertEquals(8, mediaVolume)
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused toggle action falls through to MainActivity`() {
        simulateDnd()
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        ringVolume = 0
        settings.lastKnownQuiet = true
        ShadowRefusingAudioManager.refusedStreams =
            setOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)

        val activity = launch(ToggleActivity.ACTION_TOGGLE)

        assertEquals(
            MainActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className
        )
        assertEquals("still hushed", 0, ringVolume)
    }

    // ---- the optimistic widget flip ----

    @Test
    fun `a toggle leaves the widget state on the truth it produced`() {
        WidgetUiState.refreshFrom(context)
        assertFalse(WidgetUiState.quiet)

        launch(ToggleActivity.ACTION_TOGGLE)

        // Optimistic flip and confirming refresh are both asynchronous; on a
        // successful toggle they agree, and the shown state must equal it.
        awaitWidgetQuiet(expected = true, why = "a successful hush")
        assertEquals(0, ringVolume)
    }

    @Test
    @Config(shadows = [ShadowRefusingAudioManager::class])
    fun `a refused toggle snaps the optimistic widget flip back to reality`() {
        // The trampoline flips the widget to the expected state before the
        // write; when Android refuses it nothing changed, and the revert
        // refresh must win over the optimistic guess.
        simulateDnd()
        WidgetUiState.refreshFrom(context)
        assertFalse(WidgetUiState.quiet)
        ShadowRefusingAudioManager.refusedStreams =
            setOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)

        launch(ToggleActivity.ACTION_TOGGLE)

        awaitWidgetQuiet(expected = false, why = "the revert after a refusal")
        // The revert must be the LAST word, not a state passed through on the
        // way to a stuck optimistic flip.
        Thread.sleep(200)
        assertFalse("the optimistic flip must not out-live the refusal", WidgetUiState.quiet)
        assertEquals("nothing may have moved", 3, ringVolume)
    }

    private fun awaitWidgetQuiet(expected: Boolean, why: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && WidgetUiState.quiet != expected) {
            Thread.sleep(20)
        }
        assertEquals("$why must leave the widget showing quiet=$expected",
            expected, WidgetUiState.quiet)
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
