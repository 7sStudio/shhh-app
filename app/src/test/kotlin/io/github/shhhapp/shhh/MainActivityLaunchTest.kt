package io.github.shhhapp.shhh

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
            hushRinger = ShhhSettings.HushRinger.VIBRATE
            liveCountdownEnabled = false
        }
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
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

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        composeTestRule
            .onNodeWithText(context.getString(R.string.status_quiet_on))
            .assertIsDisplayed()
    }
}
