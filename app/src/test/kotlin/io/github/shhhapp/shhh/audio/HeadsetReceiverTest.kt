package io.github.shhhapp.shhh.audio

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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

    private fun connectedIntent() = Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)

    private val postedNotifications: List<Notification>
        get() = shadowOf(notificationManager).allNotifications

    @Test
    fun `an unrelated broadcast is ignored`() {
        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `a disconnection is ignored`() {
        receiver.onReceive(
            context,
            Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        )

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `a connection-state broadcast without a state extra is ignored`() {
        receiver.onReceive(context, Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `nothing happens when the headphones option is off`() {
        settings.headphonesAutoRestore = false

        receiver.onReceive(context, connectedIntent())

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `nothing happens when the phone is not hushed`() {
        audioManager.setStreamVolume(AudioManager.STREAM_RING, 3, 0)

        receiver.onReceive(context, connectedIntent())

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(postedNotifications.isEmpty())
    }

    @Test
    fun `connecting while hushed brings media volume back and keeps the ringer hushed`() {
        receiver.onReceive(context, connectedIntent())

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

        receiver.onReceive(context, connectedIntent())

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

    @Test
    fun `no restore offer is posted without notification permission`() {
        shadowOf(audioManager).setStreamMaxVolume(0)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        receiver.onReceive(context, connectedIntent())

        assertTrue(postedNotifications.isEmpty())
    }
}
