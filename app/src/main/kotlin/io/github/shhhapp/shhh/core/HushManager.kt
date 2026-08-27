package io.github.shhhapp.shhh.core

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import io.github.shhhapp.shhh.notify.CountdownNotifier
import io.github.shhhapp.shhh.schedule.HushAlarms
import io.github.shhhapp.shhh.tile.ShhhTileService
import io.github.shhhapp.shhh.widget.ShhhWidget
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The one entry point every surface (app, tile, widget, shortcuts, alarms,
 * automation intents) uses to change hush state. Wraps [QuietModeController]
 * with timer + notification + surface-refresh orchestration so no caller can
 * forget a piece.
 */
class HushManager(private val context: Context) {

    private val settings = ShhhSettings(context)
    private val controller = QuietModeController(context, settings)

    val isQuiet: Boolean get() = controller.isQuiet
    val hasDndAccess: Boolean get() = controller.hasDndAccess

    /** End of the running hush timer (epoch millis), or 0 when none. */
    val activeTimerEnd: Long
        get() = settings.timerEndMillis.takeIf { it > System.currentTimeMillis() } ?: 0L

    fun toggle(): QuietModeController.Result =
        if (controller.isQuiet) unhush() else hush()

    /**
     * Hushes, optionally for a limited time. When already hushed, a duration
     * just (re)arms the timer — tapping "30 min" while hushed does what the
     * user expects.
     */
    fun hush(durationMinutes: Long? = null): QuietModeController.Result {
        val result = controller.goQuiet()
        if (result is QuietModeController.Result.Success) {
            if (durationMinutes != null) {
                armTimer(System.currentTimeMillis() + durationMinutes * 60_000L)
            }
            refreshSurfaces()
        }
        return result
    }

    /** Hushes until [end]; used by quiet-hours starts. */
    fun hushUntil(end: LocalDateTime): QuietModeController.Result {
        val result = controller.goQuiet()
        if (result is QuietModeController.Result.Success) {
            armTimer(end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            refreshSurfaces()
        }
        return result
    }

    fun unhush(): QuietModeController.Result {
        val result = controller.restoreSound()
        if (result is QuietModeController.Result.Success) {
            disarmTimer()
            refreshSurfaces()
        }
        return result
    }

    /**
     * Timer fired: restore — unless the user already un-hushed some other way.
     *
     * The stored timer and its notification are cleared only once the restore
     * has actually succeeded. If it was refused (Do Not Disturb access revoked
     * while the timer ran) the countdown notification stays up, so its
     * "Restore now" action remains available instead of the phone sitting
     * hushed with nothing left to retry.
     */
    fun onTimerFired(): QuietModeController.Result {
        if (!controller.isQuiet) {
            clearTimer()
            return QuietModeController.Result.Success(quiet = false)
        }
        val result = controller.restoreSound()
        if (result is QuietModeController.Result.Success) {
            clearTimer()
            refreshSurfaces()
        }
        return result
    }

    private fun clearTimer() {
        settings.timerEndMillis = 0L
        CountdownNotifier.cancel(context)
    }

    /** True when the headphones option is on and the phone is currently hushed. */
    val isHeadphoneRestoreWanted: Boolean
        get() = settings.headphonesAutoRestore && controller.isQuiet

    /** Headphones connected while hushed: bring media back, keep ringer hushed. */
    fun onHeadphonesConnected(): Boolean {
        if (!isHeadphoneRestoreWanted) return false
        return controller.restoreMediaVolumeOnly()
    }

    /** Restores media volume only (ringer untouched). Returns true on success. */
    fun restoreMediaOnly(): Boolean = controller.restoreMediaVolumeOnly()

    private fun armTimer(endMillis: Long) {
        settings.timerEndMillis = endMillis
        HushAlarms.scheduleTimerRestore(context, endMillis)
        CountdownNotifier.showIfEnabled(context, endMillis)
    }

    private fun disarmTimer() {
        if (settings.timerEndMillis != 0L) {
            settings.timerEndMillis = 0L
            HushAlarms.cancelTimerRestore(context)
        }
        CountdownNotifier.cancel(context)
    }

    fun refreshSurfaces() {
        ShhhWidget.requestRefresh(context)
        TileService.requestListeningState(
            context,
            ComponentName(context, ShhhTileService::class.java)
        )
    }
}
