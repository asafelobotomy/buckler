package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * On returning Home, offers [HomeNotices] a line, once, when something people use from bucklerStore
 * was updated ("3D Maze was updated: …"), or has an update to download ([StoreUpdates]). Fetches
 * the store's index first when it is over a day old, or Buckler itself was updated since.
 */
@Composable
fun StoreUpdateNotice(resumeTick: Int) {
    val context = LocalContext.current
    LaunchedEffect(resumeTick) {
        delay(SETTLE_MS)
        val news = withContext(Dispatchers.IO) {
            StoreIndex.refreshIfStale(context)
            StoreUpdates.untold(context, StoreUpdates.pending(context))
        }
        val text = StoreUpdates.noticeText(news) ?: return@LaunchedEffect
        HomeNotices.offer(resumeTick, HomeNotices.Priority.STORE_UPDATES, text) { StoreUpdates.markTold(context, news) }
    }
}

private const val SETTLE_MS = 4_000L
