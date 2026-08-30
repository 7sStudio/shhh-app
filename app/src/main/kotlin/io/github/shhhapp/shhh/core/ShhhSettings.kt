package io.github.shhhapp.shhh.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.DayOfWeek

/**
 * All persisted state, in one small SharedPreferences wrapper.
 * SharedPreferences (not DataStore) on purpose: receivers and services need
 * fast synchronous reads at alarm time.
 */
class ShhhSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** How the media volume comes back when un-hushing. */
    enum class RestoreMode { PREVIOUS, FIXED }

    var restoreMode: RestoreMode
        get() = RestoreMode.entries[prefs.getInt(KEY_RESTORE_MODE, RestoreMode.PREVIOUS.ordinal)]
        set(value) = prefs.edit { putInt(KEY_RESTORE_MODE, value.ordinal) }

    /** Restore target as a percentage of max media volume, used in [RestoreMode.FIXED]. */
    var fixedRestorePercent: Int
        get() = prefs.getInt(KEY_FIXED_RESTORE_PERCENT, 50)
        set(value) = prefs.edit { putInt(KEY_FIXED_RESTORE_PERCENT, value.coerceIn(10, 100)) }

    /** Media volume captured right before muting; [NO_SAVED_VOLUME] when unknown. */
    var previousMediaVolume: Int
        get() = prefs.getInt(KEY_PREVIOUS_MEDIA_VOLUME, NO_SAVED_VOLUME)
        set(value) = prefs.edit { putInt(KEY_PREVIOUS_MEDIA_VOLUME, value) }

    /** Ring volume captured right before hushing; [NO_SAVED_VOLUME] when unknown. */
    var previousRingVolume: Int
        get() = prefs.getInt(KEY_PREVIOUS_RING_VOLUME, NO_SAVED_VOLUME)
        set(value) = prefs.edit { putInt(KEY_PREVIOUS_RING_VOLUME, value) }

    /**
     * Last quiet state observed while the ring volume was still readable.
     * "Alarms only" and "Total silence" zen modes drive the ring volume every
     * app sees to 0, so under those two this remembered value is the only
     * truthful answer to "is shhh on?" — see [QuietModeController.isQuiet].
     */
    var lastKnownQuiet: Boolean
        get() = prefs.getBoolean(KEY_LAST_KNOWN_QUIET, false)
        set(value) = prefs.edit { putBoolean(KEY_LAST_KNOWN_QUIET, value) }

    /** Countdown notification while a hush timer is running. */
    var liveCountdownEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_COUNTDOWN, true)
        set(value) = prefs.edit { putBoolean(KEY_LIVE_COUNTDOWN, value) }

    /** Restore media volume (ringer stays hushed) when headphones connect. */
    var headphonesAutoRestore: Boolean
        get() = prefs.getBoolean(KEY_HEADPHONES_RESTORE, false)
        set(value) = prefs.edit { putBoolean(KEY_HEADPHONES_RESTORE, value) }

    // ---- Updates ----

    /** Once-a-day update check when the app opens. Opt-in: no network by default. */
    var autoUpdateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_UPDATE_CHECK, value) }

    /** Epoch millis of the last automatic update check attempt; 0 when never. */
    var lastUpdateCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_UPDATE_CHECK, value) }

    /** Newest version the auto-check already prompted for, so each release nags once. */
    var lastPromptedUpdateVersion: String
        get() = prefs.getString(KEY_LAST_PROMPTED_UPDATE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_PROMPTED_UPDATE, value) }

    // ---- Hush timer ----

    /** Epoch millis when the running hush timer ends; 0 when no timer is active. */
    var timerEndMillis: Long
        get() = prefs.getLong(KEY_TIMER_END, 0L)
        set(value) = prefs.edit { putLong(KEY_TIMER_END, value) }

    // ---- Quiet hours ----

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_QUIET_ENABLED, value) }

    /** Minutes after midnight, local time. Default 23:00. */
    var quietStartMinutes: Int
        get() = prefs.getInt(KEY_QUIET_START, 23 * 60)
        set(value) = prefs.edit { putInt(KEY_QUIET_START, value.coerceIn(0, 24 * 60 - 1)) }

    /** Minutes after midnight, local time. Default 07:00 (next day when <= start). */
    var quietEndMinutes: Int
        get() = prefs.getInt(KEY_QUIET_END, 7 * 60)
        set(value) = prefs.edit { putInt(KEY_QUIET_END, value.coerceIn(0, 24 * 60 - 1)) }

    /** Days the quiet window STARTS on. Default: every day. */
    var quietDays: Set<DayOfWeek>
        get() {
            val bits = prefs.getInt(KEY_QUIET_DAYS, ALL_DAYS_MASK)
            return DayOfWeek.entries.filterTo(mutableSetOf()) { day ->
                bits and (1 shl (day.value - 1)) != 0
            }
        }
        set(value) {
            val bits = value.fold(0) { acc, day -> acc or (1 shl (day.value - 1)) }
            prefs.edit { putInt(KEY_QUIET_DAYS, bits) }
        }

    companion object {
        const val NO_SAVED_VOLUME = -1
        private const val ALL_DAYS_MASK = 0b1111111

        private const val PREFS_NAME = "shhh"
        private const val KEY_RESTORE_MODE = "restore_mode"
        private const val KEY_FIXED_RESTORE_PERCENT = "fixed_restore_percent"
        private const val KEY_PREVIOUS_MEDIA_VOLUME = "previous_media_volume"
        private const val KEY_PREVIOUS_RING_VOLUME = "previous_ring_volume"
        private const val KEY_LAST_KNOWN_QUIET = "last_known_quiet"
        private const val KEY_LIVE_COUNTDOWN = "live_countdown_enabled"
        private const val KEY_HEADPHONES_RESTORE = "headphones_auto_restore"
        private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check_enabled"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check_millis"
        private const val KEY_LAST_PROMPTED_UPDATE = "last_prompted_update_version"
        private const val KEY_TIMER_END = "timer_end_millis"
        private const val KEY_QUIET_ENABLED = "quiet_hours_enabled"
        private const val KEY_QUIET_START = "quiet_start_minutes"
        private const val KEY_QUIET_END = "quiet_end_minutes"
        private const val KEY_QUIET_DAYS = "quiet_days"
    }
}
