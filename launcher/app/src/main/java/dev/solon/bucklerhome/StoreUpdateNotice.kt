package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * On returning Home, says once when something people use from bucklerStore was updated ("3D Maze
 * was updated: …"), or has an update to download ([StoreUpdates]). Waits longer than the unused-apps
 * notice so the two never show together. Fetches the store's index first when it is over a day old.
 */
@Composable
fun StoreUpdateNotice(resumeTick: Int) {
    val context = LocalContext.current
    LaunchedEffect(resumeTick) {
        delay(SETTLE_MS)
        val text = withContext(Dispatchers.IO) {
            StoreIndex.refreshIfStale(context)
            val news = StoreUpdates.untold(context, StoreUpdates.pending(context))
            StoreUpdates.noticeText(news)?.also { StoreUpdates.markTold(context, news) }
        }
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

private const val SETTLE_MS = 9_000L
