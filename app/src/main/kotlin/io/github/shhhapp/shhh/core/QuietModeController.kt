package io.github.shhhapp.shhh.core

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock

/**
 * Applies and reads the quiet-mode state. Pure audio logic — timers, alarms
 * and notifications are orchestrated one level up in [HushManager].
 *
 * Quiet ON  = ring volume 0 + media volume 0.
 * Quiet OFF = ring volume restored + media volume restored (previous level or
 *             a fixed percentage, per settings).
 *
 * Shhh moves volume sliders and nothing else. It deliberately never calls
 * [AudioManager.setRingerMode]: that is AOSP's *external* ringer path, wired
 * straight into ZenModeHelper.onSetRingerModeExternal, where a NORMAL or
 * VIBRATE target ends whatever Do Not Disturb / Bedtime / driving mode the
 * user has running, and a SILENT target starts one. Stream-volume writes take
 * the *internal* path instead, which leaves zen untouched in both directions
 * (verified on Android 17 / Pixel: ring volume 0 and back, zen stayed on).
 *
 * Consequence, and the intended behaviour: while a Do Not Disturb mode is
 * active, DND wins. Un-hushing restores the sliders but the phone stays as
 * quiet as the user's own DND policy says it should be.
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
     * Do Not Disturb access, needed ONLY to move the ring volume across the
     * silent boundary while a zen mode is already active — AudioService throws
     * SecurityException("Not allowed to change Do Not Disturb state") there.
     * With no zen running, shhh works without this permission entirely.
     */
    val hasDndAccess: Boolean
        get() = notificationManager.isNotificationPolicyAccessGranted

    /**
     * True while any Do Not Disturb mode is active (the DND tile, Bedtime,
     * driving — all zen modes). UNKNOWN reads as inactive so a filter the
     * system cannot report never blanks out the real state.
     */
    val isDndActive: Boolean
        get() = when (notificationManager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
            else -> true
        }

    /**
     * False only in the one situation Android actually refuses shhh: a zen
     * mode is running AND Do Not Disturb access was never granted, so every
     * ring-volume write across the silent boundary throws. With no zen
     * running shhh needs no permission at all.
     */
    val canChangeSound: Boolean
        get() = hasDndAccess || !isDndActive || zenOwnsRingVolume

    /**
     * True under the zen modes that take the ring stream over completely.
     *
     * Measured on Android 17 / Pixel. A priority-filter zen (plain Do Not
     * Disturb, Bedtime) only masks the legacy *external* ringer mode to
     * SILENT: the internal mode and the ring volume stay real, so shhh can
     * read and write them normally. "Alarms only" and "Total silence" instead
     * pin the *internal* ringer mode to SILENT and drive the ring volume to 0.
     *
     * Under those two the slider is untouchable in both directions:
     *  - reading it always yields 0, so it cannot say whether shhh is on;
     *  - writing it makes AudioService recompute the internal ringer mode
     *    (0 -> VIBRATE, >0 -> NORMAL) and hand that to
     *    ZenModeHelper.onSetRingerModeInternal, which ends the zen the moment
     *    the internal mode leaves SILENT. That kills the user's mode just as
     *    surely as the setRingerMode call this class was written to avoid, and
     *    it snoozes automatic rules such as Bedtime for the rest of their run.
     *
     * So under these two filters shhh leaves the ring alone entirely — the zen
     * already silences it more deeply than a hush would — mutes media only,
     * and trusts the remembered state. The platform hands the user's own ring
     * volume back untouched when the zen ends.
     */
    private val zenOwnsRingVolume: Boolean
        get() = when (notificationManager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            else -> false
        }

    /**
     * True when the ring volume is at zero — the same slider shhh writes, so
     * the toggle can never drift out of sync with the volume keys or system
     * settings. Under a zen that owns the ring (see [zenOwnsRingVolume]) this
     * falls back to the last state observed while the slider was readable.
     * Everywhere else the remembered value is kept in sync on every read.
     */
    val isQuiet: Boolean
        get() {
            if (zenOwnsRingVolume) return settings.lastKnownQuiet
            val quiet = audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0
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
     * The ring volume moves FIRST and its outcome alone decides the result,
     * because the ring volume is what [isQuiet] reads. If it is refused
     * nothing has changed yet, so [Result.NeedsDndAccess] is honest; if it
     * succeeds the transition really happened, and a media-volume write
     * refused afterwards must not turn that into a reported failure — callers
     * treat failure as "nothing to clean up" and would strand the timer and
     * the tile/widget out of sync.
     *
     * No up-front permission check: the write is simply attempted, so the
     * common case (no zen running) needs no Do Not Disturb access at all.
     */
    fun goQuiet(): Result = try {
        val ring = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        applyRingVolume(0)
        // Persisted only after the write went through, so a refused hush
        // really does leave every stored value alone.
        if (ring > 0) settings.previousRingVolume = ring

        val media = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (media > 0) settings.previousMediaVolume = media
        setMediaVolumeBestEffort(0)

        settings.lastKnownQuiet = true
        Result.Success(quiet = true)
    } catch (_: SecurityException) {
        Result.NeedsDndAccess
    }

    /** Mirror of [goQuiet]: ring volume first, media volume best-effort after it. */
    fun restoreSound(): Result = try {
        applyRingVolume(restoreTargetRingVolume())
        setMediaVolumeBestEffort(restoreTargetVolume())
        settings.lastKnownQuiet = false
        Result.Success(quiet = false)
    } catch (_: SecurityException) {
        Result.NeedsDndAccess
    }

    /**
     * Writes the ring volume, then waits (bounded) until AudioService reflects
     * it. The write is applied asynchronously by the system; callers refresh
     * the tile/widget immediately after this returns, and without the wait
     * those surfaces sometimes recompose against the old value and freeze on a
     * stale state until their next scheduled update.
     *
     * Skipped entirely when [zenOwnsRingVolume]: there the write would end the
     * user's zen mode, and the ring is already silenced more deeply than a
     * hush would make it.
     */
    private fun applyRingVolume(target: Int) {
        // Never write while the zen owns the ring: the write would end it.
        if (zenOwnsRingVolume) return
        audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
        val deadline = SystemClock.uptimeMillis() + VOLUME_SETTLE_TIMEOUT_MS
        while (audioManager.getStreamVolume(AudioManager.STREAM_RING) != target &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(VOLUME_SETTLE_POLL_MS)
        }
    }

    /**
     * Media volume is a nice-to-have next to the ring volume: Android can
     * refuse it on its own (Total silence drops media writes outright,
     * background audio hardening) and that must not invalidate a ring
     * transition that already went through.
     */
    private fun setMediaVolumeBestEffort(target: Int) {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: SecurityException) {
            // Ring volume already moved; nothing to undo.
        }
    }

    /** Restores ONLY the media volume, leaving the ring hushed (headphones case). */
    fun restoreMediaVolumeOnly(): Boolean = try {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreTargetVolume(), 0)
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        // A stream-volume write settles in a few polls; the ceiling only
        // exists so a refused or masked write can never block a caller.
        const val VOLUME_SETTLE_TIMEOUT_MS = 500L
        const val VOLUME_SETTLE_POLL_MS = 10L
    }

    /** The ring level to come back to; half of max when nothing was saved. */
    private fun restoreTargetRingVolume(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val saved = settings.previousRingVolume
        return if (saved in 1..max) saved else (max / 2).coerceAtLeast(1)
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
