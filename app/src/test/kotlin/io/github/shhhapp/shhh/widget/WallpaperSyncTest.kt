package io.github.shhhapp.shhh.widget

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.ShhhApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The wallpaper listener is what makes the transparent widget recolor the
 * moment a wallpaper change lands instead of waiting for the next tap or
 * scheduled update. These tests drive the registered listener directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [ShadowWallpaperColorsManager::class])
class WallpaperSyncTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowWallpaperColorsManager.reset()
    }

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

    private fun registerAndGetListener(): WallpaperManager.OnColorsChangedListener {
        registerWallpaperColorsListener(context)
        return ShadowWallpaperColorsManager.listeners.last()
    }

    private fun awaitHint(expected: Boolean?) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            WidgetUiState.wallpaperPrefersDarkText != expected
        ) {
            Thread.sleep(20)
        }
        assertEquals(expected, WidgetUiState.wallpaperPrefersDarkText)
    }

    @Test
    fun `the application arms the listener at process start`() {
        // Robolectric built the real Application before setUp could reset
        // anything, so its registration is already on record.
        assertTrue(ApplicationProvider.getApplicationContext<Context>() is ShhhApplication)
        assertTrue(ShadowWallpaperColorsManager.listeners.isNotEmpty())
    }

    @Test
    fun `a home wallpaper change republishes the fresh hint`() {
        setWallpaper(lightEnoughForDarkText = false)
        WidgetUiState.refreshFrom(context)
        awaitHint(false)
        val listener = registerAndGetListener()

        setWallpaper(lightEnoughForDarkText = true)
        listener.onColorsChanged(
            ShadowWallpaperColorsManager.colors,
            WallpaperManager.FLAG_SYSTEM
        )

        awaitHint(true)
    }

    @Test
    fun `a lock-screen-only change is ignored — no widget sits on it`() {
        setWallpaper(lightEnoughForDarkText = true)
        WidgetUiState.refreshFrom(context)
        awaitHint(true)
        val listener = registerAndGetListener()

        setWallpaper(lightEnoughForDarkText = false)
        listener.onColorsChanged(
            ShadowWallpaperColorsManager.colors,
            WallpaperManager.FLAG_LOCK
        )

        // The refresh is asynchronous when it happens; give a wrong one time
        // to land before concluding it never will.
        Thread.sleep(500)
        assertEquals(true, WidgetUiState.wallpaperPrefersDarkText)
    }

    @Test
    fun `a change flagged for both screens still refreshes`() {
        setWallpaper(lightEnoughForDarkText = false)
        WidgetUiState.refreshFrom(context)
        awaitHint(false)
        val listener = registerAndGetListener()

        setWallpaper(lightEnoughForDarkText = true)
        listener.onColorsChanged(
            ShadowWallpaperColorsManager.colors,
            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        )

        awaitHint(true)
    }

    @Test
    fun `a broken wallpaper service does not block registration callers`() {
        ShadowWallpaperColorsManager.failOnAddListener = true
        val before = ShadowWallpaperColorsManager.listeners.size

        registerWallpaperColorsListener(context)

        assertEquals(before, ShadowWallpaperColorsManager.listeners.size)
    }
}
