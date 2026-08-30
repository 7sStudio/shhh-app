package io.github.shhhapp.shhh

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.media.AudioManager
import android.net.Uri
import android.service.notification.Condition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.shhhapp.shhh.core.HushManager
import io.github.shhhapp.shhh.core.QuietModeController
import io.github.shhhapp.shhh.core.ShhhSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bedtime / Sleep mode on a Pixel is not the manual DND toggle: it is an
 * [AutomaticZenRule] of [AutomaticZenRule.TYPE_BEDTIME] owned by Digital
 * Wellbeing, activated by a condition. These tests build and activate a real
 * bedtime-type rule through the same platform API, so shhh's behavior is
 * verified against the exact mechanism Sleep mode uses — on the real
 * NotificationManager and AudioManager, not shadows.
 *
 * An automatic rule is the harder case for the reported bug: a manual DND that
 * shhh switches off can be switched back on, but an automatic rule that the
 * platform *snoozes* stays off for the rest of the night.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class BedtimeModeIntegrationTest {

    private lateinit var device: DeviceAudio
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var settings: ShhhSettings
    private lateinit var controller: QuietModeController
    private var ruleId: String? = null

    @Before
    fun setUp() {
        device = DeviceAudio()
        audioManager = device.audioManager
        notificationManager = device.notificationManager
        settings = ShhhSettings(device.context)
        controller = QuietModeController(device.context, settings)
        device.resetShhhState(settings)
        // A rule left behind by a crashed run would keep zen on for every test
        // that follows, so clear ours before touching anything else — and the
        // grant has to come first, because reading the rules needs it.
        removeTestRules()
        device.resetToAudibleNoZen()
        assertTrue("DND access was not granted", controller.hasDndAccess)
    }

    @After
    fun tearDown() {
        ruleId = null
        removeTestRules()
        device.resetToAudibleNoZen()
        device.resetShhhState(settings)
    }

    /**
     * Drops every rule these tests own, whoever created it. Re-asserts the Do
     * Not Disturb grant first: reading or removing a zen rule needs it, other
     * classes in this suite revoke it on purpose, and a reinstall between runs
     * clears it — so it can never be assumed just because setUp asked for it.
     */
    private fun removeTestRules() {
        device.grantDndAccess()
        notificationManager.automaticZenRules
            .filterValues { it.name == RULE_NAME }
            .keys
            .forEach { notificationManager.removeAutomaticZenRule(it) }
    }

    /** Builds and activates a bedtime-type rule the way Sleep mode does. */
    private fun startBedtimeMode(
        filter: Int = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    ) {
        val conditionId = Uri.parse("condition://${device.context.packageName}/test-bedtime")
        // TYPE_BEDTIME itself is reserved for the Wellbeing package ("Only
        // the 'Wellbeing' package can use AutomaticZenRules with
        // TYPE_BEDTIME"), but the type is UI metadata only — the zen
        // activation path and PRIORITY filter below are exactly what the real
        // Bedtime rule uses (verified against its dump on this emulator).
        val rule = AutomaticZenRule.Builder(RULE_NAME, conditionId)
            .setType(AutomaticZenRule.TYPE_SCHEDULE_TIME)
            .setInterruptionFilter(filter)
            .setManualInvocationAllowed(true)
            // The platform insists on an owner surface even for a test rule.
            .setConfigurationActivity(
                android.content.ComponentName(device.context, MainActivity::class.java)
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

    /** [Condition.STATE_TRUE] while the rule is the one holding zen on. */
    private fun bedtimeRuleState(): Int =
        notificationManager.getAutomaticZenRuleState(ruleId!!)

    @Test
    fun bedtimeRule_masksTheRingerAsSilent_thePlatformBehaviorTheFallbackExistsFor() {
        startBedtimeMode()

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            notificationManager.currentInterruptionFilter
        )
        // ...while the ring VOLUME keeps telling the truth, which is what shhh
        // reads instead of the masked ringer mode.
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
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
        assertEquals(0, device.ringVolume)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        // A ringer write here would deactivate the user's Bedtime mode.
        assertTrue("hushing must not end Bedtime mode", controller.isDndActive)
        assertEquals(Condition.STATE_TRUE, bedtimeRuleState())
    }

    @Test
    fun restoreDuringBedtime_bringsSoundBack_andBedtimeSurvives() {
        controller.goQuiet()
        startBedtimeMode()

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertFalse(controller.isQuiet)
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
        // THE REPORTED BUG, inverted. Un-hushing used to call
        // setRingerMode(NORMAL) — AOSP's external ringer path — and
        // ZenModeHelper.onSetRingerModeExternal ended the zen mode, snoozing
        // the user's Bedtime rule for the rest of the night. Sliders only now:
        // the rule has to still be running afterwards, and the phone simply
        // stays as quiet as the user's own Bedtime policy says it should be.
        assertTrue("Bedtime must survive the un-hush", controller.isDndActive)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            notificationManager.currentInterruptionFilter
        )
        assertEquals("the rule must not be snoozed", Condition.STATE_TRUE, bedtimeRuleState())
        // The zen exit used to land a few hundred ms after the restore, so give
        // a late one every chance to show up before declaring the rule intact.
        Thread.sleep(1_500)
        assertTrue(controller.isDndActive)
    }

    @Test
    fun unhushDuringBedtime_leavesNoStaleQuietState() {
        // The full production path: HushManager.unhush also refreshes the
        // widget and tile, and those surfaces read isQuiet WHILE the ringer
        // un-mask is still settling. That concurrent read used to re-poison the
        // remembered hush right after the restore cleared it — seen live on
        // Android 17 as the tile showing ON at the next DND without any hush.
        startBedtimeMode()
        val manager = HushManager(device.context)
        manager.hush()
        assertTrue(controller.isQuiet)

        manager.unhush()

        waitFor("sound restored") { device.ringVolume == DeviceAudio.DEFAULT_RING_VOLUME }
        assertTrue("Bedtime must survive the un-hush", controller.isDndActive)
        // Give every fire-and-forget surface refresh time to land, then make
        // sure none of them flipped the remembered state back.
        Thread.sleep(1_500)
        assertFalse(settings.lastKnownQuiet)
        assertFalse(controller.isQuiet)
        assertTrue(controller.isDndActive)
    }

    @Test
    fun theReportedBug_bedtimeOnHushOnHushOff_leavesBedtimeUntouched() {
        // The user's repro against a real automatic rule, through the shipping
        // entry point: Bedtime running, shhh off, toggle on, toggle off.
        startBedtimeMode()
        val manager = HushManager(device.context)
        val filterBefore = notificationManager.currentInterruptionFilter
        val zenBefore = device.globalZenMode

        assertEquals(QuietModeController.Result.Success(quiet = true), manager.toggle())
        assertTrue(manager.isQuiet)
        assertEquals(0, device.ringVolume)

        assertEquals(QuietModeController.Result.Success(quiet = false), manager.toggle())

        assertFalse(manager.isQuiet)
        assertEquals(DeviceAudio.DEFAULT_RING_VOLUME, device.ringVolume)
        assertEquals(DeviceAudio.DEFAULT_MEDIA_VOLUME, device.mediaVolume)
        assertEquals(
            "Bedtime's interruption filter must be untouched",
            filterBefore,
            notificationManager.currentInterruptionFilter
        )
        assertEquals("zen_mode must be untouched", zenBefore, device.globalZenMode)
        assertEquals("the rule must not be snoozed", Condition.STATE_TRUE, bedtimeRuleState())
        assertFalse("no stale hush left behind", settings.lastKnownQuiet)
    }

    @Test
    fun hushingUnderAnAlarmsOnlyRule_leavesTheUsersRuleRunning() {
        // The same rule machinery, set to "Alarms only" instead of the
        // priority filter real Bedtime uses — the setting a custom schedule or
        // a driving rule can carry. An alarms-only zen pins the INTERNAL ringer
        // mode to SILENT, so writing the ring volume would make AudioService
        // recompute that mode and ZenModeHelper.onSetRingerModeInternal would
        // end the zen the moment it left SILENT, snoozing the user's rule for
        // the rest of its run. QuietModeController skips the ring write here
        // for exactly that reason; the zen already silences the ring anyway.
        startBedtimeMode(filter = NotificationManager.INTERRUPTION_FILTER_ALARMS)
        val zenBefore = device.globalZenMode

        controller.goQuiet()

        Thread.sleep(1_000)
        assertTrue("the alarms-only rule must survive the hush", controller.isDndActive)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            notificationManager.currentInterruptionFilter
        )
        assertEquals(zenBefore, device.globalZenMode)
        assertEquals("the rule must not be snoozed", Condition.STATE_TRUE, bedtimeRuleState())
        assertTrue("the hush is still reported", controller.isQuiet)
    }

    private companion object {
        const val RULE_NAME = "Bedtime (test)"
    }
}
