package io.github.shhhapp.shhh.core

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock

/**
 * Applies and reads the quiet-mode state. Pure audio logic — timers, alarms
 * and notifications are orchestrated one level up in [HushManager].
 *
 * Quiet ON  = ringer set to vibrate (or silent, per settings) + media muted.
 * Quiet OFF = ringer set to normal + media restored (previous level or a
 *             fixed percentage, per settings).
 *
 * "Quiet" is defined by what the phone is actually doing right now, so the
 * toggle can never drift out of sync with changes made through the volume
 * keys or system settings. The one exception is while Do Not Disturb is
 * active, when the ringer mode apps can read is masked — see [isQuiet].
 */
class QuietModeController(
    context: Context,
    private val settings: ShhhSettings = ShhhSettings(context)
) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Ringer-mode transitions require Do Not Disturb access on modern Android;
     * without it [AudioManager.setRingerMode] can throw [SecurityException].
     */
    val hasDndAccess: Boolean
        get() = notificationManager.isNotificationPolicyAccessGranted

    /**
     * True while any Do Not Disturb mode is active (the DND tile, Bedtime,
     * driving — all zen modes). UNKNOWN reads as inactive so a filter the
     * system cannot report never blanks out the real ringer state.
     */
    val isDndActive: Boolean
        get() = when (notificationManager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
            else -> true
        }

    /**
     * True when the ringer is anything other than normal (vibrate or silent).
     *
     * While Do Not Disturb is active the ringer mode cannot be trusted:
     * AudioService masks the value reported to apps to SILENT for every zen
     * mode, even when the underlying ringer is untouched (verified on
     * Android 17 / Pixel — internal NORMAL, external SILENT). Reading it
     * directly made the tile/widget light up whenever DND or Bedtime mode
     * turned on. So under DND this falls back to the last state observed
     * while the ringer was still readable — which is also what the ringer
     * returns to when DND ends. Outside DND the remembered value is kept in
     * sync on every read, so volume-key changes are picked up as before.
     */
    val isQuiet: Boolean
        get() {
            if (isDndActive) return settings.lastKnownQuiet
            val quiet = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
            if (settings.lastKnownQuiet != quiet) settings.lastKnownQuiet = quiet
            return quiet
        }

    sealed interface Result {
        /** Toggle applied; [quiet] is the new state. */
        data class Success(val quiet: Boolean) : Result

        /** Do Not Disturb access has not been granted; nothing was changed. */
        data object NeedsDndAccess : Result
    }

    /** Flips quiet mode based on the phone's actual current state. */
    fun toggle(): Result = if (isQuiet) restoreSound() else goQuiet()

    /**
     * The ringer moves FIRST and its outcome alone decides the result, because
     * the ringer is what [isQuiet] reads. If it is refused nothing has changed
     * yet, so [Result.NeedsDndAccess] is honest; if it succeeds the transition
     * really happened, and a media-volume write refused afterwards must not
     * turn that into a reported failure — callers treat failure as "nothing to
     * clean up" and would strand the timer and the tile/widget out of sync.
     */
    fun goQuiet(): Result {
        if (!hasDndAccess) return Result.NeedsDndAccess
        return try {
            // While a DND mode is active the ringer is already silenced more
            // deeply than vibrate — and ANY app ringer write makes
            // AudioService exit the user's zen mode (verified on Android 17:
            // NORMAL and VIBRATE writes both clear an active DND/Bedtime
            // mode). Hushing must never eat the user's DND, so the ringer is
            // left alone; only media is muted and the hush remembered.
            if (!isDndActive) {
                applyRingerMode(when (settings.hushRinger) {
                    ShhhSettings.HushRinger.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                    ShhhSettings.HushRinger.SILENT -> AudioManager.RINGER_MODE_SILENT
                })
            }
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current > 0) {
                settings.previousMediaVolume = current
            }
            setMediaVolumeBestEffort(0)
            settings.lastKnownQuiet = true
            Result.Success(quiet = true)
        } catch (_: SecurityException) {
            Result.NeedsDndAccess
        }
    }

    /** Mirror of [goQuiet]: ringer first, media volume best-effort after it. */
    fun restoreSound(): Result {
        if (!hasDndAccess) return Result.NeedsDndAccess
        return try {
            applyRingerMode(AudioManager.RINGER_MODE_NORMAL)
            setMediaVolumeBestEffort(restoreTargetVolume())
            settings.lastKnownQuiet = false
            Result.Success(quiet = false)
        } catch (_: SecurityException) {
            Result.NeedsDndAccess
        }
    }

    /**
     * Writes the ringer mode, then waits (bounded) until AudioService reflects
     * it. The write is applied asynchronously by the system; callers refresh
     * the tile/widget immediately after this returns, and without the wait
     * those surfaces sometimes recompose against the old mode and freeze on a
     * stale state until their next scheduled update.
     */
    /**
     * Waiting also matters when this write exits an active DND mode (the
     * restore path): the zen exit and the ringer un-mask land asynchronously
     * AFTER the interruption filter already reads "off". A caller reading
     * [isQuiet] in that window would derive "quiet" from the still-masked
     * SILENT ringer and re-poison [ShhhSettings.lastKnownQuiet] right after
     * restoreSound cleared it — seen live on Android 17 as the tile showing
     * ON at the next DND without any hush. Returning only once the target is
     * readable (or the bounded timeout passes) closes that window. goQuiet
     * never reaches here while DND is active, so the timeout cannot burn on
     * a permanently-masked read.
     */
    private fun applyRingerMode(target: Int) {
        audioManager.ringerMode = target
        val deadline = SystemClock.uptimeMillis() + RINGER_SETTLE_TIMEOUT_MS
        while (audioManager.ringerMode != target && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(RINGER_SETTLE_POLL_MS)
        }
    }

    /**
     * Media volume is a nice-to-have next to the ringer change: Android can
     * refuse it on its own (DND policy, background audio hardening) and that
     * must not invalidate a ringer transition that already went through.
     */
    private fun setMediaVolumeBestEffort(target: Int) {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: SecurityException) {
            // Ringer already moved; nothing to undo.
        }
    }

    /** Restores ONLY the media volume, leaving the ringer hushed (headphones case). */
    fun restoreMediaVolumeOnly(): Boolean = try {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreTargetVolume(), 0)
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        // 500ms covers the zen-exit + ringer-unmask propagation observed on
        // Android 17 (a restore during DND settles well under this); the
        // plain no-DND write settles in a few polls.
        const val RINGER_SETTLE_TIMEOUT_MS = 500L
        const val RINGER_SETTLE_POLL_MS = 10L
    }

    private fun restoreTargetVolume(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return when (settings.restoreMode) {
            ShhhSettings.RestoreMode.FIXED ->
                (max * settings.fixedRestorePercent / 100).coerceIn(1, max)
            ShhhSettings.RestoreMode.PREVIOUS -> {
                val saved = settings.previousMediaVolume
                if (saved in 1..max) saved else (max / 2).coerceAtLeast(1)
            }
        }
    }
}
