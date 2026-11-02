package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings → Accessibility, by what it helps with rather than whose setting it is: each topic has
 * Buckler's own rows (Home and its screens) beside the TV's (Android's screens and other apps). The
 * TV's private keys are written over Network debugging ([SystemKey.overAdb]).
 */
@Composable
fun AccessibilityPane() {
    val context = LocalContext.current
    val a11y = AppearanceStore.config

    fun update(transform: (AppearanceConfig) -> AppearanceConfig) = AppearanceStore.update(context, transform)

    Column {
        SectionTitle("Accessibility")
        GrantNote()

        PickerTitle("Seeing")
        SettingsChoice("Text size on Home", TextSize.entries, a11y.textSize, { it.label }) { size ->
            update { it.copy(textSize = size) }
        }
        SystemChoice(
            "Text size in apps",
            SystemKeys.FONT_SCALE,
            listOf("0.85" to "Small", "1.0" to "Normal", "1.15" to "Large", "1.3" to "Largest"),
            note = "For Android's screens and other apps, which restart to take it (one playing stops).",
            other = { "${it}×" },
        )
        SettingsToggle("High contrast on Home", a11y.highContrast) {
            update { it.copy(highContrast = !it.highContrast) }
        }
        SystemToggle("High contrast text in apps", SystemKeys.HIGH_TEXT_CONTRAST)
        SettingsToggle("Strong focus outline", a11y.strongFocus) {
            update { it.copy(strongFocus = !it.strongFocus) }
        }

        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Motion")
        SettingsToggle("Reduce motion on Home", a11y.reduceMotion) {
            update { it.copy(reduceMotion = !it.reduceMotion) }
        }
        SystemChoice(
            "Animations in apps",
            SystemKeys.ANIMATOR_SCALE,
            ANIMATIONS.map { (scale, label) -> scale.toString() to label },
            note = "How fast Android's screens open and close.",
            also = listOf(SystemKeys.WINDOW_SCALE, SystemKeys.TRANSITION_SCALE),
            other = { "${it}×" },
        )

        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Speech")
        SettingsToggle("Speak focused item", a11y.spokenFocus) {
            if (a11y.spokenFocus) SpokenFocus.stop()
            update { it.copy(spokenFocus = !it.spokenFocus) }
        }
        SystemChoice(
            "Speech rate",
            SystemKeys.TTS_RATE,
            listOf("50" to "0.5×", "75" to "0.75×", "100" to "Normal", "150" to "1.5×", "200" to "2×", "300" to "3×"),
            note = "For the screen reader and Speak focused item.",
            other = { "${(it.toIntOrNull() ?: 100) / 100.0}×" },
        )
        SystemScreenLink("Screen reader (TalkBack)", SystemScreen.ACCESSIBILITY)

        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("Hearing")
        SystemToggle("Mono audio", SystemKeys.MONO_AUDIO)
        CaptionsSection()
    }
}
