package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * How a page in the Settings panel opens more of itself: a list of its own (a choice, a remote
 * button) or another Settings page, each one level down so Back comes up again. [returnTo] is the
 * row the page was left from, so coming back puts focus on it.
 */
class PanelNav(
    val open: (row: String, title: String, content: @Composable () -> Unit) -> Unit,
    val openPane: (row: String, pane: SettingsPane) -> Unit,
    val back: () -> Unit,
    val returnTo: String?,
    val claimFocus: () -> Unit,
    val focusClaimed: () -> Boolean,
)

/** Set inside the panel; null elsewhere (the hold menus on Home), where rows fall back to buttons. */
val LocalPanelNav = compositionLocalOf<PanelNav?> { null }

/** A focus requester for the row called [row], focused when the page comes back to it. */
@Composable
private fun rememberReturnFocus(row: String): FocusRequester {
    val requester = remember { FocusRequester() }
    val nav = LocalPanelNav.current
    LaunchedEffect(Unit) {
        if (nav?.returnTo != row) return@LaunchedEffect
        delay(80)
        if (runCatching { requester.requestFocus() }.isSuccess) nav.claimFocus()
    }
    return requester
}

/** A setting that is on or off: its name, and On or Off on the right. */
@Composable
fun SettingsToggle(label: String, on: Boolean, onClick: () -> Unit) {
    SettingsAction(label, value = if (on) "On" else "Off", onClick = onClick)
}

/** One of several that can be chosen together (sources, calendars): ✓ when chosen. */
@Composable
fun SettingsCheck(label: String, checked: Boolean, onClick: () -> Unit) {
    SettingsAction(label, selected = checked, mark = RowMark.CHECK, onClick = onClick)
}

/** A row that opens a list or page of its own: its name, what it is set to, and ›. */
@Composable
fun SettingsLink(label: String, value: String? = null, title: String = label, content: @Composable () -> Unit) {
    val nav = LocalPanelNav.current
    val focus = rememberReturnFocus(label)
    SettingsAction(label, focusRequester = focus, value = listOfNotNull(value, "›").joinToString("  ")) {
        nav?.open(label, title, content)
    }
}

/** A row that opens another Settings page, such as a widget's from where it is placed on Home. */
@Composable
fun SettingsPaneLink(label: String, pane: SettingsPane, value: String? = null) {
    val nav = LocalPanelNav.current
    val focus = rememberReturnFocus(label)
    SettingsAction(label, focusRequester = focus, value = listOfNotNull(value, "›").joinToString("  ")) {
        nav?.openPane(label, pane)
    }
}

/** A row that opens one of Android's own screens: ↗ on the right. */
@Composable
fun SystemScreenLink(label: String, screen: SystemScreen) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsAction(label, value = "↗") { screen.open(context) }
}

/**
 * One choice from a short list. In the panel it is a row showing the current choice, which opens
 * the list with ● on it; choosing comes back. Elsewhere the choices are buttons in a row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingsChoice(
    label: String,
    options: List<T>,
    current: T,
    optionLabel: (T) -> String,
    note: String? = null,
    onPick: (T) -> Unit,
) {
    val nav = LocalPanelNav.current
    if (nav == null) {
        PickerTitle(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { option -> SettingsAction(optionLabel(option), selected = option == current) { onPick(option) } }
        }
        return
    }
    SettingsLink(label, value = optionLabel(current)) {
        ChoiceList(options, current, optionLabel, note) { picked ->
            onPick(picked)
            nav.back()
        }
    }
}

/** The list a [SettingsChoice] opens, with focus on the current choice. */
@Composable
private fun <T> ChoiceList(options: List<T>, current: T, optionLabel: (T) -> String, note: String?, onPick: (T) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
    if (note != null) SettingsRow(note)
    options.forEach { option ->
        SettingsAction(
            optionLabel(option),
            focusRequester = if (option == current) focus else null,
            selected = option == current,
        ) { onPick(option) }
    }
}
