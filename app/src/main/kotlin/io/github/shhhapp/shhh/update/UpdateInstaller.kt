package io.github.shhhapp.shhh.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to Android's package installer. Installing still goes
 * through the full system confirmation UI — this only starts it.
 */
object UpdateInstaller {

    /** Whether the user has granted Shhh the "Install unknown apps" toggle. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Opens the system "Install unknown apps" screen for this app. */
    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Where update downloads land; cache so the system can reclaim it. */
    fun updateApkFile(context: Context): File =
        File(context.cacheDir, "updates/shhh-update.apk").apply { parentFile?.mkdirs() }

    /** Launches the package installer on [apk] via the app's FileProvider. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
