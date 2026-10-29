package dev.solon.bucklerhome

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * An open app the wizard can install, from the CLI's catalog (scripts/gen_profiles.py bundles
 * it): a swap for a stock app, an optional suggestion, or an icon pack. Every download is checked
 * against its pinned [sha256] before it is installed.
 */
data class CatalogApp(
    val id: String,
    val label: String,
    val group: String,
    val packageName: String,
    val version: String,
    val url: String,
    val sha256: String,
    val dock: String,
    val stockPackage: String,
    val stockLabel: String,
    val note: String,
) {
    /** Has an APK to install (the Google TV swap only unpins the stock app). */
    val installable: Boolean get() = url.startsWith("https://") && packageName.isNotBlank() && sha256.length == 64

    companion object {
        const val SWAP = "swap"
        const val SUGGESTION = "suggestion"
        const val ICON_PACK = "iconpack"

        fun from(o: JSONObject) = CatalogApp(
            id = o.getString("id"),
            label = o.getString("label"),
            group = o.getString("group"),
            packageName = o.optString("package"),
            version = o.optString("version"),
            url = o.optString("url"),
            sha256 = o.optString("sha256").lowercase(),
            dock = o.optString("dock"),
            stockPackage = o.optString("stock_package"),
            stockLabel = o.optString("stock_label"),
            note = o.optString("note"),
        )
    }
}

object SetupApps {
    fun installed(context: Context, pkg: String): Boolean = pkg.isNotBlank() &&
        runCatching { context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.isSuccess

    /** The dock after [chosen]: a swap takes its stock app's place, Google TV is unpinned, others go last. */
    fun applyDock(dock: List<String>, chosen: List<CatalogApp>): List<String> {
        val result = dock.toMutableList()
        for (app in chosen) {
            when (app.dock) {
                "replace-pin" -> {
                    val at = result.indexOf(app.stockPackage)
                    if (at >= 0) result[at] = app.packageName else if (app.packageName !in result) result += app.packageName
                }
                "unpin-only" -> result.remove(app.stockPackage)
                else -> if (app.packageName.isNotBlank() && app.packageName !in result) result += app.packageName
            }
        }
        return result.distinct()
    }

    /** Downloads [app]'s APK (https only) into the cache and checks its sha256; a mismatch is deleted. */
    fun download(context: Context, app: CatalogApp, onProgress: (Int) -> Unit): File {
        require(app.installable) { "${app.label} has nothing to download" }
        val dir = File(context.cacheDir, "setup-apks").apply { mkdirs() }
        val dest = File(dir, "${app.id}-${app.sha256}.apk")
        if (dest.isFile && sha256(dest) == app.sha256) return dest
        val partial = File(dir, dest.name + ".partial")
        try {
            var url = URL(app.url)
            var connection: HttpURLConnection
            // Follow redirects by hand, refusing any that leave https.
            var hops = 0
            while (true) {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "buckler")
                }
                val code = connection.responseCode
                if (code !in 300..399) break
                val next = URL(url, connection.getHeaderField("Location") ?: error("redirect without a location"))
                connection.disconnect()
                check(next.protocol == "https" && ++hops <= 5) { "unsafe redirect to $next" }
                url = next
            }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            connection.disconnect()
            val got = sha256(partial)
            check(got == app.sha256) { "the download does not match its pinned checksum" }
            check(partial.renameTo(dest)) { "could not keep the download" }
            return dest
        } finally {
            partial.delete()
        }
    }

    /** Downloads, checks and installs [app] over [adb]. Returns a line for the results. */
    fun install(context: Context, adb: AdbSelf, app: CatalogApp, step: (String) -> Unit): String {
        val apk = try {
            download(context, app) { step("Downloading ${app.label}… $it%") }
        } catch (e: Exception) {
            return "${app.label} not installed: ${e.message ?: e.javaClass.simpleName}."
        }
        step("Installing ${app.label}…")
        val result = adb.install(apk)
        apk.delete()
        return if (result.ok) "Installed ${app.label} ${app.version}." else "${app.label} not installed: ${result.output.take(120)}"
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
