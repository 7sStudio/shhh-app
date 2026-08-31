package io.github.shhhapp.shhh.smoke

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds the release app's widget exactly the way a launcher does and waits for
 * Glance to deliver RemoteViews. This walks the full production pipeline —
 * receiver → WorkManager worker → Glance session → RemoteViews — which no
 * debug-based test covers (they compose the widget in-process, skipping
 * WorkManager entirely).
 *
 * That pipeline is exactly where release builds have broken before: R8 once
 * stripped the reflectively-instantiated `OverwritingInputMerger` constructor,
 * every update worker died, and each placed widget showed "Can't load widget"
 * while all debug-based tests stayed green. If the views never arrive here,
 * that class of bug is back.
 */
@RunWith(AndroidJUnit4::class)
class WidgetPipelineSmokeTest {

    private lateinit var context: Context
    private lateinit var host: CapturingHost
    private var widgetId = -1

    /** Widget host (like a launcher's) whose views report every RemoteViews delivery. */
    private class CapturingHost(context: Context) : AppWidgetHost(context, HOST_ID) {
        val delivered = CountDownLatch(1)

        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?
        ): AppWidgetHostView = object : AppWidgetHostView(context) {
            override fun updateAppWidget(remoteViews: RemoteViews?) {
                super.updateAppWidget(remoteViews)
                if (remoteViews != null) delivered.countDown()
            }
        }
    }

    @Before
    fun setUpHost() {
        // This module self-instruments, so this is the smoke app's own context —
        // it plays the launcher, hosting the release app's widget cross-app.
        context = InstrumentationRegistry.getInstrumentation().context
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        host = CapturingHost(context)
    }

    @After
    fun unbindWidget() {
        if (widgetId != -1) host.deleteAppWidgetId(widgetId)
        host.stopListening()
        shell("appwidget revokebind --package ${context.packageName} --user 0")
    }

    /** Binds [receiver] the way a launcher would and waits for its RemoteViews. */
    private fun assertBoundWidgetReceivesRemoteViews(receiver: String) {
        widgetId = host.allocateAppWidgetId()
        val bound = AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(
            widgetId,
            ComponentName(APP_PACKAGE, "$APP_PACKAGE.widget.$receiver")
        )
        assertTrue("could not bind $receiver (grantbind failed?)", bound)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // startListening triggers APPWIDGET_UPDATE to the provider; the host
        // view must exist on the main thread to receive the resulting views.
        instrumentation.runOnMainSync {
            host.startListening()
            host.createView(context, widgetId, null)
        }

        assertTrue(
            "Glance never delivered RemoteViews for the widget bound via " +
                "$receiver — the update worker most likely crashed in the " +
                "release app (check R8 keep rules; a launcher would show " +
                "\"Can't load widget\")",
            host.delivered.await(60, TimeUnit.SECONDS)
        )
    }

    @Test
    fun boundCardWidgetReceivesRemoteViewsFromGlance() {
        assertBoundWidgetReceivesRemoteViews("ShhhWidgetReceiver")
    }

    @Test
    fun boundTransparentWidgetReceivesRemoteViewsFromGlance() {
        assertBoundWidgetReceivesRemoteViews("ShhhTransparentWidgetReceiver")
    }

    private companion object {
        const val APP_PACKAGE = "io.github.shhhapp.shhh"
        const val HOST_ID = 0x5EED

        fun shell(command: String) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand(command).use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).readBytes()
            }
        }
    }
}
