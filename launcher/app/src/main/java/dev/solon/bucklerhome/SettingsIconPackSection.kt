package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App Thumbnails → Icon pack: any installed launcher icon pack, matched to apps by package name.
 * Apps the pack has no icon for keep their own; the tint suits single-colour packs. Packs stay out
 * of the Library unless shown here, and each can be uninstalled from here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPackSection() {
    val context = LocalContext.current
    var changed by remember { mutableIntStateOf(0) }
    // Looks again on return from an uninstall.
    val tick = rememberResumeTick() + changed
    val tinted = remember(tick) { IconPackStore.tinted(context) }
    val inLibrary = remember(tick) { IconPackStore.packsInLibrary(context) }
    val fillGaps = remember(tick) { IconPackStore.fillGaps(context) }
    val packs by produceState<List<IconPack>?>(null, tick) {
        value = withContext(Dispatchers.IO) { IconPacks.installed(context) }
    }
    // A chosen pack that has been uninstalled is no longer chosen.
    val chosen = remember(tick, packs) {
        val saved = IconPackStore.pack(context)
        val installed = packs
        if (saved != null && installed != null && installed.none { it.packageName == saved }) {
            IconPackStore.setPack(context, null)
            null
        } else {
            saved
        }
    }
    // How many of the TV's apps the chosen pack covers; reading a pack's list takes a moment.
    val covered by produceState<Pair<Int, Int>?>(null, chosen) {
        value = chosen?.let { pack ->
            withContext(Dispatchers.IO) {
                val packages = IconPacks.installedPackages(context)
                val apps = loadLaunchableApps(context).filterNot { it.packageName in packages }
                IconPacks.coverage(context, pack, apps.associate { it.packageName to it.label }) to apps.size
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
            else -> covered?.let { (n, total) ->
                val rest = if (fillGaps) "Buckler draws the other ${total - n} in its style" else "the rest keep their own"
                "The pack has icons for $n of your $total apps; $rest."
            } ?: "Reading the pack…"
        },
    )
    if (list.isNullOrEmpty()) return
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Apps' own icons", selected = chosen == null) {
            IconPackStore.setPack(context, null)
            changed++
        }
        list.forEach { pack ->
            SettingsAction(pack.label, selected = pack.packageName == chosen) {
                IconPackStore.setPack(context, pack.packageName)
                changed++
            }
        }
    }
    Spacer(modifier = Modifier.height(14.dp))
    SettingsRow("Icon packs are not apps to open, so they stay out of the Library unless shown.")
    FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
        SettingsAction("Show in Library", selected = inLibrary) {
            IconPackStore.setPacksInLibrary(context, !inLibrary)
            changed++
        }
        list.forEach { pack ->
            SettingsAction("Uninstall ${pack.label}") { uninstallPackage(context, pack.packageName) }
        }
    }
    if (chosen != null) {
        Spacer(modifier = Modifier.height(14.dp))
        SettingsRow(
            "Single-colour packs (Arcticons, Lawnicons) look best in the theme's text colour. Apps the pack has no " +
                "icon for can be drawn in its style: their logo traced as a thin line.",
        )
        FlowRow(horizontalArrangement = flow, verticalArrangement = flow) {
            SettingsAction("Fill missing icons", selected = fillGaps) {
                IconPackStore.setFillGaps(context, !fillGaps)
                changed++
            }
            SettingsAction("Theme colour", selected = tinted) {
                IconPackStore.setTinted(context, !tinted)
                changed++
            }
        }
    }
}
