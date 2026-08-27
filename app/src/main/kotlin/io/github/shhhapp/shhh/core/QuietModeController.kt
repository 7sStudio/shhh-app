package io.github.shhhapp.shhh.core

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

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
 * keys or system settings.
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

    /** True when the ringer is anything other than normal (vibrate or silent). */
    val isQuiet: Boolean
        get() = audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL

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
            audioManager.ringerMode = when (settings.hushRinger) {
                ShhhSettings.HushRinger.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                ShhhSettings.HushRinger.SILENT -> AudioManager.RINGER_MODE_SILENT
            }
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current > 0) {
                settings.previousMediaVolume = current
            }
            setMediaVolumeBestEffort(0)
            Result.Success(quiet = true)
        } catch (_: SecurityException) {
            Result.NeedsDndAccess
        }
    }

    /** Mirror of [goQuiet]: ringer first, media volume best-effort after it. */
    fun restoreSound(): Result {
        if (!hasDndAccess) return Result.NeedsDndAccess
        return try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            setMediaVolumeBestEffort(restoreTargetVolume())
            Result.Success(quiet = false)
        } catch (_: SecurityException) {
            Result.NeedsDndAccess
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
