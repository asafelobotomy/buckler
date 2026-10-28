package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.provider.BaseColumns

/** How many Up Next cards one publishing app may contribute. */
const val UP_NEXT_PER_SOURCE = 3

data class UpNextPublisher(val packageName: String, val label: String)

/**
 * Indexes to keep, in cursor order, with at most [UP_NEXT_PER_SOURCE] rows from each package.
 * [allowed] null means every package; an empty set means none.
 */
fun capUpNextIndexes(packages: List<String>, allowed: Set<String>?): List<Int> {
    if (allowed != null && allowed.isEmpty()) return emptyList()
    val counts = mutableMapOf<String, Int>()
    return packages.mapIndexedNotNull { index, pkg ->
        if (allowed != null && pkg !in allowed) return@mapIndexedNotNull null
        val seen = counts[pkg] ?: 0
        if (seen >= UP_NEXT_PER_SOURCE) return@mapIndexedNotNull null
        counts[pkg] = seen + 1
        index
    }
}

object WatchNextRepository {
    private const val SCAN_LIMIT = 80

    fun load(context: Context, allowed: Set<String>?): List<ShelfItem> {
        val rows = read(context)
        val keep = capUpNextIndexes(rows.map { it.packageName }, allowed).toSet()
        return rows.mapIndexedNotNull { index, row -> if (index in keep) row.item else null }
    }

    /** Apps that currently have Watch Next rows, newest engagement first in [load]. */
    fun publishers(context: Context): List<UpNextPublisher> {
        val pm = context.packageManager
        return read(context)
            .map { it.packageName }
            .distinct()
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                UpNextPublisher(pkg, label)
            }
            .sortedBy { it.label.lowercase() }
    }

    private data class Row(val packageName: String, val item: ShelfItem)

    private fun read(context: Context): List<Row> {
        val rows = mutableListOf<Row>()
        val uri = TvContract.WatchNextPrograms.CONTENT_URI
        val order = "${TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC"
        val projection = arrayOf(
            BaseColumns._ID,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, order)?.use { cursor ->
                fun text(i: Int) = if (cursor.isNull(i)) null else cursor.getString(i)?.takeIf { it.isNotBlank() }
                while (cursor.moveToNext() && rows.size < SCAN_LIMIT) {
                    val title = text(1) ?: continue
                    val pkg = text(2) ?: continue
                    val art = (text(3) ?: text(4))?.let(Uri::parse)
                    val artStr = art?.toString()
                    rows.add(
                        Row(
                            packageName = pkg,
                            item = ShelfItem(
                                id = "wn-${cursor.getLong(0)}",
                                title = title,
                                subtitle = null,
                                packageName = null,
                                launchIntent = text(5)?.let(::parseIntent)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                imageUri = art,
                                imageUrl = artStr?.takeIf { it.startsWith("https://") },
                            ),
                        ),
                    )
                }
            }
            rows
        }.getOrDefault(emptyList())
    }

    private fun parseIntent(uri: String): Intent? =
        runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
}
