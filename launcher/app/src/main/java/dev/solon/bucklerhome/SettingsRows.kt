package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Whether two settings values are the same, as numbers when both are ("1" and "1.0"). */
private fun sameValue(a: String, b: String): Boolean {
    val x = a.toFloatOrNull()
    val y = b.toFloatOrNull()
    return if (x != null && y != null) x == y else a == b
}

/**
 * A whole-TV setting that is on or off, written straight to Android's settings ([SystemKeys]).
 * [onChanged] runs after a write that took.
 */
@Composable
fun SystemToggle(label: String, key: SystemKey, onChanged: (Boolean) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(SystemSettings.get(context, key) == "1") }
    SettingsToggle(label, on) {
        if (SystemSettings.put(context, key, if (on) "0" else "1")) {
            on = !on
            onChanged(on)
        } else {
            SystemSettings.explainGrant(context)
        }
    }
}

/**
 * A whole-TV setting with a few values ([options]: value to label), the same value also written
 * to [also]. A value set elsewhere that is not among them shows as [other] of it.
 */
@Composable
fun SystemChoice(
    label: String,
    key: SystemKey,
    options: List<Pair<String, String>>,
    note: String? = null,
    also: List<SystemKey> = emptyList(),
    other: (String) -> String = { it },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var value by remember { mutableStateOf(SystemSettings.get(context, key)) }
    val current = options.firstOrNull { sameValue(it.first, value) } ?: (value to other(value))
    val shown = if (current in options) options else options + current
    SettingsChoice(label, shown, current, { it.second }, note) { (picked, _) ->
        if ((listOf(key) + also).all { SystemSettings.put(context, it, picked) }) value = picked else SystemSettings.explainGrant(context)
    }
}

/** Says so when this TV's settings are read-only to Buckler until the one-time grant is run. */
@Composable
fun GrantNote(table: SettingsTable = SettingsTable.SECURE) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!SystemSettings.canWrite(context, table)) SettingsRow("Read-only until ${SystemSettings.GRANT_COMMAND} is run from a computer.")
}
