package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** One online photo: where to fetch it and the attribution to show. */
data class CommonsImage(val url: String, val credit: String)

object ScreensaverImages {
    private const val COMMONS = "https://commons.wikimedia.org/w/api.php"
    private const val INDEX_TTL_MS = 3 * 24 * 3_600_000L
    private const val MAX_CACHED_FILES = 40
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val UA = mapOf(
        "User-Agent" to "buckler-home/${BuildConfig.VERSION_NAME} (https://github.com/${BuildConfig.GITHUB_REPO})",
    )

    fun uploadDir(context: Context): File =
        (context.getExternalFilesDir("screensaver") ?: File(context.filesDir, "screensaver"))
            .also { it.mkdirs() }

    fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    fun uploaded(context: Context): List<File> =
        uploadDir(context).listFiles { f -> f.isFile && isImageName(f.name) }
            ?.sortedBy { it.name }
            .orEmpty()

    fun clearUploaded(context: Context): Int =
        uploaded(context).count { it.delete() }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "saver").also { it.mkdirs() }

    /** Featured pictures for [category], cached for a few days so the screensaver starts instantly. */
    fun onlineIndex(context: Context, category: PhotoCategory): List<CommonsImage> {
        // Keyed by the query too, so changing a category's search words drops the old list.
        val cache = File(cacheDir(context), "index-${category.name.lowercase()}-${sha1(category.query).take(8)}.json")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < INDEX_TTL_MS) {
            parseIndexCache(cache.readText()).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val fresh = fetchIndex(category)
        if (fresh.isNotEmpty()) {
            cache.writeText(toIndexCache(fresh))
        } else if (cache.isFile) {
            return parseIndexCache(cache.readText())
        }
        return fresh
    }

    /**
     * A random page of results for variety, then the first page when that one is empty. Small
     * categories have fewer than 50 matches, so a later page alone came back empty and the
     * screensaver reported itself offline.
     */
    private fun fetchIndex(category: PhotoCategory): List<CommonsImage> {
        for (offset in listOf(listOf(0, 50, 100).random(), 0).distinct()) {
            val page = fetchPage(category, offset)
            if (page.isNotEmpty()) return page
        }
        return emptyList()
    }

    private fun fetchPage(category: PhotoCategory, offset: Int): List<CommonsImage> {
        val search = "${category.query} incategory:Featured_pictures_on_Wikimedia_Commons filetype:bitmap"
        val url = "$COMMONS?action=query&generator=search&gsrnamespace=6&gsrlimit=50&gsroffset=$offset" +
            "&gsrsearch=${enc(search)}&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1920&format=json"
        val res = runCatching { HttpJson.get(url, headers = UA) }.getOrNull() ?: return emptyList()
        return if (res.code in 200..299) parseCommons(res.body) else emptyList()
    }

    /** One slide from each category in turn, so a mix alternates instead of running in blocks. */
    fun <T> takeTurns(groups: List<List<T>>): List<T> = buildList {
        val longest = groups.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) groups.forEach { group -> group.getOrNull(i)?.let(::add) }
    }

    /** True when Android reports a validated internet connection. */
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun parseCommons(body: String): List<CommonsImage> {
        val pages = HttpJson.parseObject(body)?.optJSONObject("query")?.optJSONObject("pages")
            ?: return emptyList()
        return pages.keys().asSequence().mapNotNull { key ->
            val page = pages.optJSONObject(key) ?: return@mapNotNull null
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val w = info.optInt("thumbwidth")
            val h = info.optInt("thumbheight")
            val url = info.optString("thumburl")
            // Landscape-ish only: portrait shots would be cropped to a sliver on a 16:9 screen.
            if (url.isBlank() || w < 1400 || h <= 0 || w.toDouble() / h !in 1.3..2.1) {
                return@mapNotNull null
            }
            CommonsImage(url, credit(page.optString("title"), info.optJSONObject("extmetadata")))
        }.toList().shuffled()
    }

    private fun credit(title: String, meta: JSONObject?): String {
        val name = title.removePrefix("File:").substringBeforeLast('.').take(70)
        val artist = meta?.optJSONObject("Artist")?.optString("value").orEmpty()
            .replace(Regex("<[^>]+>"), "").trim().take(50)
        val license = meta?.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
        return listOf(name, artist, license).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun toIndexCache(images: List<CommonsImage>): String =
        JSONArray(images.map { JSONObject().put("u", it.url).put("c", it.credit) }).toString()

    private fun parseIndexCache(raw: String): List<CommonsImage> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CommonsImage(o.getString("u"), o.optString("c"))
        }
    }.getOrDefault(emptyList())

    /** Downloads (or reuses) the cached copy of [image]; null when offline or rejected. */
    fun download(context: Context, image: CommonsImage): File? {
        val dir = cacheDir(context)
        val dest = File(dir, "img-${sha1(image.url)}.jpg")
        if (dest.isFile && dest.length() > 0) return dest
        val tmp = File(dir, dest.name + ".part")
        val code = runCatching { HttpJson.downloadTo(image.url, tmp, UA) }.getOrDefault(-1)
        if (code !in 200..299 || tmp.length() == 0L || !tmp.renameTo(dest)) {
            tmp.delete()
            return null
        }
        prune(dir)
        return dest
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("img-") }?.sortedBy { it.lastModified() }
            ?: return
        files.take((files.size - MAX_CACHED_FILES).coerceAtLeast(0)).forEach { it.delete() }
    }

    /** Decodes [file] downsampled so neither side exceeds [maxDim] (TV GPUs choke on 20 MP bitmaps). */
    fun decode(file: File, maxDim: Int = 1920): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        // Either side over the limit halves again: a 3024×4032 portrait or a long panorama used to
        // slip through at full size (needing both sides large), ~48 MB or more per slide.
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        return runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8.name())

    private fun sha1(v: String): String =
        MessageDigest.getInstance("SHA-1").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }
}
