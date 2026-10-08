package com.eosoclub.ourhome.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Updates from the server's own app build (the web deploy builds it; Profile
 * on the site and in the app offers it). Download with the signed-in session,
 * check it against the size and SHA-256 the server published, then hand it to
 * Android's installer, which only installs it over this app when it's signed
 * with the same key.
 */
object AppUpdate {
    private const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * Whether a release is a newer build of this same app. A different app id
     * is a separate install (e.g. a development build), never an "update".
     */
    fun isNewer(releaseAppId: String, releaseCode: Long, myAppId: String, myCode: Long): Boolean =
        releaseAppId == myAppId && releaseCode > myCode

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun isUpdate(context: Context, release: AppRelease): Boolean =
        isNewer(release.appId, release.versionCode, context.packageName, installedVersionCode(context))

    /** Downloads and checks the release; throws with a user-facing message. */
    suspend fun download(context: Context, api: ApiClient, release: AppRelease, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One update at a time: drop anything an earlier attempt left behind.
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "our-home-${release.versionName}.apk")
        api.downloadApp(apk, onProgress)
        val ok = withContext(Dispatchers.IO) {
            apk.length() == release.sizeBytes && sha256(apk).equals(release.sha256, ignoreCase = true)
        }
        if (!ok) {
            apk.delete()
            throw ApiException("The download was incomplete or damaged. Please try again.")
        }
        return apk
    }

    /** Whether the user allowed Our Home to install apps (asked once, in Settings). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens Settings at "Install unknown apps" for Our Home. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands the APK to Android's installer, which asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
