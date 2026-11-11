package io.github.shhhapp.shhh

import android.app.Activity
import android.media.AudioManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.schedule.HushAlarms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** CountdownNotifier's private slot; pinned here on purpose. */
private const val COUNTDOWN_NOTIFICATION_ID = 1

/** See SurfaceIntegrationTest.sendAction for why the wait comes first. */
private fun sendAction(device: DeviceAudio, action: String, extras: String = "") {
    awaitNoTrampoline()
    shell("am start -a $action -n ${device.context.packageName}/.ToggleActivity $extras")
}

private fun awaitNoTrampoline() {
    waitFor("no trampoline instance left running") {
        !isActivityAlive("ToggleActivity")
    }
}

/**
 * Finishes every activity of ours still alive, so no MainActivity started by
 * a test (it listens for ringer changes while it lives) can act in the next.
 */
private fun finishOurActivities() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)) {
            monitor.getActivitiesInStage(stage).forEach(Activity::finish)
        }
    }
    waitFor("our activities are gone", timeoutMillis = 10_000) {
        !isActivityAlive("MainActivity")
    }
}

/**
 * A timed hush with no "Alarms & reminders" grant, on a real device.
 *
 * The grant cannot be revoked from here: the system kills the app's uid when
 * it is, test runner included. These tests therefore rely on the fresh-install
 * default (no grant), which connectedAndroidTest provides, and the class name
 * sorts it ahead of every class that grants it. If some earlier test already
 * granted it, they are skipped rather than reported as passing.
 */
@RunWith(AndroidJUnit4::class)
class AbsentExactAlarmGrantIntegrationTest {

    /**
     * Sees every composition in this process, dialogs included, whoever
     * started the activity — here ToggleActivity, not the test. (A shell
     * `uiautomator dump` cannot: it competes with the runner's own
     * UiAutomation connection.)
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings

    private val explanationTitle: String
        get() = device.context.getString(R.string.exact_alarm_title)

    private fun awaitExplanation() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(explanationTitle).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Before
    fun setUp() {
        device = DeviceAudio()
        settings = ShhhSettings(device.context)
        assumeFalse(
            "needs the fresh-install default: no exact-alarm grant",
            HushAlarms.canScheduleExact(device.context)
        )
        shell("cmd statusbar collapse")
        device.notificationManager.cancelAll()
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
    }

    @After
    fun tearDown() {
        finishOurActivities()
        device.notificationManager.cancelAll()
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    private fun assertNothingChanged() {
        assertFalse(
            "a timed hush that cannot end must not hush",
            waitUntilOrGiveUp(timeoutMillis = 1_500) { device.ringVolume == 0 }
        )
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
        assertEquals(DeviceAudio.DEFAULT_MEDIA_VOLUME, device.mediaVolume)
        assertEquals(0L, settings.timerEndMillis)
        assertFalse(
            "no countdown may promise a restore",
            device.notificationManager.activeNotifications.any {
                it.id == COUNTDOWN_NOTIFICATION_ID
            }
        )
    }

    @Test
    fun timedHushShortcut_changesNothing_andOpensTheAppOnTheExplanation() {
        // What the "Hush for 30 min" launcher shortcut sends. Measured before
        // the fix: a 1-minute hush still hushed 75 s later, its notification
        // counting past zero.
        sendAction(device, ToggleActivity.ACTION_HUSH, "--ei ${ToggleActivity.EXTRA_DURATION_MINUTES} 1")

        awaitExplanation()
        assertNothingChanged()
    }

    /** Our activities currently resumed, read on the main thread. */
    private fun resumedActivities(): List<Activity> {
        var resumed = emptyList<Activity>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).toList()
        }
        return resumed
    }

    @Test
    fun timedHushShortcut_withTheAppAlreadyOpen_explainsInThatSameApp() {
        // Opened the way the launcher does. (Not ActivityScenario: it tracks
        // its activity by the launch intent, which onNewIntent replaces.)
        shell("am start -W -n ${device.context.packageName}/.MainActivity")
        waitFor("the app is open") { resumedActivities().any { it is MainActivity } }
        val opened = resumedActivities().single { it is MainActivity }

        sendAction(device, ToggleActivity.ACTION_HUSH, "--ei ${ToggleActivity.EXTRA_DURATION_MINUTES} 30")

        awaitExplanation()
        waitFor("the app is back in front") { resumedActivities().any { it is MainActivity } }
        assertSame(
            "delivered to the running activity (onNewIntent), not a new one",
            opened,
            resumedActivities().single { it is MainActivity }
        )
        assertNothingChanged()
    }

    @Test
    fun untimedHush_needsNoGrant() {
        sendAction(device, ToggleActivity.ACTION_HUSH)

        device.awaitTransition(ring = 0, media = 0, what = "untimed hush")
        assertEquals(0L, settings.timerEndMillis)
    }
}

/**
 * The hush timer and the restore, against the real AudioService, AlarmManager
 * and NotificationManagerService.
 */
