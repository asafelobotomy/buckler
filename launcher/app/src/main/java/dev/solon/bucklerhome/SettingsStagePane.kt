package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings → Appearance → Main Stage: which widget and how tall. The widget's own options are its
 * page under Widgets, one row away.
 */
@Composable
fun MainStagePane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val layout = remember(tick) { HomeLayout.stored(context) }

    fun persist(next: HomeLayout) {
        HomeLayout.save(context, next)
        tick++
    }

    SectionTitle("Main Stage")
    SettingsRow("The band under the header. On Home, hold OK on it to switch widget.")
    SettingsChoice("Shows", MainStage.entries, layout.mainStage, { it.label }) { persist(layout.copy(mainStage = it)) }
    SettingsPaneLink("${layout.mainStage.label} options", SettingsPane.forStage(layout.mainStage))
    Spacer(modifier = Modifier.height(18.dp))
    StageSizePicker(layout.stageSize, title = "Size") { persist(layout.copy(stageSize = it)) }
}

/** Which companion the stages draw; it goes about its day by the clock and the weather. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionPicker(current: CompanionKind, onChanged: () -> Unit) {
    val context = LocalContext.current
    SectionTitle("Companion")
    SettingsRow("Sleeps at night, wakes at dawn, and keeps busy through the day; it shelters when it rains.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompanionKind.entries.forEach { kind ->
            SettingsAction(kind.label, selected = kind == current) {
                StagePrefs.setCompanion(context, kind)
                onChanged()
            }
        }
    }
}

/** Media access status; the button only shows while access is missing. */
@Composable
fun MediaAccessSection() {
    val context = LocalContext.current
    val grant = rememberMediaAccessGrant()
    // Reading the counter looks again once Buckler has granted access itself.
    val access = MediaAccess.granted.let { nowPlayingAccessGranted(context) }
    SettingsRow(if (access) "Media access: granted." else "Media access: not granted, so nothing playing can be shown.")
    if (access) return
    SettingsAction("Grant media access") { grant() }
}
