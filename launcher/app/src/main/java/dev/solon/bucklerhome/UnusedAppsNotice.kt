package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * On returning Home, names apps that have gone unused (at most weekly, and only when the list
 * changed; see [UnusedApps.noticeText]). Waits a moment first so Home settles before it scans.
 */
@Composable
fun UnusedAppsNotice(resumeTick: Int) {
    val context = LocalContext.current
    LaunchedEffect(resumeTick) {
        delay(SETTLE_MS)
        val text = withContext(Dispatchers.IO) {
            if (!UnusedApps.noticeOn(context) || !UnusedApps.hasAccess(context)) return@withContext null
            val days = UnusedApps.days(context)
            val apps = UnusedApps.scan(context, days)
            UnusedApps.export(context, apps, days)
            UnusedApps.noticeText(context, apps, days)
        }
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}

private const val SETTLE_MS = 4_000L
