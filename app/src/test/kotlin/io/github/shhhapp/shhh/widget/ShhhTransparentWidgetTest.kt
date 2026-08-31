package io.github.shhhapp.shhh.widget

import android.app.NotificationManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.glance.AndroidResourceImageProvider
import androidx.glance.EmittableImage
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.GlanceNodeMatcher
import androidx.glance.testing.unit.MappedNode
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.glance.text.EmittableText
import androidx.glance.unit.ColorProvider
import androidx.glance.unit.FixedColorProvider
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
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWallpaperManager

/**
 * Robolectric's ShadowWallpaperManager has no getWallpaperColors, so the real
 * method would run and die on a missing wallpaper service. This shadow makes
 * the wallpaper's published colors — and a broken service — scriptable.
 */
@Implements(WallpaperManager::class)
class ShadowWallpaperColorsManager : ShadowWallpaperManager() {
    companion object {
        var colors: WallpaperColors? = null
        var failure: RuntimeException? = null

        /** Every listener registered since JVM start; never reset — tests fire their own. */
        val listeners = mutableListOf<WallpaperManager.OnColorsChangedListener>()
        var failOnAddListener = false

        fun reset() {
            colors = null
            failure = null
            failOnAddListener = false
        }
    }

    @Implementation
    protected fun getWallpaperColors(which: Int): WallpaperColors? {
        failure?.let { throw it }
        return colors
    }

    @Implementation
    protected fun addOnColorsChangedListener(
        listener: WallpaperManager.OnColorsChangedListener,
        handler: android.os.Handler
    ) {
        if (failOnAddListener) throw RuntimeException("wallpaper service went away")
        listeners += listener
    }
}

