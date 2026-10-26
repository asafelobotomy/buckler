package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

object WatchNextRepository {
    fun load(context: Context, limit: Int = 20): List<ShelfItem> {
        val items = mutableListOf<ShelfItem>()
        val uri = TvContractCompat.WatchNextPrograms.CONTENT_URI
        return runCatching {
            context.contentResolver.query(
                uri,
                WatchNextProgram.PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext() && items.size < limit) {
                    val program = WatchNextProgram.fromCursor(cursor) ?: continue
                    val title = program.title?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val intent = resolveIntent(context, program)
                    val art = program.posterArtUri ?: program.thumbnailUri
                    val artStr = art?.toString()
                    items.add(
                        ShelfItem(
                            id = "wn-${program.id}",
                            title = title,
                            subtitle = null,
                            packageName = null,
                            launchIntent = intent,
                            imageUri = art,
                            imageUrl = artStr?.takeIf {
                                it.startsWith("http://") || it.startsWith("https://")
                            },
                        ),
                    )
                }
            }
            items
        }.getOrDefault(emptyList())
    }

    private fun resolveIntent(context: Context, program: WatchNextProgram): Intent? {
        program.intentUri?.let { uri ->
            parseIntent(uri)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        // Fallback: open the publishing package if we can read it from intent extras later
        return null
    }

    private fun parseIntent(uri: Uri): Intent? =
        runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
}
