package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Says so on Home when remote buttons you turned on have stopped (see [RemoteMap.needsRestart]),
 * once per Buckler start, so a remapped Netflix button does not quietly go back to Netflix.
 */
@Composable
fun RemoteButtonsNotice(resumeTick: Int) {
    val context = LocalContext.current
    LaunchedEffect(resumeTick) {
        if (shown) return@LaunchedEffect
        delay(SETTLE_MS)
        if (!RemoteMap.needsRestart(context)) return@LaunchedEffect
        shown = true
        Toast.makeText(context, "Remote buttons stopped. Settings → Remote → Turn on again.", Toast.LENGTH_LONG).show()
    }
}

private var shown = false
private const val SETTLE_MS = 6_000L