/**
 * The transparent widget is the card widget with the surface paint removed:
 * same live state, same tap routing, no background behind the glyph. These
 * tests pin the difference (no background modifier anywhere in the tree, a
 * primary-tinted hushed state) and re-pin the shared behavior on this variant.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [ShadowWallpaperColorsManager::class])
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
        ShadowWallpaperColorsManager.reset()
    }

    /** Publishes wallpaper colors carrying exactly the given hint bits. */
    private fun setWallpaper(lightEnoughForDarkText: Boolean) {
        ShadowWallpaperColorsManager.colors = WallpaperColors(
            android.graphics.Color.valueOf(
                if (lightEnoughForDarkText) AndroidColor.WHITE else AndroidColor.BLACK
            ),
            null,
            null,
            if (lightEnoughForDarkText) WallpaperColors.HINT_SUPPORTS_DARK_TEXT else 0
        )
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

    /** The theme fallbacks, captured from inside the composition where they live. */
    private class ThemeColors {
        var onSurface: ColorProvider? = null
        var primary: ColorProvider? = null
    }

    /**
     * Composes the TRANSPARENT content from the phone's live state (the card
     * control passes transparent = false), capturing the theme colors the
     * wallpaper-less fallbacks resolve to.
     */
    private fun androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.provideFromLiveState(
        transparent: Boolean = true
    ): ThemeColors {
        val theme = ThemeColors()
        WidgetUiState.refreshFrom(context)
        provideComposable {
            CompositionLocalProvider(LocalContext provides context) {
                theme.onSurface = GlanceTheme.colors.onSurface
                theme.primary = GlanceTheme.colors.primary
                WidgetContent(
                    quiet = WidgetUiState.quiet,
                    canChangeSound = WidgetUiState.canChangeSound,
                    transparent = transparent,
                    wallpaperPrefersDarkText = WidgetUiState.wallpaperPrefersDarkText
                )
            }
        }
        return theme
    }

    /**
     * The expectation is a lambda because the theme colors it compares against
     * are only captured once the composition runs — which the test framework
     * defers until the first assertion evaluates.
     */
    private fun hasTextColor(expected: () -> ColorProvider?) =
        GlanceNodeMatcher<MappedNode>("text colored by expected provider") { node ->
            val emittable = node.value.emittable
            emittable is EmittableText && emittable.style?.color == expected()
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
            provideFromLiveState(transparent = false)

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

    // ---- wallpaper-aware content color ----

    @Test
    fun `a light wallpaper paints the idle content ink`() =
        runGlanceAppWidgetUnitTest {
            setWallpaper(lightEnoughForDarkText = true)

            provideFromLiveState()

            onNode(hasTextColor { FixedColorProvider(WALLPAPER_INK) }).assertExists()
        }

    @Test
    fun `a dark wallpaper paints the idle content white`() =
        runGlanceAppWidgetUnitTest {
            setWallpaper(lightEnoughForDarkText = false)

            provideFromLiveState()

            onNode(hasTextColor { FixedColorProvider(Color.White) }).assertExists()
        }

    @Test
    fun `a wallpaper with no color signal falls back to the theme`() =
        runGlanceAppWidgetUnitTest {
            // colors stays null from reset()
            val theme = provideFromLiveState()

            onNode(hasTextColor { theme.onSurface }).assertExists()
        }

    @Test
    fun `a broken wallpaper service falls back to the theme instead of crashing`() =
        runGlanceAppWidgetUnitTest {
            ShadowWallpaperColorsManager.failure =
                RuntimeException("wallpaper service went away")

            val theme = provideFromLiveState()

            onNode(hasTextColor { theme.onSurface }).assertExists()
        }

    @Test
    fun `hushed on a light wallpaper uses the dark dynamic accent`() =
        runGlanceAppWidgetUnitTest {
            setWallpaper(lightEnoughForDarkText = true)
            ringVolume = 0

            provideFromLiveState()

            onNode(hasTextColor { FixedColorProvider(Color(context.getColor(android.R.color.system_accent1_600))) })
                .assertExists()
        }

    @Test
    fun `hushed on a dark wallpaper uses the light dynamic accent`() =
        runGlanceAppWidgetUnitTest {
            setWallpaper(lightEnoughForDarkText = false)
            ringVolume = 0

            provideFromLiveState()

            onNode(hasTextColor { FixedColorProvider(Color(context.getColor(android.R.color.system_accent1_200))) })
                .assertExists()
        }

    @Test
    fun `hushed with no wallpaper signal keeps the theme primary`() =
        runGlanceAppWidgetUnitTest {
            ringVolume = 0

            val theme = provideFromLiveState()

            onNode(hasTextColor { theme.primary }).assertExists()
        }

    @Test
    fun `the card widget ignores the wallpaper entirely`() =
        runGlanceAppWidgetUnitTest {
            // A light wallpaper must not restyle content that sits on a card.
            setWallpaper(lightEnoughForDarkText = true)

            val theme = provideFromLiveState(transparent = false)

            onNode(hasTextColor { theme.onSurface }).assertExists()
            onNode(hasTextColor { FixedColorProvider(WALLPAPER_INK) }).assertDoesNotExist()
        }

    @Test
    fun `an expected-state publish never touches the wallpaper hint`() {
        // The optimistic flip is a display guess about the hush flag only; a
        // stale wallpaper read must not ride along with it.
        setWallpaper(lightEnoughForDarkText = true)
        WidgetUiState.refreshFrom(context)
        assertEquals(true, WidgetUiState.wallpaperPrefersDarkText)

        WidgetUiState.showExpected(expectedQuiet = true)

        assertEquals(true, WidgetUiState.wallpaperPrefersDarkText)
    }

    @Test
    fun `refreshFrom re-reads the wallpaper each time`() {
        setWallpaper(lightEnoughForDarkText = true)
        WidgetUiState.refreshFrom(context)
        assertEquals(true, WidgetUiState.wallpaperPrefersDarkText)

        setWallpaper(lightEnoughForDarkText = false)
        WidgetUiState.refreshFrom(context)
        assertEquals(false, WidgetUiState.wallpaperPrefersDarkText)
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
