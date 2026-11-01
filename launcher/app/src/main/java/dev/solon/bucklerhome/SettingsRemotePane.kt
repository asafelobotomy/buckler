package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings → Remote & accessories: the remote drawn with what each button does. Netflix, Back and
 * the media keys are Buckler's (an accessibility service it switches on itself); ≡ uses SHIELD's
 * own option. OK on a button opens its page: Press, Double press and Hold, each a list of its own.
 */
@Composable
fun RemotePane() {
    val context = LocalContext.current
    val nav = LocalPanelNav.current
    var tick by remember { mutableIntStateOf(0) }
    val requesters = remember { RemoteButton.entries.associateWith { FocusRequester() } }
    // Back from a button's page: focus goes to that button again.
    LaunchedEffect(Unit) {
        val button = RemoteButton.entries.firstOrNull { it.name == nav?.returnTo } ?: return@LaunchedEffect
        delay(80)
        if (runCatching { requesters.getValue(button).requestFocus() }.isSuccess) nav?.claimFocus?.invoke()
    }
    val on = remember(tick) { RemoteMap.serviceOn(context) }
    val stalled = remember(tick) { RemoteMap.needsRestart(context) }
    var restarting by remember { mutableStateOf(false) }
    val chosen = remember(tick) { RemoteModels.chosen(context) }
    val detected = remember(tick) { RemoteModels.detect(context) }
    val model = chosen ?: detected ?: remember(tick) { RemoteModels.current(context) }

    fun describe(button: RemoteButton): String = when (button.mapping) {
        RemoteButton.Mapping.FIXED -> "can't be changed"
        RemoteButton.Mapping.SHIELD -> PressKind.entries.joinToString(" · ") { "${it.short}: ${ShieldMenuButton.label(context, it)}" }
        RemoteButton.Mapping.BUCKLER -> {
            val changed = PressKind.entries.mapNotNull { kind ->
                RemoteMap.action(context, button, kind).takeIf { it != RemoteAction.Default }?.let { "${kind.short}: ${it.label}" }
            }
            if (changed.isEmpty()) "default" else changed.joinToString(" · ")
        }
    }

    Column {
        SectionTitle("Remote & accessories")
        SettingsRow(
            "Choose what your remote's buttons do on a press, a double press or a hold (≡ through SHIELD's " +
                "own option). Only a button with a double press waits for a second press.",
        )
        SettingsRow(
            when {
                chosen != null -> "Set to: ${chosen.label}."
                detected != null -> "Found: ${detected.label}."
                model != null -> "Not connected right now; last seen: ${model.label}."
                else -> "Buckler could not tell which remote this is. Choose it:"
            },
        )
        SettingsChoice("Your remote", listOf<RemoteModel?>(null) + RemoteModel.entries, chosen, { it?.short ?: "Find automatically" }) {
            RemoteModels.choose(context, it)
            tick++
        }
        when {
            restarting -> SettingsAction("Remote buttons", value = "Turning on…") {}
            stalled -> SettingsAction("Remote buttons", value = "Stopped: turn on again") {
                restarting = true
                RemoteMap.restartService(context) {
                    restarting = false
                    tick++
                }
            }
            else -> SettingsToggle("Remote buttons", on) {
                if (!RemoteMap.setServiceOn(context, !on)) {
                    Toast.makeText(context, "Run: ${SystemSettings.GRANT_COMMAND}", Toast.LENGTH_LONG).show()
                }
                tick++
            }
        }
        if (stalled && !restarting) {
            SettingsRow("Remote buttons stopped and Buckler could not turn them on again by itself.")
        }
        if (!on && !stalled) SettingsRow("Off: every button does what it always does. ≡ still follows SHIELD's option.")
        Spacer(modifier = Modifier.height(16.dp))
        if (model != null) {
            if (!model.drawn) SettingsRow("Buttons Buckler can change on most remotes:")
            RemoteMapper(
                model = model,
                describe = ::describe,
                remapped = { it.mapping != RemoteButton.Mapping.FIXED && describe(it) != "default" },
                requesters = requesters,
                onPick = { button ->
                    if (button.mapping == RemoteButton.Mapping.FIXED) {
                        Toast.makeText(context, "${button.label}: ${button.fixedReason}", Toast.LENGTH_LONG).show()
                    } else {
                        nav?.open(button.name, "${button.label} button") { ButtonPage(button) }
                    }
                },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        PickerTitle("Accessories")
        SystemScreenLink("Pair a remote or accessory", SystemScreen.ACCESSORIES)
    }
}

/** One button's three presses, each showing what it does and opening the list to change it. */
@Composable
private fun ButtonPage(button: RemoteButton) {
    val context = LocalContext.current
    SettingsRow(if (button.mapping == RemoteButton.Mapping.SHIELD) "SHIELD's own ≡ options." else "OK on a press to change it.")
    PressKind.entries.forEach { kind ->
        val now = if (button.mapping == RemoteButton.Mapping.SHIELD) {
            ShieldMenuButton.label(context, kind)
        } else {
            RemoteMap.action(context, button, kind).label
        }
        SettingsLink(kind.label, value = now, title = "${button.label}: ${kind.label}") { ActionList(button, kind) }
    }
}

/** What one press can do; choosing goes back to the button's page. */
@Composable
private fun ActionList(button: RemoteButton, kind: PressKind) {
    val context = LocalContext.current
    val nav = LocalPanelNav.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    if (button.mapping == RemoteButton.Mapping.SHIELD) {
        ShieldMenuButton.CHOICES.forEachIndexed { i, (value, label) ->
            SettingsAction(label, focusRequester = first.takeIf { i == 0 }, selected = ShieldMenuButton.isChosen(context, kind, value)) {
                if (!ShieldMenuButton.set(context, kind, value)) {
                    Toast.makeText(context, "Run: ${SystemSettings.GRANT_COMMAND}", Toast.LENGTH_LONG).show()
                }
                nav?.back()
            }
        }
        return
    }
    val current = RemoteMap.action(context, button, kind)
    fun choose(action: RemoteAction) {
        RemoteMap.set(context, button, kind, action)
        nav?.back()
    }
    SettingsAction("Default (${defaultName(button)})", focusRequester = first, selected = current == RemoteAction.Default) {
        choose(RemoteAction.Default)
    }
    RemoteAction.FIXED_CHOICES.forEach { action ->
        SettingsAction(action.label, selected = current == action) { choose(action) }
    }
    SettingsLink("An app", value = (current as? RemoteAction.OpenApp)?.appLabel, title = "${button.label}: ${kind.label}") {
        AppList(button, kind)
    }
}

/** Every app with a TV entry, for a press that opens one; choosing goes back to the button's page. */
@Composable
private fun AppList(button: RemoteButton, kind: PressKind) {
    val context = LocalContext.current
    val nav = LocalPanelNav.current
    val apps by produceState<List<LaunchableApp>?>(null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context, icons = false) }
    }
    val list = apps
    if (list == null) {
        SettingsRow("Loading apps…")
        return
    }
    list.forEach { app ->
        SettingsAction(app.label) {
            RemoteMap.set(context, button, kind, RemoteAction.OpenApp(app.packageName, app.label))
            // Past the press's list, back to the button's page.
            nav?.back()
            nav?.back()
        }
    }
}

private fun defaultName(button: RemoteButton): String = when (button) {
    RemoteButton.NETFLIX -> "open Netflix"
    RemoteButton.BACK -> "back"
    else -> button.label.lowercase()
}
