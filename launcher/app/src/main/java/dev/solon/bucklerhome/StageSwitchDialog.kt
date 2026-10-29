package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/** Hold OK on the Main Stage: pick the widget, then the options of the one showing ([StageOptions]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StageSwitchDialog(
    current: MainStage,
    pinLabel: String?,
    onPickStage: (MainStage) -> Unit,
    onOptionsChanged: () -> Unit,
    onPin: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val flow = Arrangement.spacedBy(12.dp)
    val currentFocus = remember { FocusRequester() }
    // Opens on the current stage, so OK there changes nothing.
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { currentFocus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        // Opened by a hold: the held OK's repeats and release must not pick whatever has focus.
        DialogPanel(modifier = Modifier.swallowOrphanSelect(), minWidth = 460.dp) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HoldMenuTitle("Main stage", "Hold OK on the stage to open this again.")
                FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
                    MainStage.entries.forEach { mode ->
                        SettingsAction(
                            mode.label,
                            focusRequester = currentFocus.takeIf { mode == current },
                            selected = mode == current,
                        ) { onPickStage(mode) }
                    }
                }
                StageOptions(current, onOptionsChanged)
                HoldMenuFooter(SettingsPane.MAIN_STAGE, onDismiss) {
                    if (pinLabel != null && onPin != null) SettingsAction(pinLabel, onClick = onPin)
                }
            }
        }
    }
}
