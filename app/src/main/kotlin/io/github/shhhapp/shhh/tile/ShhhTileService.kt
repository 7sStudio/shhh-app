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
 *
 * Shhh's OWN toggles are pushed through [requestTileRefresh] instead. The
 * broadcast cannot carry them while a Do Not Disturb mode runs: the receiver's
 * RINGER_MODE_CHANGED_ACTION reflects the *external* ringer mode, which any
 * zen pins at SILENT, so the internal VIBRATE↔NORMAL flip a hush causes never
 * broadcasts (AudioService.setRingerModeExt returns early on no-change). And
 * [TileService.requestListeningState] is a documented no-op for a tile without
 * META_DATA_ACTIVE_TILE, so the only refresh that reaches a listening tile
 * from the app's own transitions is this direct in-process call.
 */
class ShhhTileService : TileService() {

    private var stateReceiver: BroadcastReceiver? = null

    companion object {
        /**
         * The instance currently in the listening state, i.e. shown in an open
         * shade — the only time a refresh can land. Every surface transition
         * runs in this same process (tile, HushService, ToggleActivity,
         * alarms), so a plain reference is all the plumbing the push needs.
         */
        @Volatile
        internal var listeningInstance: ShhhTileService? = null

        /**
         * Re-reads the phone's state into the tile shown in an open shade;
         * does nothing when no shade is open. Called by
         * [io.github.shhhapp.shhh.core.HushManager.refreshSurfaces] after
         * every hush transition.
         */
        fun requestTileRefresh() {
            listeningInstance?.refreshTile()
        }
    }

    override fun onStartListening() {
        listeningInstance = this
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
        dropListeningInstance()
        unregisterStateReceiver()
    }

    override fun onDestroy() {
        // The system does not guarantee onStopListening before an unbind kill.
        dropListeningInstance()
        unregisterStateReceiver()
        super.onDestroy()
    }

    private fun dropListeningInstance() {
        if (listeningInstance === this) listeningInstance = null
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
        // shade. Once its change has landed it pushes the real state back here
        // through requestTileRefresh.
        try {
            startForegroundService(HushService.intent(this, HushService.ACTION_TOGGLE))
            // Flip the tile now, like Wi-Fi or the torch, instead of leaving
            // it on the old state for the service's round-trip. The refresh
            // that follows confirms it — or snaps it back if Android refused
            // the write (a zen mode racing in between the check above and the
            // service's volume write).
            renderTile(quiet = !manager.isQuiet)
        } catch (_: Exception) {
            // Some OEM builds refuse a background foreground-service start.
            // Falling back costs the user the open shade, but not the toggle.
            launchActivity(Intent(this, ToggleActivity::class.java))
        }
    }

    internal fun refreshTile() = renderTile(QuietModeController(this).isQuiet)

    private fun renderTile(quiet: Boolean) {
        val tile = qsTile ?: return
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