@RunWith(AndroidJUnit4::class)
class TimerIntegrityIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController
    private var originalTimezone: String = ""

    private val countdownShown: Boolean
        get() = device.notificationManager.activeNotifications.any {
            it.id == COUNTDOWN_NOTIFICATION_ID
        }

    /**
     * Whether AlarmManager holds shhh's timer-restore alarm. A pending alarm
     * prints its tag on a line of its own; the history further down repeats
     * the tag after "type=...", which must not count.
     */
    private val restoreAlarmScheduled: Boolean
        get() = shell("dumpsys alarm").lineSequence().any {
            it.trim() == "tag=*walarm*:${HushAlarms.ACTION_TIMER_RESTORE}"
        }

    @Before
    fun setUp() {
        device = DeviceAudio()
        settings = ShhhSettings(device.context)
        controller = QuietModeController(device.context, settings)
        originalTimezone = shell("getprop persist.sys.timezone").trim()
        shell("pm grant ${device.context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("cmd statusbar collapse")
        device.notificationManager.cancelAll()
        device.resetShhhState(settings)
        device.resetToAudibleNoZen()
        device.allowExactAlarms()
    }

    @After
    fun tearDown() {
        if (originalTimezone.isNotEmpty()) shell("cmd alarm set-timezone $originalTimezone")
        finishOurActivities()
        HushManager(device.context).unhush()
        device.notificationManager.cancelAll()
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    // ---- bug: a restore overwrote a slider that was already audible ----

    @Test
    fun unhushAction_onAnAudiblePhone_keepsTheUsersVolumes() {
        // Measured before the fix: not hushed, media at 7, UNHUSH -> media 5,
        // the level saved at the last hush.
        settings.previousRingVolume = DeviceAudio.DEFAULT_RING_VOLUME
        settings.previousMediaVolume = DeviceAudio.DEFAULT_MEDIA_VOLUME
        device.resetToAudibleNoZen(ring = 6, media = 7)

        sendAction(device, ToggleActivity.ACTION_UNHUSH)
        awaitNoTrampoline()

        assertFalse(
            "the un-hush must leave audible sliders alone",
            waitUntilOrGiveUp(timeoutMillis = 1_500) {
                device.mediaVolume != 7 || device.ringVolume != 6
            }
        )
    }

    @Test
    fun restoreNow_afterRaisingTheRingByHand_stillBringsMediaBack() {
        controller.goQuiet()
        device.awaitTransition(ring = 0, media = 0, what = "hushed")
        device.audioManager.setStreamVolume(AudioManager.STREAM_RING, 6, 0)
        waitFor("ring raised by hand") { device.ringVolume == 6 }

        sendAction(device, ToggleActivity.ACTION_UNHUSH)

        device.awaitTransition(
            ring = 6,
            media = DeviceAudio.DEFAULT_MEDIA_VOLUME,
            what = "media back, hand-set ring kept"
        )
    }

    // ---- bug: an un-hush shhh never saw left the timer standing ----

    @Test
    fun openingTheApp_afterRaisingTheRingByHand_disarmsTheTimer() {
        sendAction(device, ToggleActivity.ACTION_HUSH, "--ei ${ToggleActivity.EXTRA_DURATION_MINUTES} 30")
        device.awaitTransition(ring = 0, media = 0, what = "timed hush")
        waitFor("countdown posted") { countdownShown }
        assertTrue("the restore alarm is visible to this check", restoreAlarmScheduled)
        // The system volume panel, not shhh.
        device.audioManager.setStreamVolume(
            AudioManager.STREAM_RING, DeviceAudio.DEFAULT_RING_VOLUME, 0
        )
        waitFor("ring raised by hand") { device.ringVolume == DeviceAudio.DEFAULT_RING_VOLUME }
        assertTrue("nothing has observed the phone yet", settings.timerEndMillis > 0)

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("timer disarmed when the app looked") { settings.timerEndMillis == 0L }
            waitFor("countdown taken down") { !countdownShown }
            waitFor("restore alarm cancelled") { !restoreAlarmScheduled }
        }
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
    }

    @Test
    fun aTimedHushStartedWithTheAppOpen_keepsItsTimer() {
        // The app resumes the moment the trampoline finishes, and that resume
        // observes the phone: it must see the fresh hush, not kill its timer.
        ActivityScenario.launch(MainActivity::class.java).use {
            sendAction(device, ToggleActivity.ACTION_HUSH, "--ei ${ToggleActivity.EXTRA_DURATION_MINUTES} 30")
            device.awaitTransition(ring = 0, media = 0, what = "timed hush")
            awaitNoTrampoline()

            assertFalse(
                "the timer must survive the app's own resume",
                waitUntilOrGiveUp(timeoutMillis = 1_500) { settings.timerEndMillis == 0L }
            )
            assertTrue(restoreAlarmScheduled)
            assertTrue(countdownShown)
        }
    }

    // ---- bug: a timer that expired while the phone was off was dropped ----

    @Test
    fun anExpiredTimer_isRestoredWhenTheClockChanges() {
        // Stands in for a reboot past the timer's end: BOOT_COMPLETED cannot
        // be sent from a test, but a timezone change reaches the same receiver
        // through the system's own broadcast. The ring volume is still 0 then,
        // exactly as measured after a real reboot.
        controller.goQuiet()
        device.awaitTransition(ring = 0, media = 0, what = "hushed")
        settings.timerEndMillis = System.currentTimeMillis() - 60_000L

        val other = if (originalTimezone == "Asia/Tokyo") "Europe/Paris" else "Asia/Tokyo"
        shell("cmd alarm set-timezone $other")

        waitFor("restored after the clock change", timeoutMillis = 10_000) {
            device.ringVolume == DeviceAudio.DEFAULT_RING_VOLUME &&
                device.mediaVolume == DeviceAudio.DEFAULT_MEDIA_VOLUME
        }
        waitFor("timer cleared once the restore happened") { settings.timerEndMillis == 0L }
    }
}
