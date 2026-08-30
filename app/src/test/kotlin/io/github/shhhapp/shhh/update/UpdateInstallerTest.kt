package io.github.shhhapp.shhh.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateInstallerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetFileProviderCache()
    }

    private fun nextIntent(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    @Test
    fun `canInstall mirrors the system's install-unknown-apps toggle`() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        assertFalse(UpdateInstaller.canInstall(context))

        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(UpdateInstaller.canInstall(context))
    }

    @Test
    fun `requestInstallPermission opens the system screen for this package`() {
        UpdateInstaller.requestInstallPermission(context)

        val intent = requireNotNull(nextIntent())
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(context.packageName, intent.data?.schemeSpecificPart)
    }

    @Test
    fun `updateApkFile lives in the cache and its directory exists`() {
        val file = UpdateInstaller.updateApkFile(context)

        assertTrue(file.path.startsWith(context.cacheDir.path))
        assertTrue(file.path.endsWith("updates/shhh-update.apk"))
        assertTrue(requireNotNull(file.parentFile).isDirectory)
    }

    @Test
    fun `install hands the apk to the package installer through the FileProvider`() {
        val apk = UpdateInstaller.updateApkFile(context).apply { writeText("apk") }

        UpdateInstaller.install(context, apk)

        val intent = requireNotNull(nextIntent())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.fileprovider", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
