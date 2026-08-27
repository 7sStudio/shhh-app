package io.github.shhhapp.shhh.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
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
 */
class ShhhTileService : TileService() {

    override fun onStartListening() {
        refreshTile()
    }

    override fun onClick() {
        val manager = HushManager(this)
        if (!manager.hasDndAccess) {
            launchActivity(Intent(this, MainActivity::class.java))
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
