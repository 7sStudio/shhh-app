package io.github.shhhapp.shhh.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** One published release on GitHub, reduced to what the updater needs. */
data class ReleaseInfo(
    /** Version with any leading "v" stripped, e.g. "1.3.0". */
    val versionName: String,
    /** Release notes (GitHub release body), possibly empty. */
    val notes: String,
    /** Direct download URL of the release's APK asset. */
    val apkUrl: String,
    /** Asset size in bytes; 0 when GitHub didn't report one. */
    val apkSizeBytes: Long
)

/** Outcome of asking GitHub for the latest release. */
sealed interface CheckResult {
    /** The installed version is the newest published one. */
    data object UpToDate : CheckResult

    /** A newer release with an APK asset exists. */
    data class UpdateAvailable(val release: ReleaseInfo) : CheckResult

    /** Network failure, non-200 response, or a response we couldn't read. */
    data object Error : CheckResult
}

/**
 * Talks to the GitHub releases API — the app's only network code.
 * [endpoint] is the "latest release" URL; tests point it at a local server.
 */
class UpdateChecker(private val endpoint: String = LATEST_RELEASE_URL) {

    /** Fetches the latest release and compares it against [currentVersion]. */
    suspend fun check(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        val body = try {
            fetch(endpoint)
        } catch (_: IOException) {
            return@withContext CheckResult.Error
        } ?: return@withContext CheckResult.Error
        val release = parseLatestRelease(body) ?: return@withContext CheckResult.Error
        if (isNewer(release.versionName, currentVersion)) {
            CheckResult.UpdateAvailable(release)
        } else {
            CheckResult.UpToDate
        }
    }

    /**
     * Streams the release APK into [destination]. [onProgress] receives percent
     * complete, or null when the total size is unknown. Returns false on any
     * failure (the partial file is deleted).
     */
    suspend fun download(
        release: ReleaseInfo,
        destination: File,
        onProgress: (Int?) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = open(release.apkUrl)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
                val total = release.apkSizeBytes
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(
                                if (total > 0L) {
                                    (copied * 100 / total).toInt().coerceAtMost(100)
                                } else {
                                    null
                                }
                            )
                        }
                        // A short read against a known size is a broken download.
                        if (total > 0L && copied < total) throw IOException("truncated")
                    }
                }
                true
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            destination.delete()
            false
        }
    }

    /** Returns the response body, or null on a non-200 status. */
    private fun fetch(url: String): String? {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "shhh-app-updater")
        }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/7sStudio/shhh-app/releases/latest"
        private const val TIMEOUT_MILLIS = 10_000
        private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024
        private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L

        /** The automatic check runs at most once per 24 hours; 0 means never ran. */
        fun isCheckDue(lastCheckMillis: Long, nowMillis: Long): Boolean =
            lastCheckMillis == 0L || nowMillis - lastCheckMillis >= CHECK_INTERVAL_MILLIS

        /**
         * Numeric dotted-version comparison: "1.10.0" > "1.9.9", a leading
         * "v" is ignored, and non-numeric suffixes ("1.3.0-beta") are ignored.
         */
        fun isNewer(remote: String, local: String): Boolean {
            val remoteParts = numericParts(remote)
            val localParts = numericParts(local)
            for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r != l) return r > l
            }
            return false
        }

        private fun numericParts(version: String): List<Int> =
            version.removePrefix("v").removePrefix("V")
                .split('.')
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        /**
         * Extracts the release from GitHub's "latest release" JSON. Null when
         * the payload is malformed or the release carries no APK asset.
         */
        fun parseLatestRelease(json: String): ReleaseInfo? = try {
            val release = JSONObject(json)
            val tag = release.getString("tag_name")
            val assets = release.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        apkSize = asset.optLong("size")
                        break
                    }
                }
            }
            apkUrl?.let {
                ReleaseInfo(
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    notes = release.optString("body"),
                    apkUrl = it,
                    apkSizeBytes = apkSize
                )
            }
        } catch (_: JSONException) {
            null
        }
    }
}
