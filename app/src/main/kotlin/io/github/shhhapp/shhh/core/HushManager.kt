package io.github.shhhapp.shhh.core

import android.content.Context
import android.service.quicksettings.TileService
import io.github.shhhapp.shhh.audio.HeadsetReceiver
import io.github.shhhapp.shhh.notify.CountdownNotifier
import io.github.shhhapp.shhh.schedule.HushAlarms
import io.github.shhhapp.shhh.tile.ShhhTileService
import io.github.shhhapp.shhh.widget.ShhhWidget
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

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
    val isDndActive: Boolean get() = controller.isDndActive
    val canChangeSound: Boolean get() = controller.canChangeSound

    /** End of the running hush timer (epoch millis), or 0 when none. */
    val activeTimerEnd: Long
        get() = settings.timerEndMillis.takeIf { it > System.currentTimeMillis() } ?: 0L

    fun toggle(): QuietModeController.Result =
        if (controller.isQuiet) unhush() else hush()

    /**
     * Hushes, optionally for a limited time. When already hushed, a duration
     * just (re)arms the timer — tapping "30 min" while hushed does what the
     * user expects.
     *
     * A timed hush needs the exact alarm that ends it. Without the "Alarms &
     * reminders" grant it changes nothing and returns
     * [QuietModeController.Result.NeedsExactAlarmAccess]: a hush promising
     * "sound returns at HH:mm" that nothing would ever end is worse than no
     * hush. An untimed hush needs no alarm and is unaffected.
     */
    fun hush(durationMinutes: Long? = null): QuietModeController.Result {
        if (durationMinutes == null) {
            val result = controller.goQuiet()
            if (result is QuietModeController.Result.Success) refreshSurfaces()
            return result
        }
        // Durations arrive from outside too (automation extras, as numbers or
        // strings): bounded here, so no value can overflow into a timer that
        // ends in the past.
        val minutes = durationMinutes.coerceIn(1L, MAX_TIMED_HUSH_MINUTES)
        return hushTimed(System.currentTimeMillis() + minutes * 60_000L)
    }

    /**
     * Hushes until the next time the clock reads [minutesOfDay] (minutes
     * after midnight): later today, or tomorrow when that time has already
     * passed — picking the current minute means a full day, never "now".
     */
    fun hushUntilTimeOfDay(
        minutesOfDay: Int,
        now: LocalDateTime = LocalDateTime.now()
    ): QuietModeController.Result {
        val today = now.toLocalDate().atTime(minutesOfDay / 60, minutesOfDay % 60)
        return hushUntil(if (today.isAfter(now)) today else today.plusDays(1))
    }

    /** Hushes until [end]; used by quiet-hours starts. Same alarm rule as [hush]. */
    fun hushUntil(end: LocalDateTime): QuietModeController.Result =
        hushTimed(end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

    /**
     * Hushes for the quiet-hours window [now] falls in, until that window
     * ends, and remembers the window as dealt with — whatever the outcome, so
     * a later look at the same window (a reboot, a clock change) does not
     * hush again a phone the user has since un-hushed. Does nothing outside a
     * window or with quiet hours off.
     *
     * [onlyIfAudible] leaves an already hushed phone untouched, for the case
     * where the user is editing the schedule rather than a start coming due.
     */
    fun hushForQuietWindow(
        now: LocalDateTime = LocalDateTime.now(),
        onlyIfAudible: Boolean = false
    ) {
        val window = QuietHours.activeWindow(now, QuietHours.fromSettings(settings)) ?: return
        settings.lastQuietWindowStart = window.start.toEpochSecond(ZoneOffset.UTC)
        if (onlyIfAudible && controller.isQuiet) return
        hushUntil(window.end)
    }

    /**
     * True when [now] is inside a quiet-hours window whose start shhh never
     * acted on: the phone was off (or the clock elsewhere) when it came due.
     */
    fun isQuietWindowStartMissed(now: LocalDateTime = LocalDateTime.now()): Boolean {
        val window = QuietHours.activeWindow(now, QuietHours.fromSettings(settings))
            ?: return false
        return settings.lastQuietWindowStart != window.start.toEpochSecond(ZoneOffset.UTC)
    }

    private fun hushTimed(endMillis: Long): QuietModeController.Result {
        // Checked before any volume write, so a refusal really changed nothing.
        if (!HushAlarms.canScheduleExact(context)) {
            return QuietModeController.Result.NeedsExactAlarmAccess
        }
        val result = controller.goQuiet()
        if (result is QuietModeController.Result.Success) {
            armTimer(endMillis)
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
        clearNotifications()
    }

    /**
     * Shhh is off: nothing it posted is true any more, so all of it comes
     * down — the countdown, and a headphones offer the user never tapped.
     */
    private fun clearNotifications() {
        CountdownNotifier.cancel(context)
        HeadsetReceiver.cancelRestoreOffer(context)
    }

    /**
     * Retires everything a hush left standing once the phone turns out to be
     * audible again: a running timer (its alarm and stored end), the
     * countdown notification, and the headphones offer. Un-hushing through
     * shhh already does all of that; this covers the ways a hush ends without
     * shhh being asked (the volume slider, system settings), which only show
     * up when a surface next re-reads the phone.
     *
     * Call it ONLY where a surface observes the phone (activity resume, the
     * ringer / interruption-filter broadcasts, the shade opening), never on
     * the refresh that follows shhh's own transition: a ring write that has
     * not settled yet would read as "not hushed" there and disarm the timer
     * that was just armed.
     */
    fun retireStaleHush() {
        if (!controller.isQuiet) disarmTimer()
    }

    /** True when the headphones option is on and the phone is currently hushed. */
    val isHeadphoneRestoreWanted: Boolean
        get() = settings.headphonesAutoRestore && controller.isQuiet

    /** Headphones connected while hushed: bring media back, keep ringer hushed. */
    fun onHeadphonesConnected(): Boolean {
        if (!isHeadphoneRestoreWanted) return false
        return restoreMediaOnly()
    }

    /**
     * Restores media volume only (ringer untouched). Returns true on success,
     * which also retires the headphones offer: media is back, however that
     * came about (the offer's own tap, an automation intent, a reconnect).
     */
    fun restoreMediaOnly(): Boolean {
        val restored = controller.restoreMediaVolumeOnly()
        if (restored) HeadsetReceiver.cancelRestoreOffer(context)
        return restored
    }

    /**
     * Stores the timer and shows its countdown only once the alarm that ends
     * it is really scheduled. The grant can vanish between [hushTimed]'s check
     * and this call; the hush then stands untimed (it already happened) and
     * nothing claims a restore that no alarm will perform.
     */
    private fun armTimer(endMillis: Long) {
        if (!HushAlarms.scheduleTimerRestore(context, endMillis)) return
        settings.timerEndMillis = endMillis
        CountdownNotifier.showIfEnabled(context, endMillis)
    }

    private fun disarmTimer() {
        if (settings.timerEndMillis != 0L) {
            settings.timerEndMillis = 0L
            HushAlarms.cancelTimerRestore(context)
        }
        clearNotifications()
    }

    /**
     * Re-publishes the real state to every surface after a transition.
     *
     * The tile is pushed through a direct in-process call:
     * [TileService.requestListeningState] is a documented no-op for a passive
     * tile (no META_DATA_ACTIVE_TILE), and the tile's own broadcast receiver
     * is deaf to a hush while any Do Not Disturb mode runs — the ring write
     * only flips the *internal* ringer mode, and RINGER_MODE_CHANGED_ACTION
     * fires for the *external* one, which every zen pins at SILENT. Same
     * process is guaranteed: every surface transition runs in it.
     */
    fun refreshSurfaces() {
        ShhhWidget.requestRefresh(context)
        ShhhTileService.requestTileRefresh()
    }

    companion object {
        /** Longest timed hush accepted: a day. Longer requests are cut to it. */
        const val MAX_TIMED_HUSH_MINUTES = 24L * 60L
    }
}
