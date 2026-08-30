package io.github.shhhapp.shhh.smoke

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-starts the minified release app and waits for its home screen. Catches
 * R8 breakage on the startup path — it happened once before, when R8 full
 * mode stripped WorkManager's reflectively-loaded `WorkDatabase_Impl` and the
 * release build crashed on first launch while every debug-based test passed.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartSmokeTest {

    @Test
    fun releaseAppColdStartsToItsHomeScreen() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("am force-stop $APP_PACKAGE")

        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
        assertNotNull("release app is not installed", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        assertTrue(
            "release app did not reach its home screen — likely a startup " +
                "crash only present in the minified build (check logcat and " +
                "R8 keep rules)",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE).depth(0)), 15_000) &&
                device.wait(Until.hasObject(By.text("One tap to hush your phone")), 15_000)
        )
    }

    private companion object {
        const val APP_PACKAGE = "io.github.shhhapp.shhh"
    }
}
