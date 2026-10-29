package dev.solon.bucklerhome

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Setup: the changes `buckler setup` makes from a computer, made from the TV
 * itself over Network debugging ([AdbSelf], [ShieldOps]). The setup wizard builds on this; here
 * each step is a button, and a step that disables apps asks for a second press.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShieldPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Not connected yet.") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var journal by remember { mutableIntStateOf(ShieldJournal.all(context).size) }
    val data = remember { ShieldData.load(context) }
    val flow = Arrangement.spacedBy(12.dp)

    /** Runs [work] over a fresh connection off the main thread, showing what happens. */
    fun run(label: String, work: (ShieldOps) -> String) {
        if (busy) return
        busy = true
        status = "$label…"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                try {
                    AdbSelf.connect(context) { status = "Allow debugging on the TV's prompt (tick Always allow)." }
                        .use { adb -> work(ShieldOps(context, adb)) }
                        .also { connected = true }
                } catch (e: AdbSelf.Failure.NotListening) {
                    connected = false
                    "Network debugging is off. Turn it on in Developer options, then connect."
                } catch (e: AdbSelf.Failure.NotAllowed) {
                    connected = false
                    "The TV did not allow Buckler. Connect again and choose Allow."
                } catch (e: Exception) {
                    "Stopped: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            journal = ShieldJournal.all(context).size
            busy = false
        }
    }

    SectionTitle("Setup")
    SettingsRow("The setup wizard walks through Buckler's look and, on a SHIELD, its clean-up, showing every change before making it.")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Run setup wizard") { openSetupWizard(context) }
    }
    if (!SetupState.isShield()) return
    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("SHIELD, step by step")
    SettingsRow(
        "Buckler can make its SHIELD changes from the TV itself, over Network debugging: no computer " +
            "needed. Every app it turns off is recorded and can be turned back on here.",
    )
    SettingsRow(status)
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction(if (connected) "Check connection" else "Connect") {
            run("Connecting") { ops -> "Connected. Home is ${ops.homePackage() ?: "unknown"}." }
        }
        SettingsAction("Developer options") {
            launchFirstThatWorks(
                context,
                "Developer options",
                listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
            )
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Buckler")
    val missing = remember(status) { ShieldOps.missingPermissions(context) + ShieldOps.missingAppops(context) }
    SettingsRow(
        when {
            missing.isEmpty() -> "Buckler has all its permissions."
            ShieldOps.restartsOnGrant(context) ->
                "Missing: ${missing.joinToString()}. Buckler restarts once after granting (Android restarts an app when it may install apps)."
            else -> "Missing: ${missing.joinToString()}."
        },
    )
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Grant Buckler's permissions") {
            run("Granting") { ops ->
                val failed = ops.grantBuckler()
                if (failed.isEmpty()) "Granted: system settings, installs and app usage." else "Not granted: ${failed.joinToString()}"
            }
        }
        SettingsAction("Make Buckler Home") {
            run("Setting Home") { ops -> if (ops.setHome()) "Buckler is Home." else "Home is still ${ops.homePackage()}." }
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Clean up")
    data.profiles.values.forEach { profile ->
        SettingsRow("${profile.name}: ${profile.description} (${profile.packages.size} apps)")
    }
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        data.profiles.values.forEach { profile ->
            val armed = confirm == profile.name
            SettingsAction(if (armed) "Press again: turn off ${profile.name}" else "Turn off ${profile.name}") {
                if (!armed) {
                    confirm = profile.name
                    return@SettingsAction
                }
                confirm = null
                run("Turning off ${profile.name}") { ops ->
                    val r = ops.disable(profile.packages, profile.description)
                    listOfNotNull(
                        "Turned off ${r.changed.size}",
                        "${r.already.size} already off",
                        r.missing.size.takeIf { it > 0 }?.let { "$it not on this TV" },
                        r.blocked.size.takeIf { it > 0 }?.let { "$it kept (never list or Home)" },
                        r.failed.size.takeIf { it > 0 }?.let { "$it failed" },
                    ).joinToString(", ") + "."
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(18.dp))
    SettingsRow(
        if (journal == 0) "Buckler has not turned off any apps from the TV." else "Turned off from the TV: $journal apps.",
    )
    if (journal > 0) {
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            val armed = confirm == RESTORE
            SettingsAction(if (armed) "Press again: restore $journal apps" else "Restore everything") {
                if (!armed) {
                    confirm = RESTORE
                    return@SettingsAction
                }
                confirm = null
                run("Restoring") { ops -> "Turned back on: ${ops.restore().size}." }
            }
        }
    }
}

private const val RESTORE = "restore"
