package io.github.shhhapp.shhh.tile

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.util.ReflectionHelpers

/**
 * Replacement shadow that swallows ringer changes the way Android 16+ audio
 * hardening does for background processes: [android.media.AudioManager.setRingerMode]
 * reports no error but nothing happens. This is exactly the situation the tile's
 * trampoline fallback exists for.
 */
@Implements(AudioManager::class)
class ShadowHardenedAudioManager : ShadowAudioManager() {
    @Implementation
    override fun setRingerMode(mode: Int) {
        if (failRingerChanges) throw SecurityException("Not allowed to change Do Not Disturb state")
        if (!dropRingerChanges) super.setRingerMode(mode)
    }

    companion object {
        /** Ringer changes are accepted and then quietly ignored. */
        @JvmStatic
        var dropRingerChanges: Boolean = false

        /** Ringer changes are refused outright (DND access revoked mid-flight). */
        @JvmStatic
        var failRingerChanges: Boolean = false
    }
}

/** Replacement shadow for a tile service that has no attached tile yet. */
@Implements(TileService::class)
class ShadowDetachedTileService {
    @Implementation
    protected fun getQsTile(): Tile? = null
}

/**
 * The Quick Settings tile is a background surface, so every path is asserted
 * against the phone's real ringer state and the real intents it hands back to
 * the system.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShhhTileServiceTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        ShadowHardenedAudioManager.dropRingerChanges = false
        ShadowHardenedAudioManager.failRingerChanges = false

        settings = ShhhSettings(context)
        settings.timerEndMillis = 0L
        settings.previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.lastKnownQuiet = false
        settings.hushRinger = ShhhSettings.HushRinger.VIBRATE
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        settings.liveCountdownEnabled = false

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
    }

    private fun buildService(): ShhhTileService =
        Robolectric.buildService(ShhhTileService::class.java).create().get()

    /**
     * Robolectric does not shadow `startActivityAndCollapse(PendingIntent)` (only
     * the deprecated Intent overload), and the real implementation forwards to the
     * system's IQSService, which is never bound in a unit test. Standing in a
     * recording proxy lets the SDK 34+ branch run for real.
     */
    private fun recordCollapsedActivities(service: TileService): List<PendingIntent> {
        val recorded = mutableListOf<PendingIntent>()
        val qsService = Class.forName("android.service.quicksettings.IQSService")
        val stub = Proxy.newProxyInstance(qsService.classLoader, arrayOf(qsService)) { _, _, args ->
            args?.filterIsInstance<PendingIntent>()?.forEach { recorded += it }
            null
        }
        ReflectionHelpers.setField(TileService::class.java, service, "mService", stub)
        return recorded
    }

    private val startedActivity: Intent?
        get() = shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .nextStartedActivity

    // ---- onStartListening ----

    @Test
    fun `listening on a loud phone shows the inactive tile`() {
        val service = buildService()

        service.onStartListening()

        val tile = service.qsTile
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertEquals("Shhh", tile.label.toString())
        assertEquals("Off", tile.subtitle.toString())
        assertEquals("Toggle quiet mode", tile.contentDescription.toString())
        assertNotNull(tile.icon)
    }

    @Test
    fun `listening on a hushed phone shows the active tile`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val service = buildService()

        service.onStartListening()

        val tile = service.qsTile
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        assertEquals("On", tile.subtitle.toString())
    }

    @Test
    @Config(shadows = [ShadowDetachedTileService::class])
    fun `listening before a tile is attached does nothing`() {
        val service = buildService()

        service.onStartListening()

        assertNull(service.qsTile)
    }

    // ---- onClick ----

    @Test
    fun `clicking a loud phone hushes it and flips the tile`() {
        val service = buildService()
        service.onStartListening()

        service.onClick()

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        assertEquals("On", service.qsTile.subtitle.toString())
        assertNull(startedActivity)
    }

    @Test
    fun `clicking a hushed phone restores sound and flips the tile back`() {
        settings.previousMediaVolume = 7
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val service = buildService()
        service.onStartListening()

        service.onClick()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(7, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }

    @Test
    fun `clicking without DND access opens the app instead of touching the ringer`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)

        service.onClick()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        val launched = shadowOf(collapsed.single()).savedIntent
        assertEquals(MainActivity::class.java.name, launched.component?.className)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    @Config(shadows = [ShadowHardenedAudioManager::class])
    fun `a silently dropped ringer change retries through the trampoline`() {
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        ShadowHardenedAudioManager.dropRingerChanges = true

        service.onClick()

        // The ringer never moved, so the tile must hand the work to the
        // visible trampoline activity instead of reporting a fake success.
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        val launched = shadowOf(collapsed.single()).savedIntent
        assertEquals(ToggleActivity::class.java.name, launched.component?.className)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    // ---- Do Not Disturb ----
    // While a zen mode is active, AudioService masks the readable ringer to
    // SILENT (verified on Android 17). The tile must not mirror DND as "on",
    // and a tap during DND must hush — not restore, which would make
    // AudioService exit the user's DND mode.

    /** What the phone looks like to an app while Do Not Disturb is active. */
    private fun simulateDnd() {
        notificationManager.setInterruptionFilter(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

    @Test
    fun `an active dnd mode alone leaves the tile off`() {
        simulateDnd()
        val service = buildService()

        service.onStartListening()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
        assertEquals("Off", service.qsTile.subtitle.toString())
    }

    @Test
    fun `the tile stays active when dnd joins an active hush`() {
        QuietModeController(context).goQuiet()
        simulateDnd()
        val service = buildService()

        service.onStartListening()

        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        assertEquals("On", service.qsTile.subtitle.toString())
    }

    @Test
    fun `clicking during dnd always routes through the trampoline`() {
        // Background audio writes are silently dropped while DND is active,
        // and the masked ringer hides the drop from the applied-check — so
        // the tile must not even try; only the visible activity may act.
        simulateDnd()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        service.onStartListening()

        service.onClick()

        val launched = shadowOf(collapsed.single()).savedIntent
        assertEquals(ToggleActivity::class.java.name, launched.component?.className)
        // Nothing was touched from the tile's own (background) context.
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(8, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // ---- live refresh while the shade is open ----

    @Test
    fun `a ringer change while the shade is open refreshes the tile`() {
        val service = buildService()
        service.onStartListening()
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
    }

    @Test
    fun `a dnd change while the shade is open refreshes the tile`() {
        QuietModeController(context).goQuiet()
        val service = buildService()
        service.onStartListening()
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)

        // The user restores sound elsewhere, then a DND change lands: the
        // filter broadcast alone must re-read the state.
        QuietModeController(context).restoreSound()
        context.sendBroadcast(Intent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }

    @Test
    fun `the receiver is dropped when the shade closes`() {
        val service = buildService()
        service.onStartListening()
        service.onStopListening()

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }

    // onDestroy also unregisters defensively, but Robolectric cannot invoke
    // TileService.onDestroy (its shadow is not a ShadowService), so only the
    // stop/start cycles are exercised here.
    @Test
    fun `repeated listening cycles neither double-register nor crash`() {
        val service = buildService()
        service.onStartListening()
        service.onStopListening()
        service.onStopListening()

        service.onStartListening()
        service.onStartListening()

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        service.onStopListening()
    }

    @Test
    @Config(shadows = [ShadowHardenedAudioManager::class])
    fun `a refused ringer change retries through the trampoline`() {
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        // DND access reads as granted but the change itself is refused — the
        // grant can be revoked between the check and the call.
        ShadowHardenedAudioManager.failRingerChanges = true

        service.onClick()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        val launched = shadowOf(collapsed.single()).savedIntent
        assertEquals(ToggleActivity::class.java.name, launched.component?.className)
    }

}
