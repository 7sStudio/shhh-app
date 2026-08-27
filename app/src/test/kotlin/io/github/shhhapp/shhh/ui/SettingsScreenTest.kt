package io.github.shhhapp.shhh.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.core.TimeFormat
import io.github.shhhapp.shhh.widget.ShhhWidgetReceiver
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
import java.time.DayOfWeek
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    /** The pure formatter behind the composable readouts. */
    private fun clock(minutesOfDay: Int) = TimeFormat.minutesOfDay(context, minutesOfDay)
    private lateinit var settings: ShhhSettings

    private var backs = 0
    private var quietChanges = 0
    private var settingChanges = 0
    private var notificationRequests = 0
    private var bluetoothRequests = 0
    private var exactAlarmRequests = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = ShhhSettings(context).apply {
            // SharedPreferences survive between tests in one class run.
            hushRinger = ShhhSettings.HushRinger.VIBRATE
            restoreMode = ShhhSettings.RestoreMode.PREVIOUS
            fixedRestorePercent = 50
            quietHoursEnabled = false
            quietStartMinutes = 23 * 60
            quietEndMinutes = 7 * 60
            quietDays = DayOfWeek.entries.toSet()
            liveCountdownEnabled = true
            headphonesAutoRestore = false
        }
        drainStartedActivities()
    }

    private fun setScreen(canScheduleExact: Boolean = true) {
        composeTestRule.setContent {
            SettingsScreen(
                settings = settings,
                canScheduleExact = canScheduleExact,
                onBack = { backs++ },
                onQuietHoursChanged = { quietChanges++ },
                onSettingChanged = { settingChanges++ },
                onRequestNotificationPermission = { notificationRequests++ },
                onRequestBluetoothPermission = { bluetoothRequests++ },
                onRequestExactAlarmAccess = { exactAlarmRequests++ }
            )
        }
    }

    private fun drainStartedActivities() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        while (shadowOf(app).nextStartedActivity != null) {
            // Clear anything the test host started before we look for our own.
        }
    }

    private fun nextViewIntent(): Intent? {
        val app = ApplicationProvider.getApplicationContext<Application>()
        while (true) {
            val next = shadowOf(app).nextStartedActivity ?: return null
            if (next.action == Intent.ACTION_VIEW) return next
        }
    }

    // ---- Chrome ----

    @Test
    fun `renders every section header`() {
        setScreen()

        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hush behavior").assertIsDisplayed()
        // "Quiet hours" is both the section header and the switch row title.
        assertEquals(
            2,
            composeTestRule.onAllNodesWithText("Quiet hours").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithText("Notifications").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Headphones").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Quick access").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("About").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `back arrow fires onBack`() {
        setScreen()

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `version row carries the app name and is not clickable`() {
        setScreen()

        composeTestRule.onNodeWithText("Shhh").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Shhh")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    // ---- Ringer ----

    @Test
    fun `ringer toggle persists silent and back to vibrate`() {
        setScreen()

        composeTestRule.onNodeWithText("Silent").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(ShhhSettings.HushRinger.SILENT, settings.hushRinger)
        assertEquals(1, settingChanges)

        composeTestRule.onNodeWithText("Vibrate").performClick()
        composeTestRule.waitForIdle()

        assertEquals(ShhhSettings.HushRinger.VIBRATE, settings.hushRinger)
        assertEquals(2, settingChanges)
    }

    // ---- Restore level ----

    @Test
    fun `choosing fixed restore persists the mode and reveals the slider`() {
        setScreen()

        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assertDoesNotExist()

        composeTestRule.onNodeWithText("Fixed level").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(ShhhSettings.RestoreMode.FIXED, settings.restoreMode)
        assertEquals(1, settingChanges)
        // The label switches to the "Fixed level · N%" form once fixed is chosen.
        composeTestRule.onNodeWithText("Fixed level · 50%").assertExists()
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assertExists()

        composeTestRule.onNodeWithText("Previous level").performClick()
        composeTestRule.waitForIdle()

        assertEquals(ShhhSettings.RestoreMode.PREVIOUS, settings.restoreMode)
        assertEquals(2, settingChanges)
        composeTestRule.onNodeWithText("Fixed level").assertExists()
    }

    @Test
    fun `moving the fixed level slider persists the percentage`() {
        settings.restoreMode = ShhhSettings.RestoreMode.FIXED
        setScreen()

        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        composeTestRule.waitForIdle()

        assertEquals(80, settings.fixedRestorePercent)
        assertEquals(1, settingChanges)
        composeTestRule.onNodeWithText("Fixed level · 80%").assertExists()

        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        composeTestRule.waitForIdle()

        assertEquals(10, settings.fixedRestorePercent)
        assertEquals(2, settingChanges)
    }

    // ---- Quiet hours ----

    @Test
    fun `turning quiet hours on persists and reveals the dial and day chips`() {
        setScreen()

        composeTestRule.onNodeWithTag("toggle_quiet").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.quietHoursEnabled)
        assertEquals(1, notificationRequests)
        assertEquals(1, quietChanges)
        composeTestRule.onNodeWithTag("quiet_dial").assertExists()
        composeTestRule.onNodeWithText("Starts").assertExists()
        composeTestRule.onNodeWithText("Ends").assertExists()
        // Narrow day names for en-US: M T W T F S S.
        composeTestRule.onNodeWithText("M").assertExists()
        composeTestRule.onNodeWithText("W").assertExists()
        composeTestRule.onNodeWithText("F").assertExists()
    }

    @Test
    fun `turning quiet hours off persists and hides the dial`() {
        settings.quietHoursEnabled = true
        setScreen()

        composeTestRule.onNodeWithTag("quiet_dial").assertExists()

        composeTestRule.onNodeWithTag("toggle_quiet").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertFalse(settings.quietHoursEnabled)
        assertEquals(0, notificationRequests)
        assertEquals(1, quietChanges)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("quiet_dial").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `quiet hours is blocked without exact alarm access`() {
        setScreen(canScheduleExact = false)

        composeTestRule.onNodeWithTag("toggle_quiet").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertFalse(settings.quietHoursEnabled)
        assertEquals(0, quietChanges)
        assertEquals(0, notificationRequests)
        composeTestRule.onNodeWithTag("quiet_dial").assertDoesNotExist()
        composeTestRule.onNodeWithText("Allow alarms & reminders").assertIsDisplayed()
    }

    @Test
    fun `granting exact alarm access from the dialog opens system settings`() {
        setScreen(canScheduleExact = false)
        composeTestRule.onNodeWithTag("toggle_quiet").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Open settings").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, exactAlarmRequests)
        assertFalse(settings.quietHoursEnabled)
        composeTestRule.onNodeWithText("Allow alarms & reminders").assertDoesNotExist()
    }

    @Test
    fun `dismissing the exact alarm dialog changes nothing`() {
        setScreen(canScheduleExact = false)
        composeTestRule.onNodeWithTag("toggle_quiet").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, exactAlarmRequests)
        assertFalse(settings.quietHoursEnabled)
        composeTestRule.onNodeWithText("Allow alarms & reminders").assertDoesNotExist()
    }

    @Test
    fun `tapping a selected day removes it and tapping it again adds it back`() {
        settings.quietHoursEnabled = true
        setScreen()

        composeTestRule.onNodeWithText("M").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertFalse(DayOfWeek.MONDAY in settings.quietDays)
        assertEquals(6, settings.quietDays.size)
        assertEquals(1, quietChanges)

        composeTestRule.onNodeWithText("M").performClick()
        composeTestRule.waitForIdle()

        assertTrue(DayOfWeek.MONDAY in settings.quietDays)
        assertEquals(7, settings.quietDays.size)
        assertEquals(2, quietChanges)
    }

    @Test
    fun `tapping an unselected day adds only that day`() {
        settings.quietHoursEnabled = true
        settings.quietDays = emptySet()
        setScreen()

        composeTestRule.onNodeWithText("W").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(setOf(DayOfWeek.WEDNESDAY), settings.quietDays)
        assertEquals(1, quietChanges)
    }

    @Test
    fun `dragging the dial persists the new quiet window`() {
        settings.quietHoursEnabled = true
        setScreen()

        composeTestRule.onNodeWithTag("quiet_dial").performScrollTo().performTouchInput {
            // Any radius on the ring works; only the angle is read back.
            val radius = width * 0.4f
            fun onRing(minutes: Int): Offset {
                val radians =
                    Math.toRadians(DialGeometry.minutesToAngle(minutes).toDouble())
                return Offset(
                    width / 2f + radius * cos(radians).toFloat(),
                    height / 2f + radius * sin(radians).toFloat()
                )
            }
            down(onRing(23 * 60))
            moveTo(onRing(22 * 60))
            moveTo(onRing(21 * 60))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(21 * 60, settings.quietStartMinutes)
        assertEquals(7 * 60, settings.quietEndMinutes)
        assertEquals(1, quietChanges)
        composeTestRule.onNodeWithText(clock(21 * 60)).assertExists()
    }

    // ---- Time pickers ----

    @Test
    fun `confirming the start time picker persists the new start`() {
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = 22 * 60
        setScreen()

        composeTestRule.onNodeWithText("Starts").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 10 PM, so the clock is in PM: picking "3" means 15:00.
        composeTestRule.onNodeWithContentDescription("3 o'clock")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.waitForIdle()

        assertEquals(15 * 60, settings.quietStartMinutes)
        assertEquals(7 * 60, settings.quietEndMinutes)
        assertEquals(1, quietChanges)
        composeTestRule.onNodeWithText("OK").assertDoesNotExist()
        composeTestRule.onNodeWithText(clock(15 * 60)).assertExists()
    }

    @Test
    fun `cancelling the start time picker persists nothing`() {
        settings.quietHoursEnabled = true
        settings.quietStartMinutes = 22 * 60
        setScreen()

        composeTestRule.onNodeWithText("Starts").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("3 o'clock")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertEquals(22 * 60, settings.quietStartMinutes)
        assertEquals(0, quietChanges)
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `confirming the end time picker persists the new end`() {
        settings.quietHoursEnabled = true
        settings.quietEndMinutes = 7 * 60
        setScreen()

        composeTestRule.onNodeWithText("Ends").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 7 AM, so the clock is in AM: picking "3" means 03:00.
        composeTestRule.onNodeWithContentDescription("3 o'clock")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.waitForIdle()

        assertEquals(3 * 60, settings.quietEndMinutes)
        assertEquals(23 * 60, settings.quietStartMinutes)
        assertEquals(1, quietChanges)
        composeTestRule.onNodeWithText(clock(3 * 60)).assertExists()
    }

    @Test
    fun `cancelling the end time picker persists nothing`() {
        settings.quietHoursEnabled = true
        setScreen()

        composeTestRule.onNodeWithText("Ends").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertEquals(7 * 60, settings.quietEndMinutes)
        assertEquals(0, quietChanges)
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    // ---- Notifications / headphones ----

    @Test
    fun `live countdown switch persists and only asks for notifications when on`() {
        setScreen()

        composeTestRule.onNodeWithText("Live countdown").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertFalse(settings.liveCountdownEnabled)
        assertEquals(0, notificationRequests)
        assertEquals(1, settingChanges)

        composeTestRule.onNodeWithText("Live countdown").performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.liveCountdownEnabled)
        assertEquals(1, notificationRequests)
        assertEquals(2, settingChanges)
    }

    @Test
    fun `headphones switch persists and only asks for permissions when on`() {
        setScreen()

        composeTestRule.onNodeWithText("Un-mute media for headphones")
            .performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.headphonesAutoRestore)
        assertEquals(1, bluetoothRequests)
        assertEquals(1, notificationRequests)
        assertEquals(1, settingChanges)

        composeTestRule.onNodeWithText("Un-mute media for headphones").performClick()
        composeTestRule.waitForIdle()

        assertFalse(settings.headphonesAutoRestore)
        assertEquals(1, bluetoothRequests)
        assertEquals(1, notificationRequests)
        assertEquals(2, settingChanges)
    }

    // ---- Quick access ----

    @Test
    fun `quick settings tile row is offered and does not navigate away`() {
        setScreen()

        composeTestRule.onNodeWithText("Quick Settings tile").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // ShadowStatusBarManager does not record the request, so the observable
        // contract is that the row stays put and nothing else is launched.
        assertNull(nextViewIntent())
        composeTestRule.onNodeWithText("Quick Settings tile").assertIsDisplayed()
    }

    @Test
    fun `tile row is a no-op when the platform has no status bar service`() {
        val withoutStatusBar = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.STATUS_BAR_SERVICE) null else super.getSystemService(name)
        }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides withoutStatusBar) {
                SettingsScreen(
                    settings = settings,
                    canScheduleExact = true,
                    onBack = { backs++ },
                    onQuietHoursChanged = { quietChanges++ },
                    onSettingChanged = { settingChanges++ },
                    onRequestNotificationPermission = { notificationRequests++ },
                    onRequestBluetoothPermission = { bluetoothRequests++ },
                    onRequestExactAlarmAccess = { exactAlarmRequests++ }
                )
            }
        }

        composeTestRule.onNodeWithText("Quick Settings tile").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNull(nextViewIntent())
        composeTestRule.onNodeWithText("Quick Settings tile").assertIsDisplayed()
    }

    @Test
    fun `widget row asks the launcher to pin the widget`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setRequestPinAppWidgetSupported(true)
        setScreen()

        composeTestRule.onNodeWithText("Home screen widget").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val ids = manager.getAppWidgetIds(
            ComponentName(context, ShhhWidgetReceiver::class.java)
        )
        assertEquals(1, ids.size)
    }

    @Test
    fun `widget row does nothing when pinning is unsupported`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setRequestPinAppWidgetSupported(false)
        setScreen()

        composeTestRule.onNodeWithText("Home screen widget").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val ids = manager.getAppWidgetIds(
            ComponentName(context, ShhhWidgetReceiver::class.java)
        )
        assertEquals(0, ids.size)
    }

    @Test
    fun `automation row opens a sheet listing the four intents`() {
        setScreen()

        composeTestRule.onNodeWithText("Automation").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Automation intents").assertExists()
        composeTestRule.onNodeWithText(
            "…action.HUSH — hush now (optional extra duration_minutes)"
        ).assertExists()
        composeTestRule.onNodeWithText("…action.UNHUSH — restore sound").assertExists()
        composeTestRule.onNodeWithText("…action.TOGGLE — flip current state").assertExists()
        composeTestRule.onNodeWithText("…action.RESTORE_MEDIA — media volume only").assertExists()

        composeTestRule.onNodeWithContentDescription("Close sheet")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Automation intents")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    // ---- About ----

    @Test
    fun `source code row opens the GitHub repository`() {
        setScreen()

        composeTestRule.onNodeWithText("Source code").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val intent = nextViewIntent()
        assertEquals("https://github.com/7sStudio/shhh-app", intent?.data?.toString())
    }

    @Test
    fun `troubleshooting row opens the dontkillmyapp guide`() {
        setScreen()

        composeTestRule.onNodeWithText("Troubleshooting").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val intent = nextViewIntent()
        assertEquals("https://dontkillmyapp.com", intent?.data?.toString())
    }
}
