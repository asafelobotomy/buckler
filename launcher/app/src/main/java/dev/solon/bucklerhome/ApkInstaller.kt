package dev.solon.bucklerhome

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * One download-and-install at a time: Buckler's own update and bucklerStore's downloads (a font, an
 * icon pack). A Buckler update restarts Buckler, which would cut short a store download or install
 * still under way, and two installs at once only confuse Android's confirmation prompts. Whoever
 * holds the gate says what it is doing, so the other can say what it is waiting for.
 */
object InstallGate {
    @Volatile
    var holder: String? = null
        private set

    /** Take the gate for [what]; null when taken, else what already holds it. */
    @Synchronized
    fun tryEnter(what: String): String? {
        holder?.let { return it }
        holder = what
        return null
    }

    @Synchronized
    fun leave(what: String) {
        if (holder == what) holder = null
    }

    /** Run [block] holding the gate; returns null (without running it) when something else holds it. */
    inline fun <T> withGate(what: String, block: () -> T): Result<T>? {
        if (tryEnter(what) != null) return null
        return try {
            Result.success(block())
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            leave(what)
        }
    }
}

object ApkInstaller {
    private const val TAG = "ApkInstaller"

    fun downloadApk(context: Context, url: String, fileName: String): File? {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        // The name comes from GitHub: keep only its last path part, so it can't leave the folder.
        val dest = File(dir, fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "update.apk" })
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

    /**
     * True when [apk] is an update to this app: same package, signed with the same key. Android's
     * own same-key rule only covers our package, so without this a release carrying some other APK
     * (a mistake, or a compromised account) would be offered for install as "Buckler update".
     */
    fun isOwnUpdate(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val ours = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        theirs.isNotEmpty() && theirs == ours
    }.getOrDefault(false)

    fun install(context: Context, apk: File): Boolean {
        if (!isOwnUpdate(context, apk)) {
            Log.w(TAG, "Refusing ${apk.name}: not this app, or not signed with its key")
            apk.delete()
            return false
        }
        return commit(context, apk, context.packageName)
    }

    /**
     * Install another app from bucklerStore (an icon pack): only when [apk] is the package the
     * index names. Its SHA-256 was checked on download; Android asks the viewer to confirm.
     */
    fun installPackage(context: Context, apk: File, packageName: String): Boolean {
        val archive = runCatching { context.packageManager.getPackageArchiveInfo(apk.path, 0) }.getOrNull()
        if (archive?.packageName != packageName || packageName == context.packageName) {
            Log.w(TAG, "Refusing ${apk.name}: it is ${archive?.packageName}, not $packageName")
            apk.delete()
            return false
        }
        return commit(context, apk, packageName)
    }

    private fun commit(context: Context, apk: File, packageName: String): Boolean {
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= 31 && packageName == context.packageName) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                session.openWrite("package", 0, apk.length()).use { out ->
                    apk.inputStream().use { inp -> inp.copyTo(out) }
                    session.fsync(out)
                }
            } catch (e: Exception) {
                // A half-written session would stay behind; abandon it before falling back.
                session.abandon()
                throw e
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
