package io.github.shhhapp.shhh.tile

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.schedule.HushService
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

/** Replacement shadow for a tile service that has no attached tile yet. */
@Implements(TileService::class)
class ShadowDetachedTileService {
    @Implementation
    protected fun getQsTile(): Tile? = null
}

/**
 * Robolectric's ShadowTileService is not a ShadowService, so letting
 * `super.onDestroy()` reach Service.onDestroy crashes on a shadow cast.
 * Production Service.onDestroy is a no-op anyway; swallowing it here lets the
 * unbind-kill path of [ShhhTileService.onDestroy] run.
 */
@Implements(TileService::class)
class ShadowDestroyableTileService : org.robolectric.shadows.ShadowTileService() {
    @Implementation
    protected fun onDestroy() = Unit
}

/**
 * The Quick Settings tile is a background surface, so every path is asserted
 * against the phone's real volume state and the real intents it hands back to
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
        // The listening-instance hook is static and Robolectric shares statics
        // across the methods of one class; a leftover registration would let a
        // refresh land on the previous test's tile.
        ShhhTileService.listeningInstance = null
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        settings = ShhhSettings(context)
        settings.timerEndMillis = 0L
        settings.previousMediaVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.previousRingVolume = ShhhSettings.NO_SAVED_VOLUME
        settings.lastKnownQuiet = false
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        settings.liveCountdownEnabled = false

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        ring = 3
        media = 8
    }

    private var ring: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_RING, value, 0)

    private var media: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        set(value) = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)

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

    /** What the phone looks like to an app while a zen mode is running. */
    private fun simulateDnd(
        filter: Int = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    ) {
        notificationManager.setInterruptionFilter(filter)
        // The legacy ringer mode every app reads is masked to SILENT under any
        // zen; the tile must not mistake that for a hush.
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
    }

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
        ring = 0
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

    /** The service the tile hands every sound change to, or null if it started none. */
    private val startedService: Intent?
        get() = shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .nextStartedService

    @Test
    fun `clicking hands the toggle to the foreground service and never collapses`() {
        // The reported bug: the tile used to bounce through ToggleActivity, and
        // a tile can only start an activity via startActivityAndCollapse, which
        // closes the shade by definition. A toggle tile must behave like Wi-Fi
        // or the torch and leave the panel open, so the work goes to the
        // momentary foreground service instead — no activity, no collapse.
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        service.onStartListening()

        service.onClick()

        val started = startedService
        assertEquals(HushService::class.java.name, started?.component?.className)
        assertEquals(HushService.ACTION_TOGGLE, started?.action)
        assertTrue("the shade must never be collapsed by a toggle", collapsed.isEmpty())
        assertNull("no activity may be started either", startedActivity)
    }

    @Test
    fun `clicking a hushed phone hands off the same way`() {
        settings.previousMediaVolume = 7
        settings.previousRingVolume = 4
        ring = 0
        media = 0
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        service.onStartListening()

        service.onClick()

        assertEquals(HushService.ACTION_TOGGLE, startedService?.action)
        assertTrue(collapsed.isEmpty())
    }

    // ---- the optimistic flip ----

    @Test
    fun `clicking flips the tile optimistically before the service has done anything`() {
        // Wi-Fi and the torch flip the moment they are tapped; leaving the old
        // state up for the service round-trip reads as a ~1s lag. Robolectric
        // never runs the started service, so any flip seen here happened
        // optimistically in onClick.
        val service = buildService()
        service.onStartListening()
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)

        service.onClick()

        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        assertEquals("On", service.qsTile.subtitle.toString())
        assertEquals("the flip is optimistic, not a volume write", 3, ring)
        assertEquals(HushService.ACTION_TOGGLE, startedService?.action)
    }

    @Test
    fun `clicking a hushed phone optimistically shows the inactive tile`() {
        ring = 0
        val service = buildService()
        service.onStartListening()
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)

        service.onClick()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
        assertEquals("Off", service.qsTile.subtitle.toString())
    }

    @Test
    fun `the trampoline fallback does not fake a flip`() {
        // When the service start is refused the toggle goes through the
        // activity, which may itself fail or be dropped — the tile must keep
        // showing reality rather than an outcome nothing has produced yet.
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        service.onStartListening()
        val refusing = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }
        ReflectionHelpers.setField(ContextWrapper::class.java, service, "mBase", refusing)

        service.onClick()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
        assertEquals(1, collapsed.size)
    }

    @Test
    fun `the tile never writes volume from its own background context`() {
        // Measured on Android 17: a write made here is silently dropped by
        // audio hardening, and the dropped write is what forced the old
        // activity fallback. The tile must not attempt one at all.
        val service = buildService()
        service.onStartListening()

        service.onClick()

        assertEquals("the tile must not touch the ring volume", 3, ring)
        assertEquals("the tile must not touch the media volume", 8, media)
    }

    // ---- the four (dnd access, zen running) combinations ----

    @Test
    fun `clicking without DND access but with no zen running still hands off`() {
        // Volume writes need no permission outside a zen mode, so there is
        // nothing here to send the user to the app for.
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        service.onStartListening()

        service.onClick()

        assertEquals(HushService.ACTION_TOGGLE, startedService?.action)
        assertTrue("nothing needed launching", collapsed.isEmpty())
    }

    @Test
    fun `clicking with a zen running and no DND access opens the app`() {
        // Android refuses every ring-volume write in this state; the app is
        // where the grant can be offered. Opening an app is the one thing a
        // tile is allowed to collapse the shade for.
        simulateDnd()
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)

        service.onClick()

        assertEquals("nothing may be touched from the tile", 3, ring)
        assertNull("no sound change may be attempted", startedService)
        val launched = shadowOf(collapsed.single()).savedIntent
        assertEquals(MainActivity::class.java.name, launched.component?.className)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun `clicking under every zen filter hands off without collapsing`() {
        for (filter in intArrayOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE
        )) {
            simulateDnd(filter)
            val service = buildService()
            val collapsed = recordCollapsedActivities(service)

            service.onClick()

            assertEquals(
                "filter $filter",
                HushService.ACTION_TOGGLE,
                startedService?.action
            )
            assertTrue("filter $filter collapsed the shade", collapsed.isEmpty())
        }
    }

    @Test
    fun `an OEM that refuses the service start falls back to the trampoline`() {
        // Some builds refuse a foreground-service start from a background
        // context. The tile must not crash SystemUI's binding over it; falling
        // back costs the user the open shade, but not the toggle itself.
        val service = buildService()
        val collapsed = recordCollapsedActivities(service)
        val refusing = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }
        ReflectionHelpers.setField(ContextWrapper::class.java, service, "mBase", refusing)

        service.onClick()

        val launched = shadowOf(collapsed.single()).savedIntent
        assertEquals(ToggleActivity::class.java.name, launched.component?.className)
    }

    // ---- Do Not Disturb reflected in the tile state ----

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

    // ---- live refresh while the shade is open ----

    @Test
    fun `a volume change while the shade is open refreshes the tile`() {
        val service = buildService()
        service.onStartListening()
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)

        ring = 0
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

    // ---- the in-process refresh push ----
    //
    // The reported v1.4.0 bug: with a Do Not Disturb mode running, a tile tap
    // hushed the phone but the tile kept showing the old state until the shade
    // was closed and reopened. Nothing could refresh it: requestListeningState
    // is a documented no-op for a passive tile, and RINGER_MODE_CHANGED_ACTION
    // never fires under a zen (it reflects the external ringer mode, which
    // every zen pins at SILENT). The direct in-process push is the only
    // channel, so these tests drive it with no broadcast in sight.

    @Test
    fun `a hush applied by the manager refreshes a listening tile without any broadcast`() {
        val service = buildService()
        service.onStartListening()
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)

        HushManager(context).hush()

        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        assertEquals("On", service.qsTile.subtitle.toString())
    }

    @Test
    fun `a toggle while a dnd mode runs still refreshes the listening tile`() {
        // The exact reported scenario, minus the broadcast the platform never
        // sends in it.
        simulateDnd()
        val service = buildService()
        service.onStartListening()
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)

        HushManager(context).toggle()

        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
    }

    @Test
    fun `a refresh request with no listening tile is a no-op`() {
        // Transitions from the app, shortcuts or alarms run with the shade
        // closed most of the time; the push must land nowhere, quietly.
        ShhhTileService.requestTileRefresh()
    }

    @Test
    fun `a refresh request after the shade closes does not touch the tile`() {
        val service = buildService()
        service.onStartListening()
        service.onStopListening()

        ring = 0
        ShhhTileService.requestTileRefresh()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }

    @Test
    @Config(shadows = [ShadowDestroyableTileService::class])
    fun `a refresh request after an unbind kill does not touch the tile`() {
        val service = buildService()
        service.onStartListening()
        service.onDestroy()

        ring = 0
        ShhhTileService.requestTileRefresh()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }

    @Test
    fun `a stale stop from a replaced instance does not unhook the live one`() {
        // The system can create a new tile service before delivering the old
        // one's onStopListening; the identity check must keep the live
        // instance registered.
        val old = buildService()
        old.onStartListening()
        val live = buildService()
        live.onStartListening()

        old.onStopListening()
        ring = 0
        ShhhTileService.requestTileRefresh()

        assertEquals(Tile.STATE_ACTIVE, live.qsTile.state)
    }

    @Test
    fun `the receiver is dropped when the shade closes`() {
        val service = buildService()
        service.onStartListening()
        service.onStopListening()

        ring = 0
        context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }

    @Test
    @Config(shadows = [ShadowDestroyableTileService::class])
    fun `an unbind kill without onStopListening still drops the receiver`() {
        val service = buildService()
        service.onStartListening()

        // The system may unbind-kill the service without onStopListening first.
        service.onDestroy()

        ring = 0
        context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
    }

    @Test
    fun `repeated listening cycles neither double-register nor crash`() {
        val service = buildService()
        service.onStartListening()
        service.onStopListening()
        service.onStopListening()

        service.onStartListening()
        service.onStartListening()

        ring = 0
        context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        service.onStopListening()
    }

    // ---- background writes that do not take ----


}
