package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Which apps may fill Up Next. Every chosen app contributes at most [UP_NEXT_PER_SOURCE] cards.
 * Leaving them all on keeps the shelf open to apps that publish later.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UpNextSourcesDialog(
    shelf: ShelfConfig,
    onApply: (restrict: Boolean, packages: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var publishers by remember { mutableStateOf<List<UpNextPublisher>?>(null) }
    var picked by remember {
        mutableStateOf(if (shelf.restrictSources) shelf.packages.toSet() else null)
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        publishers = withContext(Dispatchers.IO) { WatchNextRepository.publishers(context) }
    }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 520.dp, maxWidth = 760.dp) {
            Column {
                Text(text = "Up Next sources", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
                Text(
                    text = "Each source shows up to $UP_NEXT_PER_SOURCE cards.",
                    fontSize = 13.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                val known = publishers
                when {
                    known == null -> Text(text = "…", color = ColorMuted, fontSize = 15.sp)
                    known.isEmpty() -> Text(
                        text = "No app has published Up Next yet.",
                        color = ColorMuted,
                        fontSize = 15.sp,
                    )
                    else -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        known.forEach { publisher ->
                            val on = picked?.contains(publisher.packageName) ?: true
                            SettingsAction(publisher.label, selected = on) {
                                val now = (picked ?: known.map { it.packageName }.toSet()).toMutableSet()
                                if (publisher.packageName in now) now -= publisher.packageName
                                else now += publisher.packageName
                                picked = now
                            }
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 18.dp),
                ) {
                    SettingsAction("Done", focusRequester = firstFocus) {
                        val list = publishers
                        if (list.isNullOrEmpty()) {
                            onDismiss()
                            return@SettingsAction
                        }
                        val names = list.map { it.packageName }.toSet()
                        val chosen = picked ?: names
                        val restrict = chosen.size < names.size
                        val allowed = list.filter { it.packageName in chosen }.map { it.packageName }
                        onApply(restrict, if (restrict) allowed else emptyList())
                    }
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
