package io.github.shhhapp.shhh.widget

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.glance.AndroidResourceImageProvider
import androidx.glance.EmittableImage
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.LocalContext
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.GlanceNodeMatcher
import androidx.glance.testing.unit.MappedNode
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
 * The transparent widget is the card widget with the surface paint removed:
 * same live state, same tap routing, no background behind the glyph. These
 * tests pin the difference (no background modifier anywhere in the tree, a
 * primary-tinted hushed state) and re-pin the shared behavior on this variant.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShhhTransparentWidgetTest {

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
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        ringVolume = 3
    }

    private var ringVolume: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_RING, value, 0)

    private fun hasImageResource(resId: Int) =
        GlanceNodeMatcher<MappedNode>("image showing resource $resId") { node ->
            val emittable = node.value.emittable
            emittable is EmittableImage &&
                (emittable.provider as? AndroidResourceImageProvider)?.resId == resId
        }

    /**
     * Matches any node carrying a background modifier. BackgroundModifier is
     * restricted API, so this goes through each element's stable toString.
     */
    private fun hasBackground() =
        GlanceNodeMatcher<MappedNode>("node with a background modifier") { node ->
            node.value.emittable.modifier.foldIn(false) { found, element ->
                found || element.toString().contains("BackgroundModifier")
            }
        }

    /** Composes the TRANSPARENT content from the phone's live state. */
    private fun androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.provideFromLiveState() {
        WidgetUiState.refreshFrom(context)
        provideComposable {
            CompositionLocalProvider(LocalContext provides context) {
                WidgetContent(
                    quiet = WidgetUiState.quiet,
                    canChangeSound = WidgetUiState.canChangeSound,
                    transparent = true
                )
            }
        }
    }

    @Test
    fun `the transparent widget paints no background anywhere`() =
        runGlanceAppWidgetUnitTest {
            provideFromLiveState()

            onNode(hasBackground()).assertDoesNotExist()
        }

    @Test
    fun `the transparent widget stays background-free while hushed`() =
        runGlanceAppWidgetUnitTest {
            ringVolume = 0

            provideFromLiveState()

            onNode(hasBackground()).assertDoesNotExist()
        }

    @Test
    fun `the card widget keeps its background — the styles must not drift together`() =
        runGlanceAppWidgetUnitTest {
            WidgetUiState.refreshFrom(context)
            provideComposable {
                CompositionLocalProvider(LocalContext provides context) {
                    WidgetContent(
                        quiet = WidgetUiState.quiet,
                        canChangeSound = WidgetUiState.canChangeSound,
                        transparent = false
                    )
                }
            }

            onNode(hasBackground()).assertExists()
        }

    @Test
    fun `a loud phone shows the idle label and the speaker glyph`() =
        runGlanceAppWidgetUnitTest {
            provideFromLiveState()

            onNode(hasTextEqualTo("Shhh")).assertExists()
            onNode(hasTextEqualTo("Hushed")).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.ic_volume_up)).assertExists()
        }

    @Test
    fun `a hushed phone shows the hushed label and the vibration glyph`() =
        runGlanceAppWidgetUnitTest {
            ringVolume = 0

            provideFromLiveState()

            onNode(hasTextEqualTo("Hushed")).assertExists()
            onNode(hasTextEqualTo("Shhh")).assertDoesNotExist()
            onNode(hasImageResource(R.drawable.ic_vibration)).assertExists()
        }

    @Test
    fun `the transparent widget taps through to the trampoline activity`() =
        runGlanceAppWidgetUnitTest {
            provideFromLiveState()

            onNode(hasStartActivityClickAction<ToggleActivity>()).assertExists()
        }

    @Test
    fun `with a zen running and no DND access the tap leads to the app`() =
        runGlanceAppWidgetUnitTest {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
            shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)

            provideFromLiveState()

            onNode(hasStartActivityClickAction<MainActivity>()).assertExists()
            onNode(hasStartActivityClickAction<ToggleActivity>()).assertDoesNotExist()
        }

    @OptIn(ExperimentalGlanceApi::class)
    @Test
    fun `provideGlance composes the transparent widget into remote views`() = runBlocking {
        val remoteViews = withTimeout(30_000) { ShhhTransparentWidget().compose(context) }

        assertNotNull(remoteViews)
    }

    @Test
    fun `the receiver publishes the transparent widget`() {
        assertTrue(ShhhTransparentWidgetReceiver().glanceAppWidget is ShhhTransparentWidget)
    }
}
