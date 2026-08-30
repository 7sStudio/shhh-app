package io.github.shhhapp.shhh.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker() = UpdateChecker(server.url("/latest").toString())

    private fun releaseJson(
        tag: String = "v9.9.9",
        body: String = "Fixes things.",
        assetName: String = "shhh-9.9.9.apk",
        assetUrl: String = "https://example.invalid/shhh.apk",
        assetSize: Long = 1234
    ) = """
        {
          "tag_name": "$tag",
          "body": "$body",
          "assets": [
            {"name": "checksums.txt", "browser_download_url": "https://example.invalid/sums", "size": 5},
            {"name": "$assetName", "browser_download_url": "$assetUrl", "size": $assetSize}
          ]
        }
    """.trimIndent()

    // ---- version comparison ----

    @Test
    fun `isNewer compares dotted versions numerically`() {
        assertTrue(UpdateChecker.isNewer("1.2.2", "1.2.1"))
        assertTrue(UpdateChecker.isNewer("1.3.0", "1.2.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.2.1", "1.2.1"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.1"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `isNewer ignores v prefixes and non-numeric suffixes`() {
        assertTrue(UpdateChecker.isNewer("v1.2.2", "1.2.1"))
        assertTrue(UpdateChecker.isNewer("V1.2.2", "v1.2.1"))
        assertFalse(UpdateChecker.isNewer("1.2.1-beta", "1.2.1"))
        assertTrue(UpdateChecker.isNewer("1.3", "1.2.9"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("garbage", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "garbage"))
    }

    // ---- throttle ----

    @Test
    fun `isCheckDue is true after an hour and on first ever check`() {
        val hour = 60 * 60 * 1000L
        assertTrue(UpdateChecker.isCheckDue(lastCheckMillis = 0L, nowMillis = 1L))
        assertTrue(UpdateChecker.isCheckDue(lastCheckMillis = 1_000L, nowMillis = 1_000L + hour))
        assertFalse(
            UpdateChecker.isCheckDue(lastCheckMillis = 1_000L, nowMillis = 1_000L + hour - 1)
        )
    }

    // ---- JSON parsing ----

    @Test
    fun `parseLatestRelease extracts version, notes and the apk asset`() {
        val release = UpdateChecker.parseLatestRelease(releaseJson())

        assertEquals("9.9.9", release?.versionName)
        assertEquals("Fixes things.", release?.notes)
        assertEquals("https://example.invalid/shhh.apk", release?.apkUrl)
        assertEquals(1234L, release?.apkSizeBytes)
    }

    @Test
    fun `parseLatestRelease tolerates a missing body`() {
        val json = """{"tag_name": "v2.0.0", "assets": [
            {"name": "a.apk", "browser_download_url": "https://x.invalid/a.apk"}]}"""

        val release = UpdateChecker.parseLatestRelease(json)

        assertEquals("", release?.notes)
        assertEquals(0L, release?.apkSizeBytes)
    }

    @Test
    fun `parseLatestRelease is null without an apk asset`() {
        assertNull(UpdateChecker.parseLatestRelease("""{"tag_name": "v2.0.0", "assets": []}"""))
        assertNull(UpdateChecker.parseLatestRelease("""{"tag_name": "v2.0.0"}"""))
        assertNull(
            UpdateChecker.parseLatestRelease(
                """{"tag_name": "v2.0.0", "assets": [
                    {"name": "notes.txt", "browser_download_url": "https://x.invalid/n"}]}"""
            )
        )
    }

    @Test
    fun `parseLatestRelease is null on malformed payloads`() {
        assertNull(UpdateChecker.parseLatestRelease("not json at all"))
        assertNull(UpdateChecker.parseLatestRelease("""{"assets": []}"""))
    }

    // ---- check() against a server ----

    @Test
    fun `check reports a newer release`() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson(tag = "v9.9.9")))

        val result = checker().check("1.2.1")

        val available = result as CheckResult.UpdateAvailable
        assertEquals("9.9.9", available.release.versionName)
        val request = server.takeRequest()
        assertEquals("shhh-app-updater", request.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun `check reports up to date for the same or an older release`() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson(tag = "v1.2.1")))
        assertEquals(CheckResult.UpToDate, checker().check("1.2.1"))

        server.enqueue(MockResponse().setBody(releaseJson(tag = "v1.0.0")))
        assertEquals(CheckResult.UpToDate, checker().check("1.2.1"))
    }

    @Test
    fun `check reports an error on a non-200 response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(CheckResult.Error, checker().check("1.2.1"))
    }

    @Test
    fun `check reports an error on an unreadable payload`() = runTest {
        server.enqueue(MockResponse().setBody("<html>rate limited</html>"))

        assertEquals(CheckResult.Error, checker().check("1.2.1"))
    }

    @Test
    fun `check reports an error when the server is unreachable`() = runTest {
        val url = server.url("/latest").toString()
        server.shutdown()

        assertEquals(CheckResult.Error, UpdateChecker(url).check("1.2.1"))
    }

    // ---- download() ----

    private fun release(sizeBytes: Long) = ReleaseInfo(
        versionName = "9.9.9",
        notes = "",
        apkUrl = server.url("/shhh.apk").toString(),
        apkSizeBytes = sizeBytes
    )

    @Test
    fun `download streams the apk and reports rising percentages`() = runTest {
        val payload = "fake apk bytes".repeat(1000)
        server.enqueue(MockResponse().setBody(payload))
        val destination = File(temp.root, "update.apk")
        val seen = mutableListOf<Int?>()

        val ok = checker().download(release(payload.length.toLong()), destination) { seen += it }

        assertTrue(ok)
        assertEquals(payload, destination.readText())
        assertTrue(seen.isNotEmpty())
        assertEquals(100, seen.last())
        assertEquals(seen.filterNotNull().sorted(), seen.filterNotNull())
    }

    @Test
    fun `download reports null progress when the size is unknown`() = runTest {
        server.enqueue(MockResponse().setBody("apk"))
        val destination = File(temp.root, "update.apk")
        val seen = mutableListOf<Int?>()

        val ok = checker().download(release(sizeBytes = 0L), destination) { seen += it }

        assertTrue(ok)
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it == null })
    }

    @Test
    fun `download caps progress at 100 when the asset is larger than declared`() = runTest {
        server.enqueue(MockResponse().setBody("123456789012345678901234567890"))
        val destination = File(temp.root, "update.apk")
        val seen = mutableListOf<Int?>()

        val ok = checker().download(release(sizeBytes = 10L), destination) { seen += it }

        assertTrue(ok)
        assertEquals(100, seen.last())
    }

    @Test
    fun `download fails on a non-200 response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val destination = File(temp.root, "update.apk")

        assertFalse(checker().download(release(sizeBytes = 10L), destination) { })
    }

    @Test
    fun `download fails and deletes the partial file when truncated`() = runTest {
        server.enqueue(MockResponse().setBody("short"))
        val destination = File(temp.root, "update.apk")

        val ok = checker().download(release(sizeBytes = 999_999L), destination) { }

        assertFalse(ok)
        assertFalse(destination.exists())
    }

    @Test
    fun `download fails when the server is unreachable`() = runTest {
        val info = release(sizeBytes = 10L)
        server.shutdown()

        assertFalse(checker().download(info, File(temp.root, "update.apk")) { })
    }
}
