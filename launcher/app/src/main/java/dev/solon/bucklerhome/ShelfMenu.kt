package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Options for a whole shelf, opened by pressing Left on its first tile (like the stock SHIELD
 * launcher's row menu). "Move shelf" closes the dialog and hands over to shelf move mode. Settings
 * is here too, so it stays reachable when the header (or its Settings icon) is switched off.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShelfMenuDialog(
    title: String,
    canMove: Boolean,
    canHide: Boolean,
    onMove: () -> Unit,
    onHide: () -> Unit,
    onEditHome: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 400.dp) {
            Column {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = "Shelf options",
                    fontSize = 13.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (canMove) {
                        SettingsAction("Move shelf", focusRequester = firstFocus, onClick = onMove)
                    }
                    if (canHide) {
                        SettingsAction("Hide shelf", focusRequester = if (canMove) null else firstFocus, onClick = onHide)
                    }
                    SettingsAction(
                        "Edit Home",
                        focusRequester = if (canMove || canHide) null else firstFocus,
                        onClick = onEditHome,
                    )
                    SettingsAction("Settings", onClick = onSettings)
                    SettingsAction("Close", onClick = onDismiss)
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { firstFocus.requestFocus() }
        }
    }
}
