package dev.solon.bucklerhome

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.http.HttpResponseCache
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.Formatter
import coil.imageLoader

/** How much of this TV's storage one app takes: the app itself, its data, and the part of that which is cache. */
data class AppStorage(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes
}

/**
 * Storage for Settings → Clean up. Other apps' sizes come from Android's storage records, which
 * need the same usage access as the unused-apps list. Buckler can empty only its own cache; other
 * apps' caches clear from their app page (or all at once with `buckler clean --cache`).
 */
object StorageUse {
    /** Free and total bytes on the internal storage apps live on. */
    fun device(): Pair<Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBytes to stat.totalBytes
    }

    /** Every launchable app's storage, largest first. Empty without usage access. */
    fun scan(context: Context): List<AppStorage> {
        if (!UnusedApps.hasAccess(context)) return emptyList()
        val stats = context.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val pm = context.packageManager
        val user = Process.myUserHandle()
        return UnusedApps.launchablePackages(context).mapNotNull { pkg ->
            val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val s = runCatching { stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user) }.getOrNull()
                ?: return@mapNotNull null
            AppStorage(
                packageName = pkg,
                label = app.loadLabel(pm).toString(),
                system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                appBytes = s.appBytes,
                dataBytes = s.dataBytes,
                cacheBytes = s.cacheBytes,
            )
        }.sortedByDescending { it.totalBytes }
    }

    fun ownCacheBytes(context: Context): Long = context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Empties Buckler's cache: web responses, artwork, saved headlines. All of it refills as Home needs it. */
    fun clearOwnCache(context: Context) {
        // Android's HTTP cache has no "empty": delete it and start a fresh one.
        runCatching { HttpResponseCache.getInstalled()?.delete() }
        installHttpCache(context)
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
        // The two caches above own their folders; everything else in cacheDir is ours to delete.
        context.cacheDir.listFiles()?.filter { it.name != "http" && it.name != "image_cache" }?.forEach { it.deleteRecursively() }
    }

    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
}
