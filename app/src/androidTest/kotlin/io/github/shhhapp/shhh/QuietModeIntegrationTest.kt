package io.github.shhhapp.shhh

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Integration tests against the REAL AudioManager on a device/emulator.
 * Do Not Disturb access is granted through the instrumentation shell identity,
 * the same switch a user flips in Settings — no root involved.
 */
@RunWith(AndroidJUnit4::class)
class QuietModeIntegrationTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var controller: QuietModeController

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
                // Drain so the command fully completes before returning.
                java.io.FileInputStream(fd.fileDescriptor).readBytes()
            }
        }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        controller = QuietModeController(context)
        assertTrue("DND access was not granted", controller.hasDndAccess)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @After
    fun tearDown() {
        // Leave the device sounding normal for the next test/user.
        shell("cmd notification set_dnd off")
        waitFor("DND off") { !controller.isDndActive }
        controller.restoreSound()
    }

    /** Zen changes propagate asynchronously; poll instead of sleeping blind. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timed out waiting for: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    private fun enableDnd() {
        shell("cmd notification set_dnd priority")
        waitFor("DND active") { controller.isDndActive }
    }

    @Test
    fun goQuiet_setsVibrateAndMutesMedia_onRealDevice() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun fullToggleCycle_restoresPreviousVolume_onRealDevice() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)

        controller.toggle()
        assertTrue(controller.isQuiet)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        controller.toggle()
        assertFalse(controller.isQuiet)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(5, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    // ---- Do Not Disturb ----

    @Test
    fun dnd_masksTheRingerAsSilent_thePlatformBehaviorTheFallbackExistsFor() {
        // Pins the premise: while zen is active, AudioService reports SILENT
        // to apps even though the real ringer is untouched. If this ever
        // fails, the DND fallback in QuietModeController can be revisited.
        // The mask lands asynchronously a moment after the filter flips, so
        // wait for it rather than asserting a race.
        enableDnd()

        waitFor("ringer masked by DND") {
            audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
        }
    }

    @Test
    fun dndAlone_doesNotReadAsQuiet_onRealDevice() {
        enableDnd()

        assertFalse(controller.isQuiet)
    }

    @Test
    fun hushEngagedBeforeDnd_staysQuietDuringDnd_onRealDevice() {
        controller.goQuiet()

        enableDnd()

        assertTrue(controller.isQuiet)
    }

    @Test
    fun hushDuringDnd_reportsQuietAndKeepsDndAlive_onRealDevice() {
        enableDnd()

        val result = controller.goQuiet()

        assertEquals(QuietModeController.Result.Success(quiet = true), result)
        assertTrue(controller.isQuiet)
        // A ringer write here would make AudioService exit the user's DND.
        assertTrue("hushing must not end the user's DND mode", controller.isDndActive)
    }

    @Test
    fun restoreDuringDnd_bringsSoundBack_onRealDevice() {
        controller.goQuiet()
        enableDnd()

        val result = controller.restoreSound()

        assertEquals(QuietModeController.Result.Success(quiet = false), result)
        assertFalse(controller.isQuiet)
        // Restoring sound during DND deliberately exits zen — a phone cannot
        // be audible while a DND mode silences it.
        waitFor("DND ended by the restore") { !controller.isDndActive }
        waitFor("ringer back to normal") {
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
        }
    }
}
