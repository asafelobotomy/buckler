package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Appearance → Shelves: the rows of apps under the Main Stage. How tiles look, which
 * shelves show and in what order, which apps fill Up Next, and which apps are pinned to Apps.
 * (Edit Home, the header pencil, has the same options; arranging tiles happens on Home itself.)
 */
@Composable
fun ShelvesPane() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val layout = remember(tick) { HomeLayout.stored(context) }
    var sourcesFor by remember { mutableStateOf<ShelfConfig?>(null) }
    val apps by produceState(emptyList<LaunchableApp>()) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context, icons = false) }
    }

    fun persist(next: HomeLayout) {
        HomeLayout.save(context, next)
        tick++
    }

    fun moveShelf(index: Int, delta: Int) {
        val shelves = layout.shelves.toMutableList()
        val target = index + delta
        if (target !in shelves.indices) return
        shelves[index] = shelves[target].also { shelves[target] = shelves[index] }
        persist(layout.copy(shelves = shelves))
    }

    Column {
        SectionTitle("Shelves")
        SettingsRow("The rows of apps under the Main Stage. On Home, ◀ on a shelf's first tile reveals its own menu.")
        // Either header icon must reach both screens; Edit Home links back here too.
        SettingsAction("Open Edit Home") { openEditHome(context) }
        Spacer(modifier = Modifier.height(18.dp))
        LayoutPicker(layout.layout, layout.tileSize) { persist(layout.copy(layout = it)) }
        Spacer(modifier = Modifier.height(18.dp))
        TileSizePicker(layout.layout, layout.tileSize) { persist(layout.copy(tileSize = it)) }
        Spacer(modifier = Modifier.height(18.dp))
        PickerTitle("App names")
        SettingsAction(
            label = if (layout.showLabels) "Show under the artwork" else "Hidden",
            selected = layout.showLabels,
        ) { persist(layout.copy(showLabels = !layout.showLabels)) }

        Spacer(modifier = Modifier.height(24.dp))
        SectionTitle("Order and visibility")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            layout.shelves.forEachIndexed { index, shelf ->
                ShelfEditRow(
                    shelf = shelf,
                    canMoveUp = index > 0,
                    canMoveDown = index < layout.shelves.lastIndex,
                    onToggle = {
                        if (shelf.enabled && !layout.canHideShelf(shelf.id)) {
                            Toast.makeText(context, "Keep at least one shelf on Home", Toast.LENGTH_SHORT).show()
                        } else {
                            persist(layout.copy(shelves = layout.shelves.map { if (it.id == shelf.id) it.copy(enabled = !it.enabled) else it }))
                        }
                    },
                    onUp = { moveShelf(index, -1) },
                    onDown = { moveShelf(index, 1) },
                )
            }
        }

        layout.shelves.firstOrNull { it.type == ShelfType.WATCH_NEXT }?.let { upNext ->
            Spacer(modifier = Modifier.height(24.dp))
            SectionTitle("Up Next")
            SettingsRow(
                if (upNext.restrictSources) {
                    "From ${upNext.packages.size} chosen app${if (upNext.packages.size == 1) "" else "s"}, up to three cards each."
                } else {
                    "From every app that publishes, up to three cards each."
                },
            )
            SettingsAction("Choose sources") { sourcesFor = upNext }
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionTitle("Apps shelf pins")
        SettingsRow("OK on an app to pin or unpin it.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            apps.filterNot { it.isSystem }.forEach { app ->
                val pinned = app.packageName in layout.appsPackages()
                PinRow(label = app.label, pinned = pinned) {
                    persist(if (pinned) layout.withoutPinned(app.packageName) else layout.withPinned(app.packageName))
                }
            }
        }
    }
    sourcesFor?.let { shelf ->
        UpNextSourcesDialog(
            shelf = shelf,
            onApply = { restrict, packages ->
                persist(layout.withWatchNextSources(shelf.id, restrict, packages))
                sourcesFor = null
            },
            onDismiss = { sourcesFor = null },
        )
    }
}
