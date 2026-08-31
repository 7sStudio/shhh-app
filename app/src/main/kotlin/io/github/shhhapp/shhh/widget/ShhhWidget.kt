package io.github.shhhapp.shhh.widget

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.ColorFilter
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.QuietModeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Home screen widgets: a rounded toggle that mirrors the phone's actual quiet
 * state, in two styles — the classic colored card ([ShhhWidget]) and a
 * background-free variant ([ShhhTransparentWidget]). Tapping flips it; if Do
 * Not Disturb access is missing, tapping opens the app so the user can grant it.
 */
/**
 * Snapshot-backed mirror of the phone's sound state, the only thing the
 * widget composition reads. Glance keeps a widget's composition alive between
 * updates and only re-executes composables whose OBSERVED state changed;
 * reading AudioManager directly inside the composition is invisible to the
 * snapshot system, so recomposition skipped the body and every update while a
 * session was alive re-published the old UI — the widget froze on a stale
 * state until the session expired (seen as "stuck on Hushed" after un-hush).
 */
internal object WidgetUiState {
    var quiet by mutableStateOf(false)
        private set
    var canChangeSound by mutableStateOf(true)
        private set

    /**
     * Whether the home wallpaper is light enough for dark content — the same
     * [WallpaperColors.HINT_SUPPORTS_DARK_TEXT] signal SystemUI reads to color
     * the lockscreen clock. Null when the wallpaper engine publishes no colors
     * (some live wallpapers) or the read fails; the theme decides then. Only
     * the transparent widget consults this — the card carries its own surface.
     */
    var wallpaperPrefersDarkText by mutableStateOf<Boolean?>(null)
        private set

    /** Re-reads reality; the composition recomposes when a value changes. */
    fun refreshFrom(context: Context) {
        val controller = QuietModeController(context)
        quiet = controller.isQuiet
        canChangeSound = controller.canChangeSound
        wallpaperPrefersDarkText = readWallpaperDarkTextHint(context)
    }

    /**
     * Shows the state a transition is EXPECTED to end in, before it has run.
     * Only the hush flag is guessed; a transition never changes what
     * [canChangeSound] reads. Always followed by a [refreshFrom]-based publish
     * once the outcome is known — which confirms it, or snaps it back.
     */
    fun showExpected(expectedQuiet: Boolean) {
        quiet = expectedQuiet
    }
}

/**
 * Recolors the widgets the moment the wallpaper service finishes recomputing
 * a new wallpaper's colors. OnColorsChangedListener is the sanctioned signal
 * (ACTION_WALLPAPER_CHANGED is an implicit broadcast manifest receivers no
 * longer get — and it fires BEFORE color extraction, so it would re-read the
 * OLD hint). The listener lives exactly as long as this process does — every
 * process start re-arms it from [io.github.shhhapp.shhh.ShhhApplication] —
 * and after a process death the next publish covers the gap.
 */
fun registerWallpaperColorsListener(context: Context) {
    val appContext = context.applicationContext
    try {
        WallpaperManager.getInstance(appContext).addOnColorsChangedListener(
            { _, which ->
                // Only the home screen wallpaper sits behind widgets.
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    ShhhWidget.requestRefresh(appContext)
                }
            },
            Handler(Looper.getMainLooper())
        )
    } catch (_: Exception) {
        // No wallpaper service (or a broken one) must never block app start;
        // the widget then simply refreshes on its usual cadence.
    }
}

private fun readWallpaperDarkTextHint(context: Context): Boolean? = try {
    val colors = WallpaperManager.getInstance(context)
        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    if (colors == null) {
        null
    } else {
        colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    }
} catch (_: Exception) {
    // A misbehaving wallpaper service must never take the widget down.
    null
}

/**
 * Seeds [WidgetUiState] and composes the shared content. Both widget styles
 * render from the same state and behavior; only the surface paint differs.
 */
private suspend fun GlanceAppWidget.provideHushContent(context: Context, transparent: Boolean) {
    WidgetUiState.refreshFrom(context)
    provideContent {
        GlanceTheme {
            WidgetContent(
                quiet = WidgetUiState.quiet,
                canChangeSound = WidgetUiState.canChangeSound,
                transparent = transparent,
                wallpaperPrefersDarkText = WidgetUiState.wallpaperPrefersDarkText
            )
        }
    }
}

class ShhhWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideHushContent(context, transparent = false)

    companion object {
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Publishes hold this across their seed-and-update, so an optimistic
         * flip and the truth that follows it land in the order they were
         * asked for — updateAll suspends, and two bare launches on the same
         * dispatcher would be free to interleave around that.
         */
        private val publishLock = Mutex()

        /** Fire-and-forget refresh of every placed widget (safe from any surface). */
        fun requestRefresh(context: Context) =
            publish(context) { WidgetUiState.refreshFrom(it) }

        /**
         * Optimistic flip: shows the state the transition is expected to end
         * in, without waiting for the volume writes, the settle poll or the
         * timer bookkeeping. The refresh that every transition triggers
         * afterwards re-seeds from reality — confirming this, or snapping it
         * back when Android refused the change.
         */
        fun showExpected(context: Context, quiet: Boolean) =
            publish(context) { WidgetUiState.showExpected(quiet) }

        private fun publish(context: Context, seed: (Context) -> Unit) {
            val appContext = context.applicationContext
            refreshScope.launch {
                publishLock.withLock {
                    // Seed the observable state BEFORE updateAll so live
                    // sessions recompose against the new values.
                    seed(appContext)
                    ShhhWidget().updateAll(appContext)
                    ShhhTransparentWidget().updateAll(appContext)
                }
            }
        }
    }
}

/** The same toggle without the colored card behind it, for see-through setups. */
class ShhhTransparentWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) =
        provideHushContent(context, transparent = true)
}

class ShhhWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShhhWidget()
}

class ShhhTransparentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShhhTransparentWidget()
}

/** M3 ink for content sitting directly on a light wallpaper. */
internal val WALLPAPER_INK = Color(0xFF1C1B1F)

@Composable
internal fun WidgetContent(
    quiet: Boolean,
    canChangeSound: Boolean,
    transparent: Boolean,
    wallpaperPrefersDarkText: Boolean?
) {
    val context = LocalContext.current

    // The transparent style has no card to invert against, so its content is
    // colored for the wallpaper it actually sits on, not for the app theme:
    // near-black on a wallpaper that supports dark text, white otherwise —
    // white doubling as the safe guess when no hint exists but colors do.
    // The hushed accent keeps the Material You hue via the fixed dynamic
    // palette steps (not the theme's primary, which flips with dark mode and
    // would fight the wallpaper): a dark accent on light wallpapers, a light
    // one on dark. Only a wallpaper with no color signal at all falls back to
    // the theme.
    val content = if (!transparent) {
        if (quiet) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurface
    } else if (wallpaperPrefersDarkText == null) {
        if (quiet) GlanceTheme.colors.primary else GlanceTheme.colors.onSurface
    } else if (quiet) {
        // Resolved to a concrete color here rather than passed as a resource:
        // the resource-based ColorProvider is restricted Glance API, and the
        // system palette resolves identically in every process anyway. Known
        // edge: a wallpaper swap that changes hue but not brightness leaves
        // the hint value unchanged, so a hushed widget keeps the old hue's
        // accent until the next publish re-composes it.
        ColorProvider(
            Color(
                context.getColor(
                    if (wallpaperPrefersDarkText) {
                        android.R.color.system_accent1_600
                    } else {
                        android.R.color.system_accent1_200
                    }
                )
            )
        )
    } else {
        ColorProvider(if (wallpaperPrefersDarkText) WALLPAPER_INK else Color.White)
    }

    // Taps go through an invisible foreground trampoline: Android 16+ audio
    // hardening drops volume changes made from background callbacks. When a
    // zen mode is running without DND access every write would be refused, so
    // the tap opens the app to explain instead.
    val clickAction = if (canChangeSound) {
        actionStartActivity<ToggleActivity>()
    } else {
        actionStartActivity<MainActivity>()
    }

    var surface = GlanceModifier
        .fillMaxSize()
        .cornerRadius(28.dp)
        .clickable(clickAction)
    if (!transparent) {
        surface = surface.background(
            if (quiet) GlanceTheme.colors.primary else GlanceTheme.colors.widgetBackground
        )
    }

    Box(
        modifier = surface,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(
                    if (quiet) R.drawable.ic_vibration else R.drawable.ic_volume_up
                ),
                contentDescription = context.getString(R.string.tile_content_description),
                colorFilter = ColorFilter.tint(content),
                modifier = GlanceModifier.size(30.dp)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = context.getString(
                    if (quiet) R.string.widget_label_on else R.string.widget_label_off
                ),
                style = TextStyle(
                    color = content,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

