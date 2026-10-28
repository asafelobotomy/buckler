package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Idle delays before the screensaver starts, and how they are written for people. */
object ScreensaverTimeout {
    const val MIN_MS = 30_000
    const val MAX_MS = 12 * 3_600_000

    val PRESETS_MS = listOf(60_000, 90_000, 120_000, 300_000, 600_000, 900_000, 3_600_000)

    fun isPreset(ms: Int): Boolean = ms in PRESETS_MS

    fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)

    /** "90 sec", "5 min", "1 hour", "7 min 30 s". */
    fun label(ms: Int): String {
        val totalSeconds = ms / 1_000
        val hour = 3_600
        return when {
            ms >= SystemSettings.NEVER_MS -> "Never"
            totalSeconds % hour == 0 -> if (totalSeconds / hour == 1) "1 hour" else "${totalSeconds / hour} hours"
            totalSeconds < 300 -> "$totalSeconds sec"
            totalSeconds % 60 == 0 -> "${totalSeconds / 60} min"
            else -> "${totalSeconds / 60} min ${totalSeconds % 60} s"
        }
    }
}

/** D-pad friendly custom delay: step buttons rather than a keyboard. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomTimeoutDialog(initialMs: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var ms by remember { mutableIntStateOf(ScreensaverTimeout.clamp(initialMs)) }
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 640.dp, maxWidth = 720.dp) {
            Column {
                Text(
                    text = "Start screensaver after",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = ScreensaverTimeout.label(ms),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    color = ColorBrand,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf("−5 min" to -300_000, "−1 min" to -60_000, "−30 s" to -30_000).forEach { (label, delta) ->
                        SettingsAction(label) { ms = ScreensaverTimeout.clamp(ms + delta) }
                    }
                    listOf("+30 s" to 30_000, "+1 min" to 60_000, "+5 min" to 300_000).forEach { (label, delta) ->
                        SettingsAction(label, focusRequester = if (delta == 60_000) firstFocus else null) {
                            ms = ScreensaverTimeout.clamp(ms + delta)
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 18.dp),
                ) {
                    SettingsAction("Save") { onSave(ms) }
                    SettingsAction("Cancel", onClick = onDismiss)
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { firstFocus.requestFocus() }
        }
    }
}
