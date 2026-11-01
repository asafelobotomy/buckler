package dev.solon.bucklerhome

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs one SHIELD step at a time over Buckler's own adb link ([AdbSelf], [ShieldOps]), saying what happens. */
class ShieldRunner(private val context: Context, private val scope: CoroutineScope) {
    var status by mutableStateOf("Not connected yet.")
    var busy by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var journal by mutableIntStateOf(ShieldJournal.all(context).size)

    /** Whether anything has run yet, so a page shows [status] only once it says something. */
    var used by mutableStateOf(false)

    /** Runs [work] over a fresh connection off the main thread. */
    fun run(label: String, work: (ShieldOps) -> String) {
        if (busy) return
        busy = true
        used = true
        status = "$label…"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                try {
                    AdbSelf.connect(context) { status = "Allow debugging on the TV's prompt (tick Always allow)." }
                        .use { adb -> work(ShieldOps(context, adb)) }
                        .also { connected = true }
                } catch (e: Exception) {
                    if (e is AdbSelf.Failure) connected = false
                    AdbSelf.explain(e)
                }
            }
            journal = ShieldJournal.all(context).size
            busy = false
        }
    }
}

@Composable
fun rememberShieldRunner(): ShieldRunner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { ShieldRunner(context, scope) }
}

/**
 * Settings → Device (Global): the setup wizard, and the changes `buckler setup` makes from a
 * computer, made from the TV itself over Network debugging. The wizard builds on these; here each
 * is a row. SHIELD clean-up, beside it, turns apps off.
 */
@Composable
fun ShieldPane() {
    val context = LocalContext.current
    val runner = rememberShieldRunner()

    SectionTitle("Device")
    GrantNote()
    DateTimeSection()
    Spacer(modifier = Modifier.height(18.dp))
    KeyboardSection()
    Spacer(modifier = Modifier.height(18.dp))
    PowerSection(runner)
    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Setup")
    SettingsRow("The setup wizard walks through Buckler's look and, on a SHIELD, its clean-up, showing every change before making it.")
    SettingsAction("Run setup wizard") { openSetupWizard(context) }
    if (!SetupState.isShield()) return
    val back = remember(runner.status) { SetupState.cameBack(context) }
    if (back.isNotEmpty()) {
        SettingsPaneLink("Apps back on after an update", SettingsPane.SHIELD_CLEAN_UP, value = appCount(back.size))
    }

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Network debugging")
    SettingsRow(
        "Buckler makes its SHIELD changes from the TV itself over Network debugging (Developer options), " +
            "with no computer.",
    )
    SettingsRow(runner.status)
    SettingsAction(if (runner.connected) "Check connection" else "Connect") {
        runner.run("Connecting") { ops -> "Connected. Home is ${ops.homePackage() ?: "unknown"}." }
    }
    SystemScreenLink("Android developer options", SystemScreen.DEVELOPER)
    SystemScreenLink("SHIELD developer options", SystemScreen.SHIELD_DEVELOPER)

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Buckler")
    val missing = remember(runner.status) { ShieldOps.missingPermissions(context) + ShieldOps.missingAppops(context) }
    SettingsRow(
        when {
            missing.isEmpty() -> "Buckler has all its permissions."
            ShieldOps.restartsOnGrant(context) ->
                "Missing: ${missing.joinToString()}. Buckler restarts once after granting (Android restarts an app when it may install apps)."
            else -> "Missing: ${missing.joinToString()}."
        },
    )
    SettingsAction("Grant Buckler's permissions") {
        runner.run("Granting") { ops ->
            val failed = ops.grantBuckler()
            if (failed.isEmpty()) "Granted: system settings, installs and app usage." else "Not granted: ${failed.joinToString()}"
        }
    }
    SettingsAction("Make Buckler Home") {
        runner.run("Setting Home") { ops -> if (ops.setHome()) "Buckler is Home." else "Home is still ${ops.homePackage()}." }
    }
}

/**
 * Settings → Device → SHIELD clean-up: the CLI's clean-up profiles, turned off from the TV (a
 * second press confirms), apps that came back on after a system update, and restoring them all.
 */
@Composable
fun ShieldCleanUpPane() {
    val context = LocalContext.current
    val runner = rememberShieldRunner()
    var confirm by remember { mutableStateOf<String?>(null) }
    val data = remember { ShieldData.load(context) }

    SectionTitle("SHIELD clean-up")
    if (!SetupState.isShield()) {
        SettingsRow("Only on a SHIELD.")
        return
    }
    SettingsRow("Turns off SHIELD apps over Network debugging. Every app turned off is recorded and can be turned back on here.")
    SettingsRow(runner.status)
    val back = remember(runner.status) { SetupState.cameBack(context) }
    if (back.isNotEmpty()) {
        SettingsRow(
            "${appCount(back.size)} Buckler keeps off ${if (back.size == 1) "is" else "are"} on again, usually after a " +
                "system update: ${SetupApply.labels(context, back)}.",
        )
        SettingsAction(if (back.size == 1) "Turn it off again" else "Turn them off again") {
            runner.run("Turning them off") { ops ->
                val r = ops.disable(back, "came back after an update")
                "Turned off ${r.changed.size} again" + if (r.failed.isEmpty()) "." else "; ${r.failed.size} could not be."
            }
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("Profiles")
    data.profiles.values.forEach { profile ->
        val armed = confirm == profile.name
        SettingsRow("${profile.name}: ${profile.description} (${profile.packages.size} apps)")
        SettingsAction(if (armed) "Press again: turn off ${profile.name}" else "Turn off ${profile.name}") {
            if (!armed) {
                confirm = profile.name
                return@SettingsAction
            }
            confirm = null
            runner.run("Turning off ${profile.name}") { ops ->
                // Not what a chosen stage needs (Google's calendar sync for the calendar widgets).
                val r = ops.disable(profile.packages - SetupState.keptOn(context), profile.description)
                SetupState.setIntendedOff(context, SetupState.intendedOff(context) + r.changed + r.already)
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
    Spacer(modifier = Modifier.height(18.dp))
    val journal = runner.journal
    SettingsRow(
        if (journal == 0) "Buckler has not turned off any apps from the TV." else "Turned off from the TV: $journal apps.",
    )
    if (journal > 0) {
        val armed = confirm == RESTORE
        SettingsAction(if (armed) "Press again: restore $journal apps" else "Restore everything") {
            if (!armed) {
                confirm = RESTORE
                return@SettingsAction
            }
            confirm = null
            runner.run("Restoring") { ops ->
                val restored = ops.restore()
                SetupState.setIntendedOff(context, SetupState.intendedOff(context) - restored.toSet())
                "Turned back on: ${restored.size}."
            }
        }
    }
}

private const val RESTORE = "restore"

fun appCount(n: Int): String = if (n == 1) "1 app" else "$n apps"
