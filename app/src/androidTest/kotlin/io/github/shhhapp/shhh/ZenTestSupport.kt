package io.github.shhhapp.shhh

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertTrue
import java.io.FileInputStream

/**
 * Shared plumbing for the on-device zen tests: shell access, bounded polling
 * waits, and a settled way to move the phone between Do Not Disturb states.
 *
 * Everything here talks to the real system services. Zen changes and the audio
 * side effects that follow them are applied asynchronously by system_server, so
 * every state change goes through [waitFor] rather than a blind sleep.
 */

/** Runs a shell command through the instrumentation and returns its output. */
fun shell(command: String): String {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    return automation.executeShellCommand(command).use { fd ->
        fd.checkError()
        // Drain so the command fully completes before returning.
        FileInputStream(fd.fileDescriptor).readBytes().decodeToString()
    }
}

/** Zen changes propagate asynchronously; poll instead of sleeping blind. */
fun waitFor(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        assertTrue("timed out waiting for: $what", System.currentTimeMillis() < deadline)
        Thread.sleep(50)
    }
}

/**
 * True once [condition] holds, false if it never does within [timeoutMillis].
 * For pinning behaviour that may legitimately never happen (a broadcast that
 * the platform does not send), where a timeout is the answer, not a failure.
 */
fun waitUntilOrGiveUp(timeoutMillis: Long = 3_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(50)
    }
    return condition()
}

/**
 * The Do Not Disturb states shhh has to survive, with the shell argument that
 * reaches each one and the interruption filter it must report afterwards.
 *
 * `set_dnd none` is the platform's own spelling of "Total silence"; `off` is
 * the same as `all`, i.e. no zen at all.
 */
enum class ZenState(val shellArgument: String, val filter: Int) {
    OFF("off", NotificationManager.INTERRUPTION_FILTER_ALL),
    PRIORITY("priority", NotificationManager.INTERRUPTION_FILTER_PRIORITY),
    ALARMS("alarms", NotificationManager.INTERRUPTION_FILTER_ALARMS),
    TOTAL_SILENCE("none", NotificationManager.INTERRUPTION_FILTER_NONE);

    /**
     * The two filters that take the ring stream over: they drive the volume
     * every app reads to 0 AND end themselves if anyone writes it, so shhh
     * neither trusts nor touches the slider while one of them runs.
     */
    val ownsRingVolume: Boolean get() = this == ALARMS || this == TOTAL_SILENCE
}

/**
 * Whether the notification shade / Quick Settings panel is currently open.
 *
 * `dumpsys window windows` lists a NotificationShade window at all times, but
 * it only owns a Surface while the panel is actually up, so the presence of
 * `mSurface=Surface` inside that window's block is the signal. Verified in
 * both directions against `cmd statusbar expand-settings` / `collapse`.
 */
fun isShadeOpen(): Boolean {
    val dump = shell("dumpsys window windows").lineSequence().iterator()
    while (dump.hasNext()) {
        if (!dump.next().contains("NotificationShade}:")) continue
        while (dump.hasNext()) {
            val line = dump.next()
            if (line.contains("mSurface=Surface")) return true
            if (line.trimStart().startsWith("Window #")) return false
        }
    }
    return false
}

/**
 * How long a zen transition may take to fully land. The propagation usually
 * finishes within a few hundred ms, but it runs asynchronously in
 * system_server and QUEUES: a test that flips zen states back-to-back (the
 * alarm-stream matrix walks all four twice) can push a single settle past the
 * generic 5 s wait under full-suite load — seen flaking exactly once there.
 * The wait polls, so a roomy ceiling costs nothing when the system is quick.
 */
private const val ZEN_SETTLE_TIMEOUT_MILLIS = 20_000L

/** How often [DeviceAudio.applyZen] re-sends its command while unsettled. */
private const val ZEN_RESEND_INTERVAL_MILLIS = 2_000L

