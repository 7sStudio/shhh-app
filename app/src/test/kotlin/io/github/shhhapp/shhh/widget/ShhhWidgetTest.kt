package io.github.shhhapp.shhh.widget

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.glance.AndroidResourceImageProvider
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.EmittableImage
import androidx.glance.LocalContext
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.GlanceNodeMatcher
import androidx.glance.testing.unit.MappedNode
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The home-screen widget renders from the phone's live ringer state, so these
 * tests set the real ringer/DND state and assert the composed widget tree.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShhhWidgetTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    private fun hasImageResource(resId: Int) =
        GlanceNodeMatcher<MappedNode>("image showing resource $resId") { node ->
            val emittable = node.value.emittable
            emittable is EmittableImage &&
                (emittable.provider as? AndroidResourceImageProvider)?.resId == resId
        }

    /**
     * Seeds [WidgetUiState] from the phone's live state — the production path —
     * and composes from it, mirroring provideGlance/requestRefresh.
     */
    private fun androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.provideFromLiveState() {
        WidgetUiState.refreshFrom(context)
        provideComposable {
            CompositionLocalProvider(LocalContext provides context) {
                WidgetContent(
                    quiet = WidgetUiState.quiet,
                    hasDndAccess = WidgetUiState.hasDndAccess
                )
            }
        }
    }

    @Test
    fun `a loud phone shows the idle label and the speaker glyph`() =
        runGlanceAppWidgetUnitTest {
            provideFromLiveState()

            onNode(hasTextEqualTo("Shhh")).assertExists()
            onNode(hasTextEqualTo("Hushed")).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.ic_volume_up)).assertExists()
            onNode(hasContentDescriptionEqualTo("Toggle quiet mode")).assertExists()
        }

    @Test
    fun `a hushed phone shows the hushed label and the vibration glyph`() =
        runGlanceAppWidgetUnitTest {
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

            provideFromLiveState()

            onNode(hasTextEqualTo("Hushed")).assertExists()
            onNode(hasTextEqualTo("Shhh")).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.ic_vibration)).assertExists()
        }

    @Test
    fun `with DND access the widget taps through to the trampoline activity`() =
        runGlanceAppWidgetUnitTest {
            provideFromLiveState()

            onNode(hasStartActivityClickAction<ToggleActivity>()).assertExists()
        }

    @Test
    fun `without DND access the widget taps through to the app`() =
        runGlanceAppWidgetUnitTest {
            shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)

            provideFromLiveState()

            onNode(hasStartActivityClickAction<MainActivity>()).assertExists()
            onNode(hasStartActivityClickAction<ToggleActivity>()).assertDoesNotExist()
        }

    @OptIn(ExperimentalGlanceApi::class)
    @Test
    fun `provideGlance composes the widget into remote views`() = runBlocking {
        val remoteViews = withTimeout(30_000) { ShhhWidget().compose(context) }

        assertNotNull(remoteViews)
    }

    @Test
    fun `the receiver publishes the Shhh widget`() {
        assertTrue(ShhhWidgetReceiver().glanceAppWidget is ShhhWidget)
    }

    @Test
    fun `requesting a refresh with no placed widgets fails silently`() {
        val failures = mutableListOf<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> failures += error }
        try {
            ShhhWidget.requestRefresh(context)
            // The refresh is fire-and-forget on a background dispatcher.
            val deadline = System.currentTimeMillis() + 1_000
            while (System.currentTimeMillis() < deadline && failures.isEmpty()) {
                Thread.sleep(50)
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        assertTrue("refresh crashed: ${failures.firstOrNull()}", failures.isEmpty())
    }
}
