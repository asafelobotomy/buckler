package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Hold OK on the Main Stage: pick the widget, and the weather row when that widget is Weather. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StageSwitchDialog(
    current: MainStage,
    span: WeatherSpan,
    pinLabel: String?,
    onPickStage: (MainStage) -> Unit,
    onPickSpan: (WeatherSpan) -> Unit,
    onPin: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val flow = Arrangement.spacedBy(12.dp)
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 460.dp) {
            Column(modifier = Modifier.autoFocus()) {
                Text(
                    text = "Main stage",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = "Hold OK on the stage to open this again.",
                    fontSize = 13.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
                    MainStage.entries.forEach { mode ->
                        SettingsAction(mode.label, selected = mode == current) { onPickStage(mode) }
                    }
                }
                Text(
                    text = "Weather",
                    fontSize = 14.sp,
                    color = ColorSection,
                    modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
                )
                FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
                    WeatherSpan.entries.forEach { option ->
                        SettingsAction(option.label, selected = option == span) { onPickSpan(option) }
                    }
                }
                FlowRow(
                    horizontalArrangement = flow,
                    verticalArrangement = flow,
                    modifier = Modifier.padding(top = 18.dp),
                ) {
                    if (pinLabel != null && onPin != null) {
                        SettingsAction(pinLabel, onClick = onPin)
                    }
                    SettingsAction("Close", onClick = onDismiss)
                }
            }
        }
    }
}
