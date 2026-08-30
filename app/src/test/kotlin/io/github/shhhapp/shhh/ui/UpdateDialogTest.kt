package io.github.shhhapp.shhh.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shhhapp.shhh.update.ReleaseInfo
import io.github.shhhapp.shhh.update.UpdateChecker
import io.github.shhhapp.shhh.update.UpdateInstaller
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var dismissals = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        dismissals = 0
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        UpdateInstaller.updateApkFile(context).delete()
        io.github.shhhapp.shhh.update.resetFileProviderCache()
        drainStartedActivities()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun release(
        notes: String = "New things.",
        sizeBytes: Long = APK_BYTES.length.toLong()
    ) = ReleaseInfo(
        versionName = "9.9.9",
        notes = notes,
        apkUrl = server.url("/shhh.apk").toString(),
        apkSizeBytes = sizeBytes
    )

    private fun setDialog(release: ReleaseInfo = release()) {
        composeTestRule.setContent {
            UpdateDialog(
                release = release,
                checker = UpdateChecker(server.url("/latest").toString()),
                onDismiss = { dismissals++ }
            )
        }
    }

    private fun drainStartedActivities() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        while (shadowOf(app).nextStartedActivity != null) {
            // Clear anything started before the scenario under test.
        }
    }

    private fun nextIntent(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity

    private fun grantInstalls() =
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)

    private fun awaitText(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- chrome ----

    @Test
    fun `shows the version and the release notes`() {
        setDialog()

        composeTestRule.onNodeWithText("Update available").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shhh 9.9.9 is ready to download.").assertIsDisplayed()
        composeTestRule.onNodeWithText("New things.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Download").assertIsDisplayed()
    }

    @Test
    fun `hides the notes block when the release has none`() {
        setDialog(release(notes = " "))

        composeTestRule.onNodeWithText("Update available").assertIsDisplayed()
        composeTestRule.onNodeWithText(" ").assertDoesNotExist()
    }

    @Test
    fun `Later dismisses without touching the network`() {
        setDialog()

        composeTestRule.onNodeWithText("Later").performClick()

        assertEquals(1, dismissals)
        assertEquals(0, server.requestCount)
    }

    // ---- install permission ----

    @Test
    fun `Download without install permission explains and offers the system screen`() {
        setDialog()

        composeTestRule.onNodeWithText("Download").performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, server.requestCount)
        composeTestRule
            .onNodeWithText("Install unknown apps", substring = true)
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Allow installs").performClick()
        composeTestRule.waitForIdle()

        val intent = requireNotNull(nextIntent())
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(context.packageName, intent.data?.schemeSpecificPart)
    }

    @Test
    fun `Allow installs proceeds straight to the download once granted`() {
        setDialog()
        composeTestRule.onNodeWithText("Download").performClick()
        composeTestRule.waitForIdle()

        grantInstalls()
        server.enqueue(MockResponse().setBody(APK_BYTES))
        composeTestRule.onNodeWithText("Allow installs").performClick()
        awaitText("Install")

        assertEquals(1, server.requestCount)
        assertEquals(Intent.ACTION_VIEW, nextIntent()?.action)
    }

    // ---- download and install ----

    @Test
    fun `a successful download launches the installer and offers Install again`() {
        grantInstalls()
        server.enqueue(MockResponse().setBody(APK_BYTES))
        setDialog()

        composeTestRule.onNodeWithText("Download").performClick()
        awaitText("Install")

        assertEquals(APK_BYTES, UpdateInstaller.updateApkFile(context).readText())
        val auto = requireNotNull(nextIntent())
        assertEquals(Intent.ACTION_VIEW, auto.action)
        assertEquals("application/vnd.android.package-archive", auto.type)

        // The user can relaunch the installer if they cancelled it.
        composeTestRule.onNodeWithText("Install").performClick()
        composeTestRule.waitForIdle()

        assertEquals(Intent.ACTION_VIEW, requireNotNull(nextIntent()).action)
    }

    @Test
    fun `download progress is shown with percentages while bytes stream in`() {
        grantInstalls()
        val body = APK_BYTES.repeat(60)
        server.enqueue(
            MockResponse().setBody(body)
                .throttleBody(64, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        setDialog(release(sizeBytes = body.length.toLong()))

        composeTestRule.onNodeWithText("Download").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule
                .onAllNodesWithText("%", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        awaitText("Install")
    }

    @Test
    fun `an unknown size shows indeterminate progress`() {
        grantInstalls()
        server.enqueue(
            MockResponse().setBody(APK_BYTES.repeat(60))
                .throttleBody(64, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        setDialog(release(sizeBytes = 0L))

        composeTestRule.onNodeWithText("Download").performClick()
        // The exact-match string carries no percentage.
        awaitText("Downloading…")

        awaitText("Install")
    }

    @Test
    fun `a failed download offers Retry and Retry can succeed`() {
        grantInstalls()
        server.enqueue(MockResponse().setResponseCode(404))
        setDialog()

        composeTestRule.onNodeWithText("Download").performClick()
        awaitText("Retry")

        composeTestRule
            .onNodeWithText("Download failed", substring = true)
            .assertIsDisplayed()
        assertNull(nextIntent())

        server.enqueue(MockResponse().setBody(APK_BYTES))
        composeTestRule.onNodeWithText("Retry").performClick()
        awaitText("Install")

        assertEquals(Intent.ACTION_VIEW, requireNotNull(nextIntent()).action)
    }

    @Test
    fun `Retry without install permission re-asks for it`() {
        grantInstalls()
        server.enqueue(MockResponse().setResponseCode(404))
        setDialog()
        composeTestRule.onNodeWithText("Download").performClick()
        awaitText("Retry")

        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Allow installs").assertIsDisplayed()
        assertTrue(server.requestCount == 1)
    }

    private companion object {
        const val APK_BYTES = "fake apk payload "
    }
}
