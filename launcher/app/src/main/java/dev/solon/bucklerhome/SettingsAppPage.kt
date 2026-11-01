package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * One app's page, opened from the Apps & storage lists: open it, force stop, clear its data, turn
 * it off or on (over Network debugging, recorded so SHIELD clean-up can restore it), uninstall, and
 * Android's own page for the rest (its cache, permissions). Buckler itself and the apps Android
 * needs are never stopped, cleared or turned off ([ShieldOps.mayChange]); [extra] adds the list's
 * own rows.
 */
@Composable
fun AppPage(packageName: String, detail: String?, extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val runner = rememberShieldRunner()
    val pm = context.packageManager
    var armed by remember { mutableStateOf<String?>(null) }
    val enabled = remember(runner.status) { runCatching { pm.getApplicationInfo(packageName, 0).enabled }.getOrDefault(true) }
    val launch = remember(enabled) { pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) }
    val guarded = remember { packageName == context.packageName || packageName in ShieldData.load(context).never }

    /** A row that does [work] on the second press, saying [confirm] after the first. */
    @Composable
    fun Confirmed(key: String, label: String, confirm: String, work: () -> Unit) {
        SettingsAction(if (armed == key) confirm else label) {
            if (armed != key) {
                armed = key
                return@SettingsAction
            }
            armed = null
            work()
        }
    }

    if (detail != null) SettingsRow(detail)
    if (launch != null) SettingsAction("Open") { safeLaunch(context, launch, "Open") }
    if (guarded) {
        SettingsRow(
            if (packageName == context.packageName) "This is Buckler, which cannot stop or turn off itself."
            else "Android needs this app, so Buckler does not stop, clear or turn it off.",
        )
    } else {
        SettingsAction("Force stop") {
            runner.run("Stopping") { ops -> if (ops.forceStop(packageName)) "Stopped." else "Could not stop it." }
        }
        Confirmed("clear", "Clear data", "Press again: delete everything it saved") {
            runner.run("Clearing") { ops -> if (ops.clearData(packageName)) "Cleared: it starts as new." else "Could not clear it." }
        }
        if (enabled) {
            Confirmed("off", "Turn off", "Press again to turn it off") {
                runner.run("Turning off") { ops ->
                    val r = ops.disable(listOf(packageName), "turned off in Settings")
                    if (r.changed.isNotEmpty()) "Turned off. SHIELD clean-up can turn it back on." else "Could not turn it off."
                }
            }
        } else {
            SettingsAction("Turn on") {
                runner.run("Turning on") { ops -> if (ops.turnOn(packageName)) "Turned on." else "Could not turn it on." }
            }
        }
    }
    if (canUninstallPackage(context, packageName)) SettingsAction("Uninstall") { uninstallPackage(context, packageName) }
    extra()
    SettingsAction("Android's app info", value = "↗") { openAppInfo(context, packageName) }
    if (runner.used) SettingsRow(runner.status)
}
