package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Apps & storage → Unused apps: apps not opened for a while, busiest in the background
 * first, each opening its [AppPage] to uninstall it, turn it off, or keep it.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun AppsPane() {
    val context = LocalContext.current
    var changed by remember { mutableIntStateOf(0) }
    // Rescan on return from an uninstall or an app page, and after a change here.
    val tick = rememberResumeTick() + changed
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
        SectionTitle("Unused apps")
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
                    changed++
                }
            }
            SettingsToggle("Tell me on Home", notice) {
                UnusedApps.setNoticeOn(context, !notice)
                changed++
            }
            if (keptCount > 0) {
                SettingsAction("Show $keptCount kept again") {
                    UnusedApps.clearKept(context)
                    changed++
                }
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        val list = unused
        when {
            list == null -> SettingsRow("Checking…")
            list.isEmpty() -> SettingsRow("Every app has been opened in the last $days days.")
            else -> list.forEach { app -> UnusedAppRow(app) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun UnusedAppRow(app: AppUsage) {
    val context = LocalContext.current
    val detail = listOfNotNull(UnusedApps.describeLastUse(app), UnusedApps.describeBackground(app)).joinToString(" · ")
    SettingsLink(app.label) {
        val nav = LocalPanelNav.current
        AppPage(app.packageName, detail) {
            SettingsAction("Keep (leave off this list)") {
                UnusedApps.keep(context, app.packageName)
                nav?.back()
            }
        }
    }
}
