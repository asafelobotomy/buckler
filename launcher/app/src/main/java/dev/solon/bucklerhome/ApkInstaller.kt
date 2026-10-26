package dev.solon.bucklerhome

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    private const val TAG = "ApkInstaller"

    fun downloadApk(context: Context, url: String, fileName: String): File? {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val dest = File(dir, fileName.ifBlank { "update.apk" })
        val headers = CoverAccounts.githubAuthHeaders(context).toMutableMap()
        if (url.contains("api.github.com/") && url.contains("/releases/assets/")) {
            headers["Accept"] = "application/octet-stream"
        }
        val code = runCatching { HttpJson.downloadTo(url, dest, headers = headers) }
            .onFailure { Log.w(TAG, "APK download failed", it) }
            .getOrDefault(-1)
        if (code !in 200..299 || dest.length() == 0L) {
            dest.delete()
            return null
        }
        return dest
    }

    fun install(context: Context, apk: File): Boolean {
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            session.openWrite("package", 0, apk.length()).use { out ->
                apk.inputStream().use { inp -> inp.copyTo(out) }
                session.fsync(out)
            }
            val callback = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION
            }
            // Mutable so PackageInstaller can attach EXTRA_STATUS / EXTRA_INTENT.
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
            session.close()
            true
        }.getOrElse { error ->
            Log.w(TAG, "PackageInstaller session failed; falling back to ACTION_VIEW", error)
            // Fallback: ACTION_VIEW via FileProvider
            runCatching {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    context.getString(R.string.file_provider_authority),
                    apk,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}
