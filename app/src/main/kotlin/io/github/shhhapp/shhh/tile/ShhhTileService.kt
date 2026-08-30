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

/**
 * Quick Settings tile: one tap flips quiet mode.
 *
 * The tile is passive — [onStartListening] runs every time the shade opens,
 * so the displayed state always reflects the phone's actual ringer mode.
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
        if (!manager.hasDndAccess) {
            launchActivity(Intent(this, MainActivity::class.java))
            return
        }

        // While a DND mode is active, act only through the visible trampoline.
        // Background audio writes are silently dropped under DND (verified on
        // Android 17: a tile-side restore neither exited zen nor brought sound
        // back), and with the ringer masked the remembered-state fallback would
        // report the flip as applied anyway, hiding the dropped write from the
        // retry check below.
        if (manager.isDndActive) {
            launchActivity(Intent(this, ToggleActivity::class.java))
            return
        }

        val before = manager.isQuiet
        val result = manager.toggle()
        val applied =
            result is QuietModeController.Result.Success && manager.isQuiet != before

        if (applied) {
            refreshTile()
        } else {
            // Android 16+ audio hardening silently drops ringer/volume changes
            // from background processes. Retry through an invisible foreground
            // trampoline, which is always allowed to apply them.
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
