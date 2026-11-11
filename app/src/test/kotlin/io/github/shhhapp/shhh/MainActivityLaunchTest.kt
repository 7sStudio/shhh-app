package io.github.shhhapp.shhh

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Launches the real [MainActivity] (edge-to-edge, themed) instead of only the
 * `ShhhApp` composable, so the activity's own wiring is covered end to end.
 *
 * The phone state is prepared in an initialiser rather than in `@Before`: the
 * activity rule starts the activity — and therefore the first composition —
 * before `@Before` runs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityLaunchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        ShhhSettings(context).apply {
            timerEndMillis = 0L
            quietHoursEnabled = false
            previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
            previousRingVolume = ShhhSettings.NO_SAVED_VOLUME
            liveCountdownEnabled = false
        }
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 3, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `launching the activity renders the themed home screen`() {
        assertFalse(composeTestRule.activity.isFinishing)
        composeTestRule.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.app_tagline)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.status_quiet_off))
            .assertIsDisplayed()
    }

    @Test
    fun `the launched activity hosts a live toggle`() {
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.tile_content_description))
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        composeTestRule
            .onNodeWithText(context.getString(R.string.status_quiet_on))
            .assertIsDisplayed()
    }
}

/**
 * The launch [ToggleActivity] makes after refusing a timed hush that has no
 * exact alarm to end it: the app must open on the existing "Alarms &
 * reminders" explanation, whether it starts fresh or is already running.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainActivityExactAlarmExplanationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    init {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        ShhhSettings(context).apply {
            timerEndMillis = 0L
            quietHoursEnabled = false
            autoUpdateCheckEnabled = false
        }
    }

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val title get() = context.getString(R.string.exact_alarm_title)

    private fun explainIntent(explain: Boolean = true) =
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EXPLAIN_EXACT_ALARMS, explain)

    @Test
    fun `a fresh launch asking for the explanation shows it`() {
        ActivityScenario.launch<MainActivity>(explainIntent()).use {
            composeTestRule.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test
    fun `a relaunch from Recents does not replay the explanation`() {
        // Recents restarts a task with the intent that first started it.
        val replayed = explainIntent()
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        ActivityScenario.launch<MainActivity>(replayed).use {
            composeTestRule.onNodeWithText(context.getString(R.string.app_tagline))
                .assertIsDisplayed()
            composeTestRule.onNodeWithText(title).assertDoesNotExist()
        }
    }

    @Test
    fun `long-pressing the tile is routed to the app`() {
        val resolved = context.packageManager.queryIntentActivities(
            Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES)
                .setPackage(context.packageName),
            0
        )

        assertEquals(
            listOf(MainActivity::class.java.name),
            resolved.map { it.activityInfo.name }
        )
    }

    @Test
    fun `a plain launch shows no explanation`() {
        ActivityScenario.launch<MainActivity>(explainIntent(explain = false)).use {
            composeTestRule.onNodeWithText(context.getString(R.string.app_tagline))
                .assertIsDisplayed()
            composeTestRule.onNodeWithText(title).assertDoesNotExist()
        }
    }

    @Test
    fun `the explanation survives a rotation and a dismissal does too`() {
        ActivityScenario.launch<MainActivity>(explainIntent()).use { scenario ->
            scenario.recreate()
            composeTestRule.onNodeWithText(title).assertIsDisplayed()

            composeTestRule.onNodeWithText(context.getString(android.R.string.cancel))
                .performClick()
            composeTestRule.waitForIdle()
            // The launch intent still carries the extra; the saved state wins.
            scenario.recreate()

            composeTestRule.onNodeWithText(title).assertDoesNotExist()
        }
    }

    @Test
    fun `a request delivered to the running app shows the explanation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        composeTestRule.onNodeWithText(title).assertDoesNotExist()

        controller.newIntent(explainIntent(explain = false))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(title).assertDoesNotExist()

        controller.newIntent(explainIntent())
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        assertEquals(
            "the delivered intent becomes the activity's own",
            true,
            controller.get().intent.getBooleanExtra(MainActivity.EXTRA_EXPLAIN_EXACT_ALARMS, false)
        )
        controller.pause().stop().destroy()
    }
}
