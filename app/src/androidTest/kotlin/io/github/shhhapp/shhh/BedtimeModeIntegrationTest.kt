package io.github.shhhapp.shhh

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.service.notification.Condition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shhhapp.shhh.core.QuietModeController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bedtime / Sleep mode on a Pixel is not the manual DND toggle: it is an
 * [AutomaticZenRule] of [AutomaticZenRule.TYPE_BEDTIME] owned by Digital
 * Wellbeing, activated by a condition. These tests build and activate a real
 * bedtime-type rule through the same platform API, so shhh's behavior is
 * verified against the exact mechanism Sleep mode uses — on the real
 * NotificationManager and AudioManager, not shadows.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class BedtimeModeIntegrationTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var controller: QuietModeController
    private var ruleId: String? = null

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantDndAccess() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            shell("cmd notification allow_dnd ${appContext.packageName}")
        }

        private fun shell(command: String) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand(command).use { fd ->
                fd.checkError()
                java.io.FileInputStream(fd.fileDescriptor).readBytes()
            }
        }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        controller = QuietModeController(context)
        assertTrue("DND access was not granted", controller.hasDndAccess)
        shell("cmd notification set_dnd off")
        waitFor("no DND at start") { !controller.isDndActive }
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @After
    fun tearDown() {
        ruleId?.let { notificationManager.removeAutomaticZenRule(it) }
        ruleId = null
        shell("cmd notification set_dnd off")
        waitFor("DND off") { !controller.isDndActive }
        controller.restoreSound()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out waiting for: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    /** Builds and activates a bedtime-type rule the way Sleep mode does. */
    private fun startBedtimeMode() {
        val conditionId = Uri.parse("condition://${context.packageName}/test-bedtime")
        // TYPE_BEDTIME itself is reserved for the Wellbeing package ("Only
        // the 'Wellbeing' package can use AutomaticZenRules with
        // TYPE_BEDTIME"), but the type is UI metadata only — the zen
        // activation path and PRIORITY filter below are exactly what the real
        // Bedtime rule uses (verified against its dump on this emulator).
        val rule = AutomaticZenRule.Builder("Bedtime (test)", conditionId)
            .setType(AutomaticZenRule.TYPE_SCHEDULE_TIME)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setManualInvocationAllowed(true)
            // The platform insists on an owner surface even for a test rule.
            .setConfigurationActivity(
                android.content.ComponentName(context, MainActivity::class.java)
            )
            .build()
        ruleId = notificationManager.addAutomaticZenRule(rule)
        notificationManager.setAutomaticZenRuleState(
            ruleId!!,
            Condition(conditionId, "asleep", Condition.STATE_TRUE)
        )
        waitFor("bedtime rule active") { controller.isDndActive }
        // AudioService applies the external ringer mask asynchronously, a few
        // hundred ms after the rule activates (observed on Android 17). Wait
        // for it so every test runs against the fully-settled bedtime state.
        waitFor("ringer masked by the bedtime rule") {
            audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
        }
    }

    @Test
    fun bedtimeRule_masksTheRingerAsSilent_thePlatformBehaviorTheFallbackExistsFor() {
        startBedtimeMode()

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun bedtimeAlone_doesNotReadAsQuiet() {
        startBedtimeMode()

        assertFalse(controller.isQuiet)
    }

    @Test
    fun hushEngagedBeforeBedtime_staysQuietDuringBedtime() {
        controller.goQuiet()

        startBedtimeMode()

        assertTrue(controller.isQuiet)
    }

    @Test
    fun hushDuringBedtime_reportsQuietAndKeepsBedtimeAlive() {
        startBedtimeMode()

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertTrue(controller.isQuiet)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        // A ringer write here would deactivate the user's Bedtime mode.
        assertTrue("hushing must not end Bedtime mode", controller.isDndActive)
    }

    @Test
    fun unhushDuringBedtime_leavesNoStaleQuietState() {
        // The full production path: HushManager.unhush also refreshes the
        // widget and tile, and those surfaces read isQuiet WHILE the zen exit
        // and ringer un-mask are still settling. That concurrent read used to
        // re-poison the remembered hush right after the restore cleared it —
        // seen live on Android 17 as the tile showing ON at the next DND
        // without any hush.
        startBedtimeMode()
        val manager = io.github.shhhapp.shhh.core.HushManager(context)
        manager.hush()
        assertTrue(controller.isQuiet)

        manager.unhush()

        waitFor("Bedtime ended by the restore") { !controller.isDndActive }
        waitFor("ringer back to normal") {
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
        }
        // Give every fire-and-forget surface refresh time to land, then make
        // sure none of them flipped the remembered state back.
        Thread.sleep(1_500)
        assertFalse(io.github.shhhapp.shhh.core.ShhhSettings(context).lastKnownQuiet)
        assertFalse(controller.isQuiet)
    }

    @Test
    fun restoreDuringBedtime_bringsSoundBack() {
        controller.goQuiet()
        startBedtimeMode()

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertFalse(controller.isQuiet)
        // Restoring sound deliberately ends the zen mode — a phone cannot be
        // audible while Bedtime silences it. The automatic rule is snoozed.
        waitFor("Bedtime ended by the restore") { !controller.isDndActive }
        waitFor("ringer back to normal") {
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
        }
    }
}
