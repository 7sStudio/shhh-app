package io.github.shhhapp.shhh.audio

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.schedule.HushService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HeadsetReceiverTest {

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings
    private val receiver = HeadsetReceiver()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        shadowOf(notificationManager).setNotificationsEnabled(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        settings = ShhhSettings(context)
        settings.headphonesAutoRestore = true
        settings.restoreMode = ShhhSettings.RestoreMode.PREVIOUS
        settings.previousMediaVolume = 6

        shadowOf(audioManager).setStreamMaxVolume(ShadowAudioManager.DEFAULT_MAX_VOLUME)
        // Hushed means "ring volume 0" — the slider shhh reads and writes.
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun connectedIntent(
        action: String = BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
    ) = Intent(action).putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)

    /**
     * Delivers a broadcast the way the phone does end to end: the receiver
     * hands the restore to [HushService], which is then run for real.
     */
    private fun deliver(intent: Intent) {
        receiver.onReceive(context, intent)
        shadowOf(context).nextStartedService?.let { started ->
            Robolectric.buildService(HushService::class.java, started).create().get()
                .handleIntent(started)
        }
    }

    /** A context whose background foreground-service starts are refused. */
    private val refusingServiceStarts by lazy {
        object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }
    }

    private val postedNotifications: List<Notification>
        get() = shadowOf(notificationManager).allNotifications

    @Test
    fun `an unrelated broadcast is ignored`() {
        deliver(Intent(Intent.ACTION_POWER_CONNECTED))

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `a disconnection is ignored`() {
        deliver(
            Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        )

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `a connection-state broadcast without a state extra is ignored`() {
        deliver(Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `nothing happens when the headphones option is off`() {
        settings.headphonesAutoRestore = false

        deliver(connectedIntent())

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `nothing happens when the phone is not hushed`() {
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 3, 0)

        deliver(connectedIntent())

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `connecting while hushed brings media volume back and keeps the ringer hushed`() {
        deliver(connectedIntent())

        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(
            "the ringer must stay hushed",
            0,
            audioManager.getStreamVolume(AudioManager.STREAM_RING)
        )
        assertTrue("no notification is needed when the change went through", postedNotifications.isEmpty())
    }

    @Test
    fun `a dropped volume change falls back to a one-tap restore notification`() {
        // A max volume of 0 makes the write a no-op, exactly like an Android 16+
        // audio-hardening drop: the volume stays at 0 and the restore reports failure.
        shadowOf(audioManager).setStreamMaxVolume(0)

        deliver(connectedIntent())

        val notification = postedNotifications.single()
        assertEquals(
            context.getString(R.string.headphones_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        )
        assertEquals(
            context.getString(R.string.headphones_notification_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        assertTrue(
            "tapping it must dismiss it",
            notification.flags and Notification.FLAG_AUTO_CANCEL != 0
        )

        val tapIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ToggleActivity::class.java.name, tapIntent.component?.className)
        assertEquals(ToggleActivity.ACTION_RESTORE_MEDIA, tapIntent.action)
    }

    // ---- every audio profile, and the route the restore takes ----

    @Test
    fun `media-only and LE Audio earbuds are heard too`() {
        for (action in listOf(
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED
        )) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

            deliver(connectedIntent(action))

            assertEquals(action, 6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }

    @Test
    fun `the restore is handed to the foreground service, not written from the receiver`() {
        // A receiver is a background context: Android 16+ drops its volume
        // writes, so nothing may be written before the service runs.
        receiver.onReceive(context, connectedIntent())

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        val started = shadowOf(context).nextStartedService
        assertEquals(HushService::class.java.name, started?.component?.className)
        assertEquals(HushService.ACTION_HEADPHONES_CONNECTED, started?.action)
    }

    @Test
    fun `nothing is started when the option is off or the phone is not hushed`() {
        settings.headphonesAutoRestore = false
        receiver.onReceive(context, connectedIntent())
        assertEquals(null, shadowOf(context).nextStartedService)

        settings.headphonesAutoRestore = true
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 3, 0)
        receiver.onReceive(context, connectedIntent())
        assertEquals(null, shadowOf(context).nextStartedService)
    }

    @Test
    fun `a refused service start still tries the restore on the spot`() {
        receiver.onReceive(refusingServiceStarts, connectedIntent())

        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `a refused service start whose write is dropped too leaves the one-tap offer`() {
        shadowOf(audioManager).setStreamMaxVolume(0)

        receiver.onReceive(refusingServiceStarts, connectedIntent())

        assertEquals(1, postedNotifications.size)
    }

    @Test
    fun `a second profile announcing the same earbuds changes nothing more`() {
        deliver(connectedIntent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))
        // The user nudges the earbud volume, then A2DP reports in.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)

        deliver(connectedIntent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))

        assertEquals(4, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `the service does nothing if the hush ended before it ran`() {
        receiver.onReceive(context, connectedIntent())
        val started = requireNotNull(shadowOf(context).nextStartedService)
        // Un-hushed in the moment between the broadcast and the service.
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 3, 0)

        Robolectric.buildService(HushService::class.java, started).create().get()
            .handleIntent(started)

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `cancelRestoreOffer takes the posted offer down`() {
        shadowOf(audioManager).setStreamMaxVolume(0)
        deliver(connectedIntent())
        assertEquals(1, postedNotifications.size)

        HeadsetReceiver.cancelRestoreOffer(context)

        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `cancelRestoreOffer with nothing posted is harmless`() {
        HeadsetReceiver.cancelRestoreOffer(context)

        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `a reconnect whose restore goes through retires an earlier offer`() {
        shadowOf(audioManager).setStreamMaxVolume(0)
        deliver(connectedIntent())
        assertEquals(1, postedNotifications.size)

        // This time Android lets the write through.
        shadowOf(audioManager).setStreamMaxVolume(ShadowAudioManager.DEFAULT_MAX_VOLUME)
        deliver(connectedIntent())

        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(
            "media is back, so there is nothing left to offer",
            postedNotifications.isEmpty()
        )
    }

    @Test
    fun `no restore offer is posted without notification permission`() {
        shadowOf(audioManager).setStreamMaxVolume(0)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        deliver(connectedIntent())

        assertTrue(postedNotifications.isEmpty())
    }
}
