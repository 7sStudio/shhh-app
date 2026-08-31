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
import io.github.shhhapp.shhh.update.UpdateChecker
import io.github.shhhapp.shhh.widget.ShhhTransparentWidgetReceiver
import io.github.shhhapp.shhh.widget.ShhhWidgetReceiver
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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
            restoreMode = ShhhSettings.RestoreMode.PREVIOUS
            fixedRestorePercent = 50
            quietHoursEnabled = false
            quietStartMinutes = 23 * 60
            quietEndMinutes = 7 * 60
            quietDays = DayOfWeek.entries.toSet()
            liveCountdownEnabled = true
            headphonesAutoRestore = false
            autoUpdateCheckEnabled = false
            lastUpdateCheckMillis = 0L
            lastPromptedUpdateVersion = ""
        }
        drainStartedActivities()
    }

    @After
    fun tearDown() {
        server?.shutdown()
    }

    /** Started lazily; only the update tests need a server. */
    private var server: MockWebServer? = null

    private fun startServer(): MockWebServer =
        MockWebServer().also { it.start(); server = it }

    private fun setScreen(canScheduleExact: Boolean = true, updateChecker: UpdateChecker? = null) {
        composeTestRule.setContent {
            if (updateChecker == null) {
                // Omitting the parameter exercises the composable's default.
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
            } else {
                SettingsScreen(
                    settings = settings,
                    canScheduleExact = canScheduleExact,
                    onBack = { backs++ },
                    onQuietHoursChanged = { quietChanges++ },
                    onSettingChanged = { settingChanges++ },
                    onRequestNotificationPermission = { notificationRequests++ },
                    onRequestBluetoothPermission = { bluetoothRequests++ },
                    onRequestExactAlarmAccess = { exactAlarmRequests++ },
                    updateChecker = updateChecker
                )
            }
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
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
    // The Vibrate/Silent selector is gone: shhh moves volume sliders only, and
    // setRingerMode(SILENT) — the only way to reach silent — starts a zen mode.

    @Test
    fun `no ringer selector is offered any more`() {
        setScreen()

        composeTestRule.onNodeWithText("Silent").assertDoesNotExist()
        composeTestRule.onNodeWithText("Vibrate").assertDoesNotExist()
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
    fun `transparent widget row pins the transparent widget, not the card`() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).setRequestPinAppWidgetSupported(true)
        setScreen()

        composeTestRule.onNodeWithText("Shhh (transparent)").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val transparentIds = manager.getAppWidgetIds(
            ComponentName(context, ShhhTransparentWidgetReceiver::class.java)
        )
        val cardIds = manager.getAppWidgetIds(
            ComponentName(context, ShhhWidgetReceiver::class.java)
        )
        assertEquals(1, transparentIds.size)
        assertEquals(0, cardIds.size)
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

    // ---- Updates ----

    private fun releaseJson(tag: String) = """
        {
          "tag_name": "$tag",
          "body": "Shiny new things.",
          "assets": [{"name": "shhh.apk",
            "browser_download_url": "https://example.invalid/shhh.apk", "size": 10}]
        }
    """.trimIndent()

    private fun checkerFor(server: MockWebServer) =
        UpdateChecker(server.url("/latest").toString())

    @Test
    fun `updates section renders with the switch off by default`() {
        setScreen()

        composeTestRule.onNodeWithText("Updates").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Check for updates automatically")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Tap to check the latest release on GitHub")
            .assertIsDisplayed()
    }

    @Test
    fun `auto update switch persists both ways`() {
        setScreen()

        composeTestRule.onNodeWithTag("toggle_auto_update").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(settings.autoUpdateCheckEnabled)
        assertEquals(1, settingChanges)

        composeTestRule.onNodeWithTag("toggle_auto_update").performClick()
        composeTestRule.waitForIdle()

        assertFalse(settings.autoUpdateCheckEnabled)
        assertEquals(2, settingChanges)
    }

    @Test
    fun `a manual check that finds an update opens the dialog and keeps the row hint`() {
        val server = startServer()
        server.enqueue(
            MockResponse().setBody(releaseJson("v99.0.0"))
                .setBodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        setScreen(updateChecker = checkerFor(server))

        composeTestRule.onNodeWithText("Check for updates").performScrollTo().performClick()
        awaitText("Checking…")
        awaitText("Update available")

        composeTestRule.onNodeWithText("Shhh 99.0.0 is ready to download.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shiny new things.").assertIsDisplayed()

        composeTestRule.onNodeWithText("Later").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Update available").assertDoesNotExist()
        composeTestRule
            .onNodeWithText("Version 99.0.0 is available — tap to update")
            .assertIsDisplayed()

        // Re-tapping reopens the dialog from memory instead of re-checking.
        composeTestRule.onNodeWithText("Check for updates").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Update available").assertIsDisplayed()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a manual check on the latest version reports up to date`() {
        val server = startServer()
        server.enqueue(
            MockResponse().setBody(releaseJson("v${io.github.shhhapp.shhh.BuildConfig.VERSION_NAME}"))
        )
        setScreen(updateChecker = checkerFor(server))

        composeTestRule.onNodeWithText("Check for updates").performScrollTo().performClick()
        awaitText("You're up to date")

        composeTestRule.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test
    fun `a failed manual check reports the error`() {
        val server = startServer()
        server.enqueue(MockResponse().setResponseCode(500))
        setScreen(updateChecker = checkerFor(server))

        composeTestRule.onNodeWithText("Check for updates").performScrollTo().performClick()
        awaitText("Couldn't reach GitHub", substring = true)

        composeTestRule.onNodeWithText("Update available").assertDoesNotExist()
    }

    // ---- About ----

    @Test
    fun `contact row opens a pre-filled email to the developer`() {
        setScreen()

        composeTestRule.onNodeWithText("Contact the developer").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val app = ApplicationProvider.getApplicationContext<Application>()
        var intent: Intent?
        do {
            intent = shadowOf(app).nextStartedActivity
        } while (intent != null && intent.action != Intent.ACTION_SENDTO)
        val email = requireNotNull(intent)
        assertEquals("mailto:", email.data?.toString())
        assertEquals(
            listOf("7sStudio@tutamail.com"),
            email.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList()
        )
        assertEquals(
            "Shhh ${io.github.shhhapp.shhh.BuildConfig.VERSION_NAME} — feedback",
            email.getStringExtra(Intent.EXTRA_SUBJECT)
        )
    }

    @Test
    fun `contact row survives a phone without an email app`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).checkActivities(true)
        setScreen()

        composeTestRule.onNodeWithText("Contact the developer").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // No crash and the screen is still there.
        composeTestRule.onNodeWithText("Contact the developer").assertIsDisplayed()
        shadowOf(app).checkActivities(false)
    }

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
