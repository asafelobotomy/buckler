package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * On returning Home, offers [HomeNotices] a line naming apps that have gone unused (at most weekly, and only when the list
 * changed; see [UnusedApps.noticeText]). Scans only when [UnusedApps.scanDue], after a moment so
 * Home settles first.
 */
@Composable
fun UnusedAppsNotice(resumeTick: Int) {
    val context = LocalContext.current
    LaunchedEffect(resumeTick) {
        delay(SETTLE_MS)
        val found = withContext(Dispatchers.IO) {
            if (!UnusedApps.hasAccess(context) || !UnusedApps.scanDue(context)) return@withContext null
            val days = UnusedApps.days(context)
            val apps = UnusedApps.scan(context, days)
            UnusedApps.export(context, apps, days)
            UnusedApps.noticeText(context, apps, days)?.let { it to apps }
        } ?: return@LaunchedEffect
        val (text, apps) = found
        HomeNotices.offer(resumeTick, HomeNotices.Priority.UNUSED_APPS, text) { UnusedApps.markNoticed(context, apps) }
    }
}

private const val SETTLE_MS = 4_000L
