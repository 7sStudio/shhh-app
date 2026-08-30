package io.github.shhhapp.shhh.core

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioManager

@RunWith(AndroidJUnit4::class)
class QuietModeControllerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var controller: QuietModeController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        // SharedPreferences outlive a single test in a Robolectric class run.
        context.getSharedPreferences("shhh", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(audioManager).setStreamMaxVolume(ShadowAudioManager.DEFAULT_MAX_VOLUME)
        controller = QuietModeController(context)

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun `goQuiet sets vibrate and mutes media`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `restoreSound restores previous media volume`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        controller.goQuiet()

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(7, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse(controller.isQuiet)
    }

    @Test
    fun `restoreSound with no saved volume falls back to half of max`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        controller.restoreSound()

        val expected = (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2)
            .coerceAtLeast(1)
        assertEquals(expected, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    @Test
    fun `goQuiet when media already muted keeps earlier saved volume`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        controller.goQuiet()
        controller.restoreSound()

        // Mute manually, then hush again: volume 0 must not overwrite the saved 5.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        controller.goQuiet()
        controller.restoreSound()

        assertEquals(5, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `toggle flips based on actual ringer state`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)

        var result = controller.toggle()
        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)

        result = controller.toggle()
        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(4, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `toggle reacts to ringer changes made outside the app`() {
        // e.g. the user set vibrate via the volume keys — toggle must restore sound.
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        val result = controller.toggle()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    @Test
    fun `silent ringer setting engages silent instead of vibrate`() {
        ShhhSettings(context).hushRinger = ShhhSettings.HushRinger.SILENT

        controller.goQuiet()

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `fixed restore mode restores the configured percentage`() {
        val settings = ShhhSettings(context)
        settings.restoreMode = ShhhSettings.RestoreMode.FIXED
        settings.fixedRestorePercent = 80
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)

        controller.goQuiet()
        controller.restoreSound()

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        assertEquals((max * 80 / 100).coerceIn(1, max),
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `restoreMediaVolumeOnly leaves ringer hushed`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        controller.goQuiet()

        assertTrue(controller.restoreMediaVolumeOnly())

        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `toggle restores from externally-set silent mode too`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        val result = controller.toggle()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
    }

    @Test
    fun `without dnd access nothing changes`() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)

        val result = controller.toggle()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse(controller.hasDndAccess)
    }

    @Test
    fun `restoreSound without dnd access changes nothing`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .setNotificationPolicyAccessGranted(false)

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.NeedsDndAccess, result)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `restoreMediaVolumeOnly reports failure when the volume stays at zero`() {
        // Max volume 0 makes the write a no-op, like an Android 16+ hardening drop.
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        shadowOf(audioManager).setStreamMaxVolume(0)

        assertFalse(controller.restoreMediaVolumeOnly())
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `a saved volume above the maximum falls back to half of max`() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        ShhhSettings(context).previousMediaVolume = max + 5
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        controller.restoreSound()

        assertEquals((max / 2).coerceAtLeast(1),
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // ---- Do Not Disturb ----
    // While any zen mode is active (the DND tile, Bedtime, driving),
    // AudioService masks the ringer mode apps can read to SILENT no matter
    // what the real ringer is (verified on Android 17 / Pixel: internal
    // NORMAL, external SILENT). These tests recreate that masked view.

    /** What the phone looks like to an app while Do Not Disturb is active. */
    private fun simulateDnd(
        filter: Int = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(filter)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    @Test
    fun `an active dnd mode alone does not read as quiet`() {
        simulateDnd()

        assertTrue(controller.isDndActive)
        assertFalse(controller.isQuiet)
    }

    @Test
    fun `every dnd filter masks the ringer the same way`() {
        for (filter in intArrayOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE
        )) {
            simulateDnd(filter)
            assertFalse("filter $filter must not read as quiet", controller.isQuiet)
        }
    }

    @Test
    fun `an unknown interruption filter trusts the readable ringer`() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        )
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        assertFalse(controller.isDndActive)
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `a hush engaged before dnd stays quiet while dnd is active`() {
        controller.goQuiet()

        simulateDnd()

        assertTrue(controller.isQuiet)
    }

    @Test
    fun `a volume-key hush observed before dnd stays quiet during dnd`() {
        // The user flips to vibrate with the volume keys; any surface reading
        // the state afterwards (tile, widget, app) records what it saw.
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(controller.isQuiet)

        simulateDnd()

        assertTrue(controller.isQuiet)
    }

    @Test
    fun `goQuiet during dnd leaves the ringer alone but reports quiet`() {
        simulateDnd()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        // No ringer write: one would make AudioService exit the user's DND.
        // hushRinger is VIBRATE, so a write would be visible here.
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `restoring during dnd clears the remembered hush`() {
        controller.goQuiet()
        simulateDnd()

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertFalse(controller.isQuiet)
    }

    @Test
    fun `toggle during dnd hushes instead of restoring sound`() {
        // Before the DND fallback, the masked SILENT ringer read as "shhh is
        // on" and a tap here restored sound — killing the user's DND mode.
        simulateDnd()

        val result = controller.toggle()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertTrue(controller.isQuiet)
    }

    /** Wraps the app context so the audio service is [audio] instead. */
    private fun controllerWith(audio: AudioManager): QuietModeController {
        val wrapper = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.AUDIO_SERVICE) audio else super.getSystemService(name)
        }
        return QuietModeController(wrapper, ShhhSettings(context))
    }

    /**
     * A controller whose AudioManager refuses every volume write with a
     * [SecurityException] — what Android does when the change is not allowed —
     * while ringer-mode changes still go through.
     */
    private fun controllerRefusingVolumeChanges(): QuietModeController {
        val refusing = mockk<AudioManager>(relaxed = true)
        every { refusing.getStreamVolume(any()) } returns 5
        every { refusing.getStreamMaxVolume(any()) } returns 15
        every { refusing.ringerMode } returns AudioManager.RINGER_MODE_VIBRATE
        every { refusing.setStreamVolume(any(), any(), any()) } throws
            SecurityException("volume change denied")
        return controllerWith(refusing)
    }

    /** The mirror case: the ringer write is refused, so nothing is applied. */
    private fun controllerRefusingRingerChanges(): QuietModeController {
        val refusing = mockk<AudioManager>(relaxed = true)
        every { refusing.getStreamVolume(any()) } returns 5
        every { refusing.getStreamMaxVolume(any()) } returns 15
        every { refusing.ringerMode } returns AudioManager.RINGER_MODE_NORMAL
        every { refusing.ringerMode = any() } throws SecurityException("ringer change denied")
        return controllerWith(refusing)
    }

    // The ringer is what defines quiet mode, so its outcome alone decides the
    // result. Reporting failure for a refused media-volume write would tell
    // HushManager that nothing happened, and it would then skip disarming the
    // timer and refreshing the tile/widget while the phone had in fact flipped.

    @Test
    fun `goQuiet succeeds when only the media volume is refused`() {
        assertEquals(
            QuietModeController.Result.Success(quiet = true),
            controllerRefusingVolumeChanges().goQuiet()
        )
    }

    @Test
    fun `restoreSound succeeds when only the media volume is refused`() {
        assertEquals(
            QuietModeController.Result.Success(quiet = false),
            controllerRefusingVolumeChanges().restoreSound()
        )
    }

    @Test
    fun `goQuiet reports NeedsDndAccess when the ringer change is refused`() {
        assertEquals(
            QuietModeController.Result.NeedsDndAccess,
            controllerRefusingRingerChanges().goQuiet()
        )
    }

    @Test
    fun `restoreSound reports NeedsDndAccess when the ringer change is refused`() {
        assertEquals(
            QuietModeController.Result.NeedsDndAccess,
            controllerRefusingRingerChanges().restoreSound()
        )
    }

    @Test
    fun `restoreMediaVolumeOnly reports failure when the volume change is refused`() {
        assertFalse(controllerRefusingVolumeChanges().restoreMediaVolumeOnly())
    }

    // AudioService applies ringer writes asynchronously. The controller must
    // not return until the new mode is readable, because HushManager refreshes
    // the tile/widget immediately afterwards — recomposing against the old
    // mode freezes those surfaces on a stale state (seen on a Pixel 10 /
    // Android 17: widget stuck on "Hushed" after a successful un-hush).

    @Test
    fun `restoreSound waits until the ringer write is readable before returning`() {
        val lagging = mockk<AudioManager>(relaxed = true)
        every { lagging.getStreamVolume(any()) } returns 5
        every { lagging.getStreamMaxVolume(any()) } returns 15
        // Two stale reads, then AudioService catches up.
        every { lagging.ringerMode } returnsMany listOf(
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_NORMAL
        )

        val result = controllerWith(lagging).restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        verify(atLeast = 3) { lagging.ringerMode }
    }

    @Test
    fun `restoreSound outlasts a lagging dnd unmask before clearing the remembered hush`() {
        // Exiting DND un-masks the readable ringer asynchronously. The settle
        // wait must cover that window, so that surface refreshes triggered
        // right after restoreSound cannot re-derive "quiet" from a
        // still-masked SILENT read and poison the remembered state.
        ShhhSettings(context).lastKnownQuiet = true
        val lagging = mockk<AudioManager>(relaxed = true)
        every { lagging.getStreamVolume(any()) } returns 5
        every { lagging.getStreamMaxVolume(any()) } returns 15
        // Still masked for several reads after the NORMAL write, then unmasked.
        every { lagging.ringerMode } returnsMany listOf(
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_NORMAL
        )
        val controller = controllerWith(lagging)

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        verify(atLeast = 4) { lagging.ringerMode }
        assertFalse(ShhhSettings(context).lastKnownQuiet)
        assertFalse(controller.isQuiet)
    }

    @Test
    fun `settle wait is bounded when the ringer never reflects the write`() {
        val stuck = mockk<AudioManager>(relaxed = true)
        every { stuck.getStreamVolume(any()) } returns 5
        every { stuck.getStreamMaxVolume(any()) } returns 15
        // Never reaches VIBRATE — the wait must give up, not hang.
        every { stuck.ringerMode } returns AudioManager.RINGER_MODE_NORMAL

        val before = SystemClock.uptimeMillis()
        val result = controllerWith(stuck).goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertTrue(SystemClock.uptimeMillis() - before <= 1_000)
    }
}
