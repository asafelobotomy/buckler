package dev.solon.bucklerhome

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings → Accessibility. Visual options change how Buckler itself looks and speaks; hearing
 * options are system features, so they link straight to Android's own screens (mono audio is the
 * one we can flip directly once permission has been granted).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessibilityPane() {
    val context = LocalContext.current
    val a11y = AppearanceStore.config
    var tick by remember { mutableStateOf(0) }
    val mono = remember(tick) { SystemSettings.monoAudio(context) }
    val flow = Arrangement.spacedBy(12.dp)

    fun update(transform: (AppearanceConfig) -> AppearanceConfig) = AppearanceStore.update(context, transform)

    fun openSystem(action: String, label: String) {
        launchFirstThatWorks(
            context,
            label,
            listOf(Intent(action), Intent(Settings.ACTION_SETTINGS)),
        )
    }

    Column {
        SectionTitle("Accessibility")

        SettingsRow("Vision")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsAction("High contrast", selected = a11y.highContrast) {
                update { it.copy(highContrast = !it.highContrast) }
            }
            SettingsAction("Strong focus outline", selected = a11y.strongFocus) {
                update { it.copy(strongFocus = !it.strongFocus) }
            }
            SettingsAction("Reduce motion", selected = a11y.reduceMotion) {
                update { it.copy(reduceMotion = !it.reduceMotion) }
            }
            SettingsAction("Speak focused item", selected = a11y.spokenFocus) {
                if (a11y.spokenFocus) SpokenFocus.stop()
                update { it.copy(spokenFocus = !it.spokenFocus) }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsRow("Text size")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            TextSize.entries.forEach { size ->
                SettingsAction(size.label, selected = size == a11y.textSize) {
                    update { it.copy(textSize = size) }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsAction("Screen reader (TalkBack)") {
            openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility settings")
        }

        Spacer(modifier = Modifier.height(24.dp))
        SettingsRow(
            "Hearing" + if (SystemSettings.canWriteSystem(context)) "" else
                "  (mono audio needs a one-time permission: ${SystemSettings.GRANT_COMMAND})",
        )
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsAction("Mono audio", selected = mono) {
                if (SystemSettings.setMonoAudio(context, !mono)) {
                    tick++
                } else {
                    Toast.makeText(context, "Run: ${SystemSettings.GRANT_COMMAND}", Toast.LENGTH_LONG).show()
                }
            }
            SettingsAction("Captions") {
                openSystem(Settings.ACTION_CAPTIONING_SETTINGS, "caption settings")
            }
        }
    }
}
