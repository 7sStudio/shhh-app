package io.github.shhhapp.shhh.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.MainActivity
import io.github.shhhapp.shhh.R
import io.github.shhhapp.shhh.ToggleActivity
import io.github.shhhapp.shhh.core.ShhhSettings
import io.github.shhhapp.shhh.core.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Formats an end time exactly like the notification does. */
private fun shortTime(context: Context, millis: Long): String =
    TimeFormat.epochMillis(context, millis)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CountdownNotifierTest {

    private lateinit var context: Application
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationsEnabled(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings = ShhhSettings(context)
        settings.liveCountdownEnabled = true
    }

    @Test
    fun `canNotify is true when the permission is granted and notifications are on`() {
        assertTrue(CountdownNotifier.canNotify(context))
    }

    @Test
    fun `canNotify is false without the POST_NOTIFICATIONS runtime permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(CountdownNotifier.canNotify(context))
    }

    @Test
    fun `canNotify is false when the user blocked notifications`() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        assertFalse(CountdownNotifier.canNotify(context))
    }

    @Test
    fun `showIfEnabled posts an ongoing countdown ending at the given time`() {
        val end = System.currentTimeMillis() + 30 * 60_000L

        CountdownNotifier.showIfEnabled(context, end)

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        val notification = posted[0]
        assertEquals(
            context.getString(R.string.countdown_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        )
        assertEquals(
            context.getString(R.string.countdown_text, shortTime(context, end)),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        assertEquals(end, notification.`when`)
        assertTrue(
            "countdown must be ongoing",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        )
    }

    @Test
    fun `the countdown opens the app and offers a one-tap restore`() {
        CountdownNotifier.showIfEnabled(context, System.currentTimeMillis() + 60_000L)

        val notification = shadowOf(notificationManager).allNotifications.single()

        val contentIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, contentIntent.component?.className)

        assertEquals(1, notification.actions.size)
        val action = notification.actions[0]
        assertEquals(context.getString(R.string.countdown_action_restore), action.title.toString())
        val restoreIntent = shadowOf(action.actionIntent).savedIntent
        assertEquals(ToggleActivity::class.java.name, restoreIntent.component?.className)
        assertEquals(ToggleActivity.ACTION_UNHUSH, restoreIntent.action)
    }

    @Test
    fun `showIfEnabled posts nothing when the live countdown is switched off`() {
        settings.liveCountdownEnabled = false

        CountdownNotifier.showIfEnabled(context, System.currentTimeMillis() + 60_000L)

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `showIfEnabled posts nothing without notification permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        CountdownNotifier.showIfEnabled(context, System.currentTimeMillis() + 60_000L)

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `cancel removes the countdown`() {
        CountdownNotifier.showIfEnabled(context, System.currentTimeMillis() + 60_000L)
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)

        CountdownNotifier.cancel(context)

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `ensureChannels creates a low countdown channel and a minimal service channel`() {
        CountdownNotifier.ensureChannels(context)

        val countdown =
            notificationManager.getNotificationChannel(CountdownNotifier.CHANNEL_COUNTDOWN)
        assertNotNull(countdown)
        assertEquals(context.getString(R.string.channel_countdown_name), countdown.name)
        assertEquals(
            context.getString(R.string.channel_countdown_description),
            countdown.description
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, countdown.importance)
        assertFalse(countdown.canShowBadge())

        val service = notificationManager.getNotificationChannel(CountdownNotifier.CHANNEL_SERVICE)
        assertNotNull(service)
        assertEquals(context.getString(R.string.channel_service_name), service.name)
        assertEquals(context.getString(R.string.channel_service_description), service.description)
        assertEquals(NotificationManager.IMPORTANCE_MIN, service.importance)
        assertFalse(service.canShowBadge())
    }
}
