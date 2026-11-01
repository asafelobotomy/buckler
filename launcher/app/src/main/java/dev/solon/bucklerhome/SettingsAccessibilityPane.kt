package dev.solon.bucklerhome

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
import androidx.compose.runtime.mutableIntStateOf
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
    var tick by remember { mutableIntStateOf(0) }
    val mono = remember(tick) { SystemSettings.monoAudio(context) }
    val flow = Arrangement.spacedBy(12.dp)

    fun update(transform: (AppearanceConfig) -> AppearanceConfig) = AppearanceStore.update(context, transform)

    Column {
        SectionTitle("Accessibility")

        PickerTitle("Buckler")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsToggle("High contrast", a11y.highContrast) {
                update { it.copy(highContrast = !it.highContrast) }
            }
            SettingsToggle("Strong focus outline", a11y.strongFocus) {
                update { it.copy(strongFocus = !it.strongFocus) }
            }
            SettingsToggle("Reduce motion", a11y.reduceMotion) {
                update { it.copy(reduceMotion = !it.reduceMotion) }
            }
            SettingsToggle("Speak focused item", a11y.spokenFocus) {
                if (a11y.spokenFocus) SpokenFocus.stop()
                update { it.copy(spokenFocus = !it.spokenFocus) }
            }
            SystemScreenLink("Screen reader (TalkBack)", SystemScreen.ACCESSIBILITY)
        }
        Spacer(modifier = Modifier.height(18.dp))
        SettingsChoice("Text size", TextSize.entries, a11y.textSize, { it.label }) { size ->
            update { it.copy(textSize = size) }
        }
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Whole TV")
        GrantNote()
        SystemToggle("High contrast text", SystemKeys.HIGH_TEXT_CONTRAST)
        SettingsToggle("Mono audio", mono) {
            if (SystemSettings.setMonoAudio(context, !mono)) {
                tick++
            } else {
                Toast.makeText(context, "Run: ${SystemSettings.GRANT_COMMAND}", Toast.LENGTH_LONG).show()
            }
        }
        SystemChoice(
            "Speech rate",
            SystemKeys.TTS_RATE,
            listOf("50" to "0.5×", "75" to "0.75×", "100" to "Normal", "150" to "1.5×", "200" to "2×", "300" to "3×"),
            note = "For the screen reader and Speak focused item.",
            other = { "${(it.toIntOrNull() ?: 100) / 100.0}×" },
        )
        SystemChoice(
            "Animations",
            SystemKeys.ANIMATOR_SCALE,
            ANIMATIONS.map { (scale, label) -> scale.toString() to label },
            note = "How fast Android's screens open and close; Reduce motion above is Buckler's own.",
            also = listOf(SystemKeys.WINDOW_SCALE, SystemKeys.TRANSITION_SCALE),
            other = { "${it}×" },
        )
        Spacer(modifier = Modifier.height(18.dp))
        CaptionsSection()
    }
}
