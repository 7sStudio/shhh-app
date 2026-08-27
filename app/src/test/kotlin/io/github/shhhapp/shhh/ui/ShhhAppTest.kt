package io.github.shhhapp.shhh.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ShhhApp
import io.github.shhhapp.shhh.core.ShhhSettings
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * `ShhhApp` is the whole app's state machine: it owns the quiet state, the
 * timer, permission prompts and navigation. Every path here is asserted against
 * the phone's real ringer/media state and the persisted settings, not mocks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShhhAppTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        settings = ShhhSettings(context)
        settings.timerEndMillis = 0L
        settings.previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.hushRinger = ShhhSettings.HushRinger.VIBRATE
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        settings.liveCountdownEnabled = false
        settings.headphonesAutoRestore = false
        settings.quietHoursEnabled = false

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
    }

    // ---- helpers ----

    private fun launchApp() {
        composeTestRule.setContent { ShhhApp() }
        composeTestRule.waitForIdle()
    }

    private fun string(resId: Int) = context.getString(resId)

    private val mediaVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun grant(permission: String) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(permission)
    }

    private val lastPermissionRequest: Array<String>?
        get() = shadowOf(composeTestRule.activity)
            .lastRequestedPermission
            ?.requestedPermissions

    private val startedActivity: Intent?
        get() = shadowOf(composeTestRule.activity).nextStartedActivity

    /** Puts the quiet-hours window at [startOffset]..[endOffset] minutes from now. */
    private fun setQuietWindow(startOffset: Int, endOffset: Int) {
        val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
        settings.quietStartMinutes = Math.floorMod(nowMinutes + startOffset, 24 * 60)
        settings.quietEndMinutes = Math.floorMod(nowMinutes + endOffset, 24 * 60)
        settings.quietDays = DayOfWeek.entries.toSet()
    }

    private fun openSettings() {
        composeTestRule.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
        composeTestRule.waitForIdle()
    }

    // ---- the big toggle ----

    @Test
    fun `the home screen shows the phone's actual ringer state`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        launchApp()

        composeTestRule.onNodeWithText(string(R.string.status_quiet_on)).assertIsDisplayed()
    }

    @Test
    fun `the big toggle hushes the phone and then brings sound back`() {
        launchApp()

        composeTestRule
            .onNodeWithContentDescription(string(R.string.tile_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, mediaVolume)
        composeTestRule.onNodeWithText(string(R.string.status_quiet_on)).assertIsDisplayed()

        composeTestRule
            .onNodeWithContentDescription(string(R.string.tile_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(8, mediaVolume)
        composeTestRule.onNodeWithText(string(R.string.status_quiet_off)).assertIsDisplayed()
    }

    @Test
    fun `DND access revoked while the app is open surfaces the setup card`() {
        launchApp()
        composeTestRule.onNodeWithText(string(R.string.permission_title)).assertDoesNotExist()

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        composeTestRule
            .onNodeWithContentDescription(string(R.string.tile_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        composeTestRule.onNodeWithText(string(R.string.permission_title)).assertIsDisplayed()
    }

    // ---- timers ----

    @Test
    fun `a timer chip hushes for that long and shows the countdown`() {
        val before = System.currentTimeMillis()
        launchApp()

        composeTestRule.onNodeWithText(string(R.string.timer_chip_30))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, mediaVolume)
        assertTrue(settings.timerEndMillis >= before + 30 * 60_000L)
        assertTrue(settings.timerEndMillis <= System.currentTimeMillis() + 30 * 60_000L)
        val countdownPrefix = string(R.string.timer_running_until).substringBefore("%1\$s")
        composeTestRule
            .onNodeWithText(countdownPrefix, substring = true)
            .assertIsDisplayed()
        assertTrue(
            lastPermissionRequest?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
        )
    }

    @Test
    fun `End now cancels the timer and restores sound`() {
        launchApp()
        composeTestRule.onNodeWithText(string(R.string.timer_chip_30))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.timer_end_now))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(0L, settings.timerEndMillis)
        composeTestRule.onNodeWithText(string(R.string.timer_end_now)).assertDoesNotExist()
    }

    @Test
    fun `without exact alarm access a timer chip offers the system screen instead`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        launchApp()

        composeTestRule.onNodeWithText(string(R.string.timer_chip_30))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.exact_alarm_title)).assertIsDisplayed()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        composeTestRule.onNodeWithText(string(R.string.exact_alarm_grant)).performClick()
        composeTestRule.waitForIdle()

        val launched = requireNotNull(startedActivity)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, launched.action)
        assertEquals(context.packageName, launched.data?.schemeSpecificPart)
    }

    // ---- quiet hours ----

    @Test
    fun `enabling quiet hours inside the window hushes until the window ends`() {
        setQuietWindow(startOffset = -60, endOffset = 60)
        val before = System.currentTimeMillis()
        launchApp()

        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.quietHoursEnabled)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertTrue(settings.timerEndMillis >= before + 50 * 60_000L)
        assertTrue(settings.timerEndMillis <= before + 61 * 60_000L)
    }

    @Test
    fun `enabling quiet hours outside the window changes nothing yet`() {
        setQuietWindow(startOffset = 120, endOffset = 180)
        launchApp()

        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.quietHoursEnabled)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `enabling quiet hours on an already hushed phone arms nothing`() {
        setQuietWindow(startOffset = -60, endOffset = 60)
        launchApp()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.tile_content_description))
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)

        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.quietHoursEnabled)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        // The phone is already quiet, so the window end must not overwrite the
        // (absent) manual timer.
        assertEquals(0L, settings.timerEndMillis)
    }

    @Test
    fun `turning quiet hours back off leaves the current state alone`() {
        setQuietWindow(startOffset = -60, endOffset = 60)
        launchApp()
        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertFalse(settings.quietHoursEnabled)
        // Disabling the schedule must not un-hush a phone that is already quiet.
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
    }

    // ---- ringer changes made elsewhere ----

    @Test
    fun `a ringer change made outside the app updates the screen`() {
        launchApp()
        composeTestRule.onNodeWithText(string(R.string.status_quiet_off)).assertIsDisplayed()

        composeTestRule.runOnUiThread {
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.status_quiet_on)).assertIsDisplayed()
    }

    @Test
    fun `leaving the composition unregisters the ringer receiver`() {
        val visible = mutableStateOf(true)
        composeTestRule.setContent { if (visible.value) ShhhApp() }
        composeTestRule.waitForIdle()
        val whileVisible = ringerReceiverCount()
        assertTrue(whileVisible > 0)

        composeTestRule.runOnUiThread { visible.value = false }
        composeTestRule.waitForIdle()

        assertEquals(whileVisible - 1, ringerReceiverCount())
    }

    /** Receivers currently registered for [AudioManager.RINGER_MODE_CHANGED_ACTION]. */
    private fun ringerReceiverCount(): Int =
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .registeredReceivers
            .count { it.intentFilter.hasAction(AudioManager.RINGER_MODE_CHANGED_ACTION) }

    // ---- navigation ----

    @Test
    fun `the settings screen opens and the back arrow returns home`() {
        launchApp()

        openSettings()
        composeTestRule.onNodeWithText(string(R.string.settings_behavior_header))
            .assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.settings_back)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.app_tagline)).assertIsDisplayed()
    }

    @Test
    fun `the system back button returns home from settings`() {
        launchApp()
        openSettings()
        composeTestRule.onNodeWithText(string(R.string.settings_behavior_header))
            .assertIsDisplayed()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.app_tagline)).assertIsDisplayed()
        assertFalse(composeTestRule.activity.isFinishing)
    }

    // ---- settings screen callbacks ----

    @Test
    fun `enabling quiet hours from settings hushes immediately`() {
        setQuietWindow(startOffset = -60, endOffset = 60)
        val before = System.currentTimeMillis()
        launchApp()
        openSettings()

        composeTestRule.onNodeWithTag("toggle_quiet").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.quietHoursEnabled)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertTrue(settings.timerEndMillis >= before + 50 * 60_000L)
        assertTrue(
            lastPermissionRequest?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
        )
    }

    @Test
    fun `without exact alarm access the settings quiet-hours switch offers the system screen`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        launchApp()
        openSettings()

        composeTestRule.onNodeWithTag("toggle_quiet").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(string(R.string.exact_alarm_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.exact_alarm_grant)).performClick()
        composeTestRule.waitForIdle()

        assertFalse(settings.quietHoursEnabled)
        assertEquals(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            requireNotNull(startedActivity).action
        )
    }

    @Test
    fun `the headphones option asks for the Bluetooth permission`() {
        grant(Manifest.permission.POST_NOTIFICATIONS)
        launchApp()
        openSettings()

        composeTestRule.onNodeWithText(string(R.string.settings_headphones_title))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.headphonesAutoRestore)
        // Notifications are already granted, so Bluetooth is the only prompt.
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT),
            lastPermissionRequest?.toList()
        )
    }

    @Test
    fun `already granted permissions are not requested again`() {
        grant(Manifest.permission.POST_NOTIFICATIONS)
        grant(Manifest.permission.BLUETOOTH_CONNECT)
        launchApp()
        openSettings()

        composeTestRule.onNodeWithText(string(R.string.settings_headphones_title))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.headphonesAutoRestore)
        assertNull(lastPermissionRequest)
    }
}
