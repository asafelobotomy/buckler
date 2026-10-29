package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
 * Settings → Remote: the remote drawn with what each button does. Netflix, Back and the media
 * keys are Buckler's (an accessibility service it switches on itself); ≡ uses SHIELD's own option.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RemotePane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<RemoteButton?>(null) }
    // After a dialog closes, focus goes back to the row that opened it.
    val requesters = remember { RemoteButton.entries.associateWith { FocusRequester() } }
    var refocus by remember { mutableStateOf<RemoteButton?>(null) }
    LaunchedEffect(refocus) {
        val button = refocus ?: return@LaunchedEffect
        delay(50)
        runCatching { requesters.getValue(button).requestFocus() }
        refocus = null
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
        SectionTitle("Remote")
        SettingsRow(
            "Choose what your remote's buttons do on a press, a double press or a hold (≡ through SHIELD's " +
                "own option). Only a button with a double press waits for a second press.",
        )
        PickerTitle("Your remote")
        SettingsRow(
            when {
                chosen != null -> "Set to: ${chosen.label}."
                detected != null -> "Found: ${detected.label}."
                model != null -> "Not connected right now; last seen: ${model.label}."
                else -> "Buckler could not tell which remote this is. Choose it:"
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAction("Find automatically", selected = chosen == null) {
                RemoteModels.choose(context, null)
                tick++
            }
            RemoteModel.entries.forEach { m ->
                SettingsAction(m.short, selected = chosen == m) {
                    RemoteModels.choose(context, m)
                    tick++
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                restarting -> SettingsAction("Turning on…") {}
                stalled -> SettingsAction("Turn on again") {
                    restarting = true
                    RemoteMap.restartService(context) {
                        restarting = false
                        tick++
                    }
                }
                else -> SettingsAction(if (on) "Remote buttons: on" else "Turn on remote buttons", selected = on) {
                    if (!RemoteMap.setServiceOn(context, !on)) {
                        Toast.makeText(context, "Run: ${SystemSettings.GRANT_COMMAND}", Toast.LENGTH_LONG).show()
                    }
                    tick++
                }
            }
        }
        if (stalled && !restarting) {
            SettingsRow("Remote buttons stopped: Android stops them when Buckler is force-stopped or crashes.")
        }
        if (!on && !stalled) SettingsRow("Off: every button does what it always does. ≡ still follows SHIELD's option.")
        Spacer(modifier = Modifier.height(16.dp))
        if (model == null) return@Column
        if (!model.drawn) SettingsRow("Buttons Buckler can change on most remotes:")
        key(tick) {
            RemoteMapper(
                model = model,
                describe = ::describe,
                remapped = { it.mapping != RemoteButton.Mapping.FIXED && describe(it) != "default" },
                requesters = requesters,
                onPick = { button ->
                    if (button.mapping == RemoteButton.Mapping.FIXED) {
                        Toast.makeText(context, "${button.label}: ${button.fixedReason}", Toast.LENGTH_LONG).show()
                    } else {
                        picking = button
                    }
                },
            )
        }
    }
    picking?.let { button ->
        ButtonDialog(
            button,
            onDismiss = {
                picking = null
                tick++
                refocus = button
            },
        )
    }
}

/** One button's three presses; each opens the list of what it can do. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ButtonDialog(button: RemoteButton, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var kind by remember { mutableStateOf<PressKind?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 520.dp, maxWidth = 820.dp, modifier = Modifier.autoFocus(kind)) {
            Column {
                Text(button.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
                val chosen = kind
                if (chosen == null) {
                    Text(
                        if (button.mapping == RemoteButton.Mapping.SHIELD) "SHIELD's own ≡ options." else "OK on a row to change it.",
                        fontSize = 14.sp, color = ColorMuted, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    )
                    key(tick) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PressKind.entries.forEach { k ->
                                val now = if (button.mapping == RemoteButton.Mapping.SHIELD) {
                                    ShieldMenuButton.label(context, k)
                                } else {
                                    RemoteMap.action(context, button, k).label
                                }
                                SettingsAction("${k.label}: $now") { kind = k }
                            }
                            SettingsAction("Done", onClick = onDismiss)
                        }
                    }
                } else {
                    Text(chosen.label, fontSize = 15.sp, color = ColorMuted, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                    ActionChoices(button, chosen) {
                        kind = null
                        tick++
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ActionChoices(button: RemoteButton, kind: PressKind, onDone: () -> Unit) {
    val context = LocalContext.current
    var pickApp by remember { mutableStateOf(false) }
    val flow = Arrangement.spacedBy(10.dp)
    if (button.mapping == RemoteButton.Mapping.SHIELD) {
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            ShieldMenuButton.CHOICES.forEach { (value, label) ->
                SettingsAction(label, selected = ShieldMenuButton.isChosen(context, kind, value)) {
                    if (!ShieldMenuButton.set(context, kind, value)) {
                        Toast.makeText(context, "Run: ${SystemSettings.GRANT_COMMAND}", Toast.LENGTH_LONG).show()
                    }
                    onDone()
                }
            }
        }
        return
    }
    val current = RemoteMap.action(context, button, kind)
    fun choose(action: RemoteAction) {
        RemoteMap.set(context, button, kind, action)
        onDone()
    }
    if (pickApp) {
        val apps by produceState<List<LaunchableApp>>(emptyList()) {
            value = withContext(Dispatchers.IO) { loadLaunchableApps(context, icons = false) }
        }
        FlowRow(
            horizontalArrangement = flow,
            verticalArrangement = flow,
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        ) {
            apps.forEach { app -> SettingsAction(app.label) { choose(RemoteAction.OpenApp(app.packageName, app.label)) } }
        }
        return
    }
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        val default = "Default (${defaultName(button)})"
        SettingsAction(default, selected = current == RemoteAction.Default) { choose(RemoteAction.Default) }
        RemoteAction.FIXED_CHOICES.forEach { action ->
            SettingsAction(action.label, selected = current == action) { choose(action) }
        }
        val appLabel = (current as? RemoteAction.OpenApp)?.let { "App: ${it.appLabel}" } ?: "An app…"
        SettingsAction(appLabel, selected = current is RemoteAction.OpenApp) { pickApp = true }
    }
}

private fun defaultName(button: RemoteButton): String = when (button) {
    RemoteButton.NETFLIX -> "open Netflix"
    RemoteButton.BACK -> "back"
    else -> button.label.lowercase()
}
