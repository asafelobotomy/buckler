package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App Thumbnails → Icon pack: any installed launcher icon pack, matched to apps by package name.
 * Apps the pack has no icon for keep their own; the tint suits single-colour packs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPackSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val chosen = remember(tick) { IconPackStore.pack(context) }
    val tinted = remember(tick) { IconPackStore.tinted(context) }
    val packs by produceState<List<IconPack>?>(null) {
        value = withContext(Dispatchers.IO) { IconPacks.installed(context) }
    }
    // How many of the TV's apps the chosen pack covers; reading a pack's list takes a moment.
    val covered by produceState<Pair<Int, Int>?>(null, chosen) {
        value = chosen?.let { pack ->
            withContext(Dispatchers.IO) {
                val apps = loadLaunchableApps(context).map { it.packageName }
                IconPacks.coverage(context, pack, apps) to apps.size
            }
        }
    }
    val flow = Arrangement.spacedBy(12.dp)

    PickerTitle("Icon pack")
    val list = packs
    SettingsRow(
        when {
            list == null -> "Looking for icon packs…"
            list.isEmpty() ->
                "No icon pack installed. Any launcher icon pack works, such as Arcticons " +
                    "(buckler launcher iconpack install arcticons) or Lawnicons."
            chosen == null -> "Apps show their own icons. A pack replaces each icon it has, matched by app."
            else -> covered?.let { (n, total) -> "The pack has icons for $n of your $total apps; the rest keep their own." }
                ?: "Reading the pack…"
        },
    )
    if (list.isNullOrEmpty()) return
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Apps' own icons", selected = chosen == null) {
            IconPackStore.setPack(context, null)
            tick++
        }
        list.forEach { pack ->
            SettingsAction(pack.label, selected = pack.packageName == chosen) {
                IconPackStore.setPack(context, pack.packageName)
                tick++
            }
        }
    }
    if (chosen != null) {
        SettingsRow("Single-colour packs (Arcticons, Lawnicons) look best in the theme's text colour.")
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsAction("Theme colour", selected = tinted) {
                IconPackStore.setTinted(context, !tinted)
                tick++
            }
        }
    }
}
