package io.github.shhhapp.shhh

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController

/**
 * Invisible, instant toggle trampoline — and the app's public automation
 * surface (Tasker, Google Assistant routines, launcher shortcuts, `am start`).
 *
 * Android 16/17 "audio hardening" silently ignores volume and ringer changes
 * from backgrounded processes — which is what a TileService or a widget
 * ActionCallback is. A visible activity is always allowed, so those taps
 * route through this zero-UI activity: it acts, refreshes the other
 * surfaces, and finishes before anything is drawn.
 *
 * Supported actions (all exported, all safe — they only affect sound state):
 *  - [ACTION_HUSH]    with optional int/string extra [EXTRA_DURATION_MINUTES]
 *  - [ACTION_UNHUSH]
 *  - [ACTION_TOGGLE]  (default when launched with no action)
 *  - [ACTION_RESTORE_MEDIA] (media volume only; ringer stays hushed)
 */
class ToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = HushManager(this)
        val result = when (intent?.action) {
            ACTION_HUSH -> manager.hush(durationMinutes = readDurationExtra())
            ACTION_UNHUSH -> manager.unhush()
            ACTION_RESTORE_MEDIA -> {
                if (manager.restoreMediaOnly()) manager.refreshSurfaces()
                QuietModeController.Result.Success(quiet = manager.isQuiet)
            }
            else -> manager.toggle()
        }

        if (result == QuietModeController.Result.NeedsDndAccess) {
            // Fall through to the full app so the user can grant access.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    /** Accepts both int and string extras — `am` and some automation apps send strings. */
    private fun readDurationExtra(): Long? {
        val fromInt = intent.getIntExtra(EXTRA_DURATION_MINUTES, -1)
        if (fromInt > 0) return fromInt.toLong()
        return intent.getStringExtra(EXTRA_DURATION_MINUTES)?.toLongOrNull()?.takeIf { it > 0 }
    }

    companion object {
        const val ACTION_HUSH = "io.github.shhhapp.shhh.action.HUSH"
        const val ACTION_UNHUSH = "io.github.shhhapp.shhh.action.UNHUSH"
        const val ACTION_TOGGLE = "io.github.shhhapp.shhh.action.TOGGLE"
        const val ACTION_RESTORE_MEDIA = "io.github.shhhapp.shhh.action.RESTORE_MEDIA"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
    }
}
