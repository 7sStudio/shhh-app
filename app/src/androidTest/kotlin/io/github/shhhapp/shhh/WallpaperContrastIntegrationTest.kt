package io.github.shhhapp.shhh

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shhhapp.shhh.widget.WidgetUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transparent widget's readability rests on one platform promise: setting
 * a wallpaper makes the wallpaper service publish [android.app.WallpaperColors]
 * whose dark-text hint matches the wallpaper's brightness, and refreshing the
 * widget state picks that hint up. These tests walk that real pipeline on the
 * device — set an actual wallpaper, poll the actual service — because the unit
 * tests can only script the answer, not prove Android gives it.
 *
 * Deliberately NO wallpaper restore afterwards: the hint computation is
 * asynchronous in the wallpaper service, so a teardown restore would race the
 * next test class. The emulator is simply left on the last test wallpaper.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperContrastIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // The wallpaper service checks the CALLING PACKAGE for SET_WALLPAPER,
        // so a permission on the test APK (even sharing the app's uid) is not
        // enough — borrow the shell's identity, which holds it.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .adoptShellPermissionIdentity(android.Manifest.permission.SET_WALLPAPER)
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .dropShellPermissionIdentity()
    }

    /** Sets a solid wallpaper and waits for the service to publish its colors. */
    private fun setWallpaperAndAwaitHint(color: Int, expectedDarkText: Boolean) {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        WallpaperManager.getInstance(context).setBitmap(bitmap)

        // Color extraction runs asynchronously in the wallpaper service.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            WidgetUiState.refreshFrom(context)
            if (WidgetUiState.wallpaperPrefersDarkText == expectedDarkText) break
            Thread.sleep(250)
        }
        assertEquals(
            "the wallpaper service never published the expected dark-text hint",
            expectedDarkText,
            WidgetUiState.wallpaperPrefersDarkText
        )
    }

    @Test
    fun aWhiteWallpaperYieldsTheDarkTextHint() {
        setWallpaperAndAwaitHint(Color.WHITE, expectedDarkText = true)
    }

    @Test
    fun aBlackWallpaperWithholdsTheDarkTextHint() {
        // Run a light wallpaper through first so the flip is observable in
        // both directions within one test.
        setWallpaperAndAwaitHint(Color.WHITE, expectedDarkText = true)
        setWallpaperAndAwaitHint(Color.BLACK, expectedDarkText = false)
    }

    @Test
    fun aWallpaperChangeRecolorsWithNoTapAtAll() {
        // Baseline established the manual way…
        setWallpaperAndAwaitHint(Color.WHITE, expectedDarkText = true)

        // …then the flip must arrive on its own: this process's
        // ShhhApplication armed the real OnColorsChangedListener, and only it
        // may call refreshFrom now.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        WallpaperManager.getInstance(context).setBitmap(bitmap)

        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline &&
            WidgetUiState.wallpaperPrefersDarkText != false
        ) {
            Thread.sleep(250)
        }
        assertEquals(
            "the armed wallpaper listener never republished the widget state",
            false,
            WidgetUiState.wallpaperPrefersDarkText
        )
    }
}
