package dev.solon.bucklerhome

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Apps: apps not opened for a while, busiest in the background first, each with a way
 * to remove it. Your own apps uninstall; stock apps open Android's app page, where Disable is
 * (Buckler cannot disable apps itself; `buckler unused --apply` does it from a computer).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun AppsPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Rescan on return from an uninstall or an app page.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val access = remember(tick) { UnusedApps.hasAccess(context) }
    val days = remember(tick) { UnusedApps.days(context) }
    val notice = remember(tick) { UnusedApps.noticeOn(context) }
    val keptCount = remember(tick) { UnusedApps.kept(context).size }
    val unused by produceState<List<AppUsage>?>(null, tick) {
        value = withContext(Dispatchers.IO) {
            UnusedApps.scan(context, days).also { UnusedApps.export(context, it, days) }
        }
    }
    val flow = Arrangement.spacedBy(12.dp)

    Column {
        SectionTitle("Apps")
        SettingsRow(
            "Apps you have not opened for a while, from this TV's own usage records. Those still busy " +
                "in the background come first.",
        )
        if (!access) {
            SettingsRow("Reading app usage needs a one-time permission: ${SystemSettings.GRANT_COMMAND}")
            return@Column
        }
        PickerTitle("Unused after")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            UnusedApps.PERIOD_DAYS.forEach { d ->
                SettingsAction("$d days", selected = d == days) {
                    UnusedApps.setDays(context, d)
                    tick++
                }
            }
            SettingsAction("Tell me on Home", selected = notice) {
                UnusedApps.setNoticeOn(context, !notice)
                tick++
            }
            if (keptCount > 0) {
                SettingsAction("Show $keptCount kept again") {
                    UnusedApps.clearKept(context)
                    tick++
                }
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        val list = unused
        when {
            list == null -> SettingsRow("Checking…")
            list.isEmpty() -> SettingsRow("Every app has been opened in the last $days days.")
            else -> list.forEach { app -> UnusedAppRow(app) { tick++ } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun UnusedAppRow(app: AppUsage, onChanged: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(text = app.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
        val detail = listOfNotNull(UnusedApps.describeLastUse(app), UnusedApps.describeBackground(app))
        SettingsRow(detail.joinToString(" · "))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canUninstallPackage(context, app.packageName)) {
                SettingsAction("Uninstall") { uninstallPackage(context, app.packageName) }
            } else {
                SettingsAction("App info (Disable)") {
                    launchFirstThatWorks(
                        context,
                        "app info",
                        listOf(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)),
                            Intent(Settings.ACTION_APPLICATION_SETTINGS),
                        ),
                    )
                }
            }
            SettingsAction("Keep") {
                UnusedApps.keep(context, app.packageName)
                onChanged()
            }
        }
    }
}
