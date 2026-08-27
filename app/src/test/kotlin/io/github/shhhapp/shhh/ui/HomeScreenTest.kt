package io.github.shhhapp.shhh.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.core.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settings: ShhhSettings

    /** Recorded callback invocations, so every test can assert on the same object. */
    private class Recorder {
        var toggles = 0
        val hushedFor = mutableListOf<Long>()
        var endTimerCalls = 0
        val quietHoursToggles = mutableListOf<Boolean>()
        var exactAlarmRequests = 0
        var openSettingsCalls = 0
    }

    private val rec = Recorder()

    @Before
    fun resetSettings() {
        // SharedPreferences survive between tests in one class run.
        settings = ShhhSettings(context)
        settings.quietHoursEnabled = false
        settings.quietStartMinutes = 23 * 60
        settings.quietEndMinutes = 7 * 60
        settings.quietDays = DayOfWeek.entries.toSet()
        // Drain anything a previous test launched.
        while (shadowOf(context as Application).nextStartedActivity != null) {
            // no-op
        }
    }

    @Composable
    private fun Screen(
        quiet: Boolean = false,
        hasDndAccess: Boolean = true,
        canScheduleExact: Boolean = true,
        timerEndMillis: Long = 0L,
        settingsRevision: Int = 0
    ) {
        HomeScreen(
            quiet = quiet,
            hasDndAccess = hasDndAccess,
            canScheduleExact = canScheduleExact,
            timerEndMillis = timerEndMillis,
            settings = settings,
            settingsRevision = settingsRevision,
            onToggle = { rec.toggles++ },
            onHushFor = { rec.hushedFor += it },
            onEndTimer = { rec.endTimerCalls++ },
            onQuietHoursToggled = { rec.quietHoursToggles += it },
            onRequestExactAlarmAccess = { rec.exactAlarmRequests++ },
            onOpenSettings = { rec.openSettingsCalls++ }
        )
    }

    private fun str(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    // ---------------------------------------------------------------- toggle

    @Test
    fun `toggle button triggers onToggle`() {
        composeTestRule.setContent { Screen(quiet = false) }

        composeTestRule.onNodeWithContentDescription(str(R.string.tile_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, rec.toggles)
    }

    @Test
    fun `shows the sound-on status when not quiet`() {
        composeTestRule.setContent { Screen(quiet = false) }

        composeTestRule.onNodeWithText(str(R.string.status_quiet_off)).assertIsDisplayed()
    }

    @Test
    fun `shows the hushed status when quiet`() {
        composeTestRule.setContent { Screen(quiet = true) }

        composeTestRule.onNodeWithText(str(R.string.status_quiet_on)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.tile_content_description))
            .assertExists()
    }

    @Test
    fun `status text and glyph swap when quiet flips`() {
        var quiet by mutableStateOf(false)
        composeTestRule.setContent { Screen(quiet = quiet) }

        composeTestRule.onNodeWithText(str(R.string.status_quiet_off)).assertIsDisplayed()

        composeTestRule.runOnIdle { quiet = true }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.status_quiet_on)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.status_quiet_off)).assertDoesNotExist()

        composeTestRule.runOnIdle { quiet = false }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.status_quiet_off)).assertIsDisplayed()
    }

    @Test
    fun `disabled toggle does not fire onToggle`() {
        composeTestRule.setContent { Screen(hasDndAccess = false) }

        composeTestRule.onNodeWithContentDescription(str(R.string.tile_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, rec.toggles)
    }

    // ------------------------------------------------------------ permission

    @Test
    fun `shows PermissionCard when DND access is missing`() {
        composeTestRule.setContent { Screen(hasDndAccess = false) }

        composeTestRule.onNodeWithText(str(R.string.permission_title)).assertIsDisplayed()
    }

    @Test
    fun `hides PermissionCard when DND access is granted`() {
        composeTestRule.setContent { Screen(hasDndAccess = true) }

        composeTestRule.onNodeWithText(str(R.string.permission_title)).assertDoesNotExist()
    }

    @Test
    fun `grant access opens the notification policy settings screen`() {
        composeTestRule.setContent { Screen(hasDndAccess = false) }

        composeTestRule.onNodeWithText(str(R.string.permission_button))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        val started = shadowOf(context as Application).nextStartedActivity
        assertNotNull("Grant access must start an activity", started)
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, started!!.action)
    }

    // ------------------------------------------------------------- countdown

    @Test
    fun `hides the countdown card when no timer is running`() {
        composeTestRule.setContent { Screen(timerEndMillis = 0L) }

        composeTestRule.onNodeWithText(str(R.string.timer_end_now)).assertDoesNotExist()
    }

    @Test
    fun `shows the countdown card with the formatted end time`() {
        val end = Instant.parse("2026-03-04T18:45:00Z").toEpochMilli()
        val expected = str(
            R.string.timer_running_until,
            TimeFormat.epochMillis(context, end)
        )

        composeTestRule.setContent { Screen(timerEndMillis = end) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `end now invokes onEndTimer`() {
        composeTestRule.setContent {
            Screen(timerEndMillis = System.currentTimeMillis() + 600_000L)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.timer_end_now))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, rec.endTimerCalls)
    }

    // ---------------------------------------------------------- timer chips

    @Test
    fun `every duration chip reports its own number of minutes`() {
        composeTestRule.setContent { Screen() }

        val chips = listOf(
            R.string.timer_chip_15 to 15L,
            R.string.timer_chip_30 to 30L,
            R.string.timer_chip_60 to 60L,
            R.string.timer_chip_120 to 120L
        )
        chips.forEach { (res, _) ->
            composeTestRule.onNodeWithText(str(res)).performScrollTo().performClick()
            composeTestRule.waitForIdle()
        }

        assertEquals(chips.map { it.second }, rec.hushedFor)
    }

    @Test
    fun `chips are inert without DND access`() {
        composeTestRule.setContent { Screen(hasDndAccess = false) }

        composeTestRule.onNodeWithText(str(R.string.timer_chip_30))
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(rec.hushedFor.isEmpty())
    }

    // -------------------------------------------------------- quiet hours

    @Test
    fun `quiet hours switch reports being turned on`() {
        settings.quietHoursEnabled = false
        composeTestRule.setContent { Screen() }

        composeTestRule.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(true), rec.quietHoursToggles)
    }

    @Test
    fun `quiet hours switch reports being turned off`() {
        settings.quietHoursEnabled = true
        composeTestRule.setContent { Screen() }

        composeTestRule.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), rec.quietHoursToggles)
    }

    @Test
    fun `quiet hours card body opens settings`() {
        composeTestRule.setContent { Screen() }

        composeTestRule.onNodeWithText(str(R.string.quiet_hours_title))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, rec.openSettingsCalls)
    }

    @Test
    fun `quiet hours card shows the window and the day summary`() {
        settings.quietStartMinutes = 22 * 60 + 30
        settings.quietEndMinutes = 6 * 60 + 15
        settings.quietDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)

        composeTestRule.setContent { Screen(settingsRevision = 1) }

        val expected = "${clock(22 * 60 + 30)} – ${clock(6 * 60 + 15)} · " +
            daysSummary(context, settings, Locale.US)
        composeTestRule.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `settings icon triggers onOpenSettings`() {
        composeTestRule.setContent { Screen() }

        composeTestRule.onNodeWithContentDescription(str(R.string.settings_title)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, rec.openSettingsCalls)
    }

    // ------------------------------------------------------ exact alarm gate

    @Test
    fun `a timer chip opens the exact alarm dialog when exact alarms are blocked`() {
        composeTestRule.setContent { Screen(canScheduleExact = false) }

        composeTestRule.onNodeWithText(str(R.string.timer_chip_15))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue("onHushFor must not fire without exact alarms", rec.hushedFor.isEmpty())
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertIsDisplayed()
    }

    @Test
    fun `the quiet hours switch opens the exact alarm dialog when exact alarms are blocked`() {
        settings.quietHoursEnabled = false
        composeTestRule.setContent { Screen(canScheduleExact = false) }

        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "onQuietHoursToggled must not fire without exact alarms",
            rec.quietHoursToggles.isEmpty()
        )
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertIsDisplayed()
    }

    @Test
    fun `turning quiet hours OFF never needs exact alarm access`() {
        settings.quietHoursEnabled = true
        composeTestRule.setContent { Screen(canScheduleExact = false) }

        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), rec.quietHoursToggles)
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertDoesNotExist()
    }

    @Test
    fun `granting from the dialog requests access and dismisses it`() {
        composeTestRule.setContent { Screen(canScheduleExact = false) }

        composeTestRule.onNodeWithText(str(R.string.timer_chip_15))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.exact_alarm_grant)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, rec.exactAlarmRequests)
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertDoesNotExist()
    }

    @Test
    fun `cancelling the dialog dismisses it without requesting access`() {
        composeTestRule.setContent { Screen(canScheduleExact = false) }

        composeTestRule.onNodeWithText(str(R.string.timer_chip_15))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, rec.exactAlarmRequests)
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertDoesNotExist()
    }

    @Test
    fun `ExactAlarmDialog wires both buttons`() {
        var granted = 0
        var dismissed = 0
        composeTestRule.setContent {
            ExactAlarmDialog(onGrant = { granted++ }, onDismiss = { dismissed++ })
        }

        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_body)).assertIsDisplayed()

        composeTestRule.onNodeWithText(str(android.R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, granted)
        assertEquals(1, dismissed)

        composeTestRule.onNodeWithText(str(R.string.exact_alarm_grant)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, granted)
        assertEquals(1, dismissed)
    }

    // ------------------------------------------------------- recomposition

    @Test
    fun `the toggle only fires on release, not while held down`() {
        composeTestRule.setContent { Screen() }

        val toggle = composeTestRule
            .onNodeWithContentDescription(str(R.string.tile_content_description))

        toggle.performTouchInput { down(center) }
        // The toggle sits inside a vertical scroller, so Compose delays the
        // press indication until it is sure the gesture is not a scroll.
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()
        assertEquals("holding must not toggle", 0, rec.toggles)

        toggle.performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertEquals(1, rec.toggles)
    }

    @Test
    fun `chips follow DND access as it changes`() {
        var hasDnd by mutableStateOf(false)
        composeTestRule.setContent { Screen(hasDndAccess = hasDnd) }

        composeTestRule.onNodeWithText(str(R.string.timer_chip_30))
            .performScrollTo()
            .assertIsNotEnabled()

        composeTestRule.runOnIdle { hasDnd = true }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.timer_chip_30))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(30L), rec.hushedFor)
    }

    @Test
    fun `countdown and dialog survive an unrelated recomposition`() {
        var quiet by mutableStateOf(false)
        val end = System.currentTimeMillis() + 900_000L
        composeTestRule.setContent {
            Screen(quiet = quiet, canScheduleExact = false, timerEndMillis = end)
        }

        composeTestRule.onNodeWithText(str(R.string.timer_chip_60))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertIsDisplayed()

        // Flip a piece of state the dialog and the countdown know nothing about.
        composeTestRule.runOnIdle { quiet = true }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(str(R.string.status_quiet_on)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.exact_alarm_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.timer_end_now)).assertExists()

        composeTestRule.onNodeWithText(str(R.string.exact_alarm_grant)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, rec.exactAlarmRequests)
        assertTrue(rec.hushedFor.isEmpty())
    }


    @Test
    fun `screen is skipped when nothing it depends on changes`() {
        var tick by mutableStateOf(0)
        composeTestRule.setContent {
            Column {
                Text("tick $tick", modifier = Modifier.testTag("tick"))
                Screen()
            }
        }

        composeTestRule.runOnIdle { tick = 1 }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { tick = 2 }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("tick").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.status_quiet_off)).assertIsDisplayed()
        assertEquals(0, rec.toggles)
    }

    // ------------------------------------------------ top-level formatters

    /** The pure formatter behind the composable; see `core.TimeFormatTest`. */
    private fun clock(minutesOfDay: Int) = TimeFormat.minutesOfDay(context, minutesOfDay)

    @Test
    fun `formatMinutes renders the phone's own clock face`() {
        val values = listOf(0, 7 * 60, 13 * 60 + 45, 24 * 60 - 1)
        val rendered = arrayOfNulls<String>(values.size)

        composeTestRule.setContent {
            values.forEachIndexed { i, minutes -> rendered[i] = formatMinutes(minutes) }
        }
        composeTestRule.waitForIdle()

        assertEquals(values.map { clock(it) }, rendered.toList())
    }

    @Test
    fun `daysSummary collapses a full week to Every day`() {
        settings.quietDays = DayOfWeek.entries.toSet()
        assertEquals(
            str(R.string.quiet_days_every_day),
            daysSummary(context, settings, Locale.US)
        )
    }

    @Test
    fun `daysSummary lists short day names in week order`() {
        settings.quietDays = setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)
        assertEquals("Mon, Wed, Sun", daysSummary(context, settings, Locale.US))
    }

    @Test
    fun `daysSummary honours the locale it is given`() {
        settings.quietDays = setOf(DayOfWeek.MONDAY)
        val french = daysSummary(context, settings, Locale.FRENCH)
        assertEquals(
            DayOfWeek.MONDAY.getDisplayName(java.time.format.TextStyle.SHORT, Locale.FRENCH),
            french
        )
        assertNotEquals(daysSummary(context, settings, Locale.US), french)
    }

    @Test
    fun `daysSummary reports an empty selection`() {
        settings.quietDays = emptySet()
        assertEquals(
            str(R.string.quiet_days_none),
            daysSummary(context, settings, Locale.US)
        )
    }

    @Test
    fun `daysSummary of six days is not Every day`() {
        settings.quietDays = DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY
        val summary = daysSummary(context, settings, Locale.US)
        assertEquals("Mon, Tue, Wed, Thu, Fri, Sat", summary)
        assertNotEquals(str(R.string.quiet_days_every_day), summary)
    }
}