/** The system-service handles every zen test needs, resolved once. */
class DeviceAudio {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val filter: Int get() = notificationManager.currentInterruptionFilter
    val ringVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_RING)
    val mediaVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val alarmVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    val alarmMuted: Boolean get() = audioManager.isStreamMute(AudioManager.STREAM_ALARM)

    /** `settings get global zen_mode`: 0 off, 1 priority, 2 total silence, 3 alarms. */
    val globalZenMode: String get() = shell("settings get global zen_mode").trim()

    /**
     * Moves the phone to [state] and waits until the change has fully landed.
     *
     * AudioService applies the *external* ringer mask a few hundred ms after
     * [NotificationManager.getCurrentInterruptionFilter] flips, in both
     * directions, so waiting on the filter alone leaves a window where the
     * audio side of the state is still the old one. Every zen mode masks the
     * external ringer mode to SILENT, so that is the settle signal on the way
     * in; on the way out the mask lifts to NORMAL, or to VIBRATE when the ring
     * volume happens to be 0 — either way, no longer SILENT.
     */
    fun applyZen(state: ZenState) {
        // Re-issued while the wait runs: two zen flips in quick succession can
        // settle OUT OF ORDER in system_server (seen once as a total-silence
        // mask that never landed because the previous OFF's un-mask arrived
        // after it), and re-sending the same idempotent command makes
        // AudioService recompute from the intended state.
        var lastSend = 0L
        fun settle(what: String, condition: () -> Boolean) {
            waitFor(what, timeoutMillis = ZEN_SETTLE_TIMEOUT_MILLIS) {
                val now = System.currentTimeMillis()
                if (!condition() && now - lastSend >= ZEN_RESEND_INTERVAL_MILLIS) {
                    lastSend = now
                    shell("cmd notification set_dnd ${state.shellArgument}")
                }
                condition()
            }
        }
        lastSend = System.currentTimeMillis()
        shell("cmd notification set_dnd ${state.shellArgument}")
        settle("interruption filter = $state") { filter == state.filter }
        if (state == ZenState.OFF) {
            settle("external ringer un-masked") {
                audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT || ringVolume == 0
            }
        } else {
            settle("external ringer masked by $state") {
                audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
            }
        }
    }

    /**
     * Puts the phone back in a known-good state for the next test: no zen, Do
     * Not Disturb access granted, ring and media sliders audible.
     *
     * The zen goes off FIRST: while a ring-masking zen is running the ring
     * volume is not writable to a truthful value, and when the zen ends the
     * platform hands back the level it had saved, overwriting anything set
     * meanwhile.
     */
    fun resetToAudibleNoZen(ring: Int = DEFAULT_RING_VOLUME, media: Int = DEFAULT_MEDIA_VOLUME) {
        grantDndAccess()
        applyZen(ZenState.OFF)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, ring, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, media, 0)
        waitFor("ring volume settled at $ring") { ringVolume == ring }
        waitFor("media volume settled at $media") { mediaVolume == media }
    }

    /**
     * Waits for a whole hush transition to land, not just its first half.
     *
     * goQuiet and restoreSound move the ring slider first and the media slider
     * after it, so any test that waits only on the ring can look at media while
     * it is still mid-flight. Every assertion about a completed transition has
     * to go through here.
     */
    fun awaitTransition(ring: Int, media: Int, what: String = "transition") {
        waitFor("$what: ring=$ring media=$media") {
            ringVolume == ring && mediaVolume == media
        }
    }

    fun grantDndAccess() {
        shell("cmd notification allow_dnd ${context.packageName}")
        waitFor("Do Not Disturb access granted") {
            notificationManager.isNotificationPolicyAccessGranted
        }
    }

    fun revokeDndAccess() {
        shell("cmd notification disallow_dnd ${context.packageName}")
        waitFor("Do Not Disturb access revoked") {
            !notificationManager.isNotificationPolicyAccessGranted
        }
    }

    /** Clears every persisted value a hush test can be influenced by. */
    fun resetShhhState(settings: ShhhSettings) {
        settings.lastKnownQuiet = false
        settings.previousRingVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        settings.timerEndMillis = 0L
    }

    /**
     * AudioService's own volume-event log, the section that records every
     * `setStreamVolume` and every ringer-mode change together with the package
     * that asked for it. It is what makes "shhh never calls setRingerMode"
     * checkable from the outside instead of by reading the source.
     */
    fun volumeEvents(): List<String> =
        shell("dumpsys audio").lineSequence()
            .dropWhile { !it.contains("## Volume events") }
            .takeWhile { !it.contains("### Mute commands") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /**
     * Ringer-mode changes AudioService attributed to this app. A call to
     * [AudioManager.setRingerMode] logs `setRingerMode external to N,
     * caller=<package>`; the internal mode changes AudioService derives from a
     * stream-volume write are logged against AudioService itself, not us.
     */
    fun ringerModeEventsFromThisApp(): List<String> =
        volumeEvents().filter {
            it.contains("setRingerMode") && it.contains("caller=${context.packageName}")
        }

    companion object {
        /** Audible but not maximal, so a restore to "half of max" is distinguishable. */
        const val DEFAULT_RING_VOLUME = 3
        const val DEFAULT_MEDIA_VOLUME = 5
    }
}
