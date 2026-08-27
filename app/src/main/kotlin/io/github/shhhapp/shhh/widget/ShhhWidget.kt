package io.github.shhhapp.shhh.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.ColorFilter
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.QuietModeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home screen widget: a single rounded toggle that mirrors the phone's actual
 * quiet state. Tapping flips it; if Do Not Disturb access is missing, tapping
 * opens the app so the user can grant it.
 */
class ShhhWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }

    companion object {
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Fire-and-forget refresh of every placed widget (safe from any surface). */
        fun requestRefresh(context: Context) {
            val appContext = context.applicationContext
            refreshScope.launch { ShhhWidget().updateAll(appContext) }
        }
    }
}

class ShhhWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShhhWidget()
}

@Composable
internal fun WidgetContent() {
    val context = LocalContext.current
    val controller = QuietModeController(context)
    val quiet = controller.isQuiet

    val background = if (quiet) GlanceTheme.colors.primary else GlanceTheme.colors.widgetBackground
    val content = if (quiet) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurface

    // Taps go through an invisible foreground trampoline: Android 16+ audio
    // hardening drops ringer/volume changes made from background callbacks.
    // Without DND access the tap opens the app to request it instead.
    val clickAction = if (controller.hasDndAccess) {
        actionStartActivity<ToggleActivity>()
    } else {
        actionStartActivity<MainActivity>()
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(28.dp)
            .clickable(clickAction),
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

