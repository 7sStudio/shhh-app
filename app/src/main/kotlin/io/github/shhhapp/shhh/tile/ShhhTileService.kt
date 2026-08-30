package io.github.shhhapp.shhh.tile

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.schedule.HushService

/**
 * Quick Settings tile: one tap flips quiet mode.
 *
 * Taps never close the shade: the work goes to a momentary foreground
 * service, so the tile behaves like Wi-Fi or the torch rather than like a
 * shortcut that dismisses the panel.
 *
 * The tile is passive — [onStartListening] runs every time the shade opens,
 * so the displayed state always reflects the phone's actual volume.
 * While the shade stays open, ringer and Do Not Disturb changes made
 * elsewhere (volume keys, the DND tile, Bedtime mode) are picked up through
 * a broadcast receiver that lives only for the listening window.
 */
class ShhhTileService : TileService() {

    private var stateReceiver: BroadcastReceiver? = null

    override fun onStartListening() {
        if (stateReceiver == null) {
            stateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = refreshTile()
            }
            registerReceiver(
                stateReceiver,
                IntentFilter().apply {
                    addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                },
                Context.RECEIVER_NOT_EXPORTED
            )
        }
        refreshTile()
    }

    override fun onStopListening() {
        unregisterStateReceiver()
    }

    override fun onDestroy() {
        // The system does not guarantee onStopListening before an unbind kill.
        unregisterStateReceiver()
        super.onDestroy()
    }

    private fun unregisterStateReceiver() {
        stateReceiver?.let { unregisterReceiver(it) }
        stateReceiver = null
    }

    override fun onClick() {
        val manager = HushManager(this)

        // The one case worth leaving the shade for: while a zen mode runs
        // without Do Not Disturb access Android refuses every write, and the
        // app is where that is explained and granted. Opening an app is what a
        // tile is allowed to collapse the shade for.
        if (!manager.canChangeSound) {
            launchActivity(Intent(this, MainActivity::class.java))
            return
        }

        // Everything else stays in the shade. A toggle tile must behave like
        // Wi-Fi or the torch: flip, and leave the panel exactly where the user
        // left it. That rules out the obvious route, because a tile can only
        // start an activity via startActivityAndCollapse, which closes the
        // shade by definition.
        //
        // Writing the volume here instead does not work either: a tile's
        // process is background for audio purposes and Android 16+ audio
        // hardening silently drops the write (measured on Android 17 — the
        // tile's own toggle never landed, which is why this used to bounce
        // through the invisible ToggleActivity and collapse the shade).
        //
        // The momentary foreground service is the sanctioned path: it is
        // allowed to change volume, and it has no UI, so nothing touches the
        // shade. It refreshes this tile itself once the change has landed.
        try {
            startForegroundService(HushService.intent(this, HushService.ACTION_TOGGLE))
        } catch (_: Exception) {
            // Some OEM builds refuse a background foreground-service start.
            // Falling back costs the user the open shade, but not the toggle.
            launchActivity(Intent(this, ToggleActivity::class.java))
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val quiet = QuietModeController(this).isQuiet
        tile.state = if (quiet) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(
            if (quiet) R.string.tile_subtitle_on else R.string.tile_subtitle_off
        )
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.contentDescription = getString(R.string.tile_content_description)
        tile.updateTile()
    }

    private fun launchActivity(activityIntent: Intent) {
        val intent = activityIntent
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                /* requestCode = */ 0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }
}
