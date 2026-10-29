package dev.solon.bucklerhome

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Edit Home. Everything is reachable by D-pad: Done / Reset sit in the header (not below a long
 * list), BACK saves and leaves, and each shelf row has separate Toggle / Up / Down targets.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditHomeScreen(
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var layout by remember { mutableStateOf(HomeLayout.load(context)) }
    val apps by produceState(emptyList<LaunchableApp>()) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context, icons = false) }
    }

    BackHandler(onBack = onDone)

    fun persist(next: HomeLayout) {
        layout = next
        HomeLayout.save(context, next)
    }

    fun resetFromFileOrDefault() {
        if (HomeLayout.importIfPresent(context)) {
            layout = HomeLayout.load(context)
            return
        }
        persist(HomeLayout.default(layout.appsPackages()))
    }

    fun moveShelf(index: Int, delta: Int) {
        val shelves = layout.shelves.toMutableList()
        val target = index + delta
        if (target !in shelves.indices) return
        shelves[index] = shelves[target].also { shelves[target] = shelves[index] }
        persist(layout.copy(shelves = shelves))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .padding(horizontal = 56.dp, vertical = 40.dp)
            .autoFocus(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Edit Home",
                    color = ColorBrand,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Layout, app size, shelves and app pins · Back to finish",
                    color = ColorMuted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("Done", selected = true, onClick = onDone)
                Chip("Reset layout", selected = false, onClick = ::resetFromFileOrDefault)
                // Either header shortcut must reach both screens; the gear may be hidden.
                Chip("Settings", selected = false) {
                    safeLaunch(context, Intent(context, BucklerSettingsActivity::class.java), "Buckler Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                LayoutAndSizePickers(
                    style = layout.layout,
                    tileSize = layout.tileSize,
                    showLabels = layout.showLabels,
                    mainStage = layout.mainStage,
                    stageSize = layout.stageSize,
                    onStyle = { persist(layout.copy(layout = it)) },
                    onSize = { persist(layout.copy(tileSize = it)) },
                    onLabels = { persist(layout.copy(showLabels = it)) },
                    onStage = { persist(layout.copy(mainStage = it)) },
                    onStageSize = { persist(layout.copy(stageSize = it)) },
                )
            }
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "Shelves", color = ColorSection, fontSize = 14.sp)
            }
            itemsIndexed(layout.shelves, key = { _, s -> s.id }) { index, shelf ->
                ShelfEditRow(
                    shelf = shelf,
                    canMoveUp = index > 0,
                    canMoveDown = index < layout.shelves.lastIndex,
                    onToggle = {
                        if (shelf.enabled && !layout.canHideShelf(shelf.id)) {
                            Toast.makeText(context, "Keep at least one shelf on Home", Toast.LENGTH_SHORT).show()
                        } else {
                            val shelves = layout.shelves.toMutableList()
                            shelves[index] = shelf.copy(enabled = !shelf.enabled)
                            persist(layout.copy(shelves = shelves))
                        }
                    },
                    onUp = { moveShelf(index, -1) },
                    onDown = { moveShelf(index, 1) },
                )
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Apps shelf pins", color = ColorSection, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select an app to pin or unpin it",
                    color = ColorHint,
                    fontSize = 13.sp,
                )
            }
            itemsIndexed(
                apps.filterNot { it.isSystem },
                key = { _, a -> a.packageName },
            ) { _, app ->
                val pinned = app.packageName in layout.appsPackages()
                PinRow(label = app.label, pinned = pinned) {
                    persist(
                        if (pinned) layout.withoutPinned(app.packageName) else layout.withPinned(app.packageName),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ShelfEditRow(
    shelf: ShelfConfig,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onToggle,
            colors = rowColors(selected = shelf.enabled),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(text = shelf.title, fontSize = 17.sp)
                Text(
                    text = "${shelf.type.wire()} · ${if (shelf.enabled) "on — select to hide" else "off — select to show"}",
                    fontSize = 13.sp,
                )
            }
        }
        if (canMoveUp) MiniChip("Up", onUp)
        if (canMoveDown) MiniChip("Down", onDown)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PinRow(label: String, pinned: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = rowColors(selected = pinned),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "${if (pinned) "●" else "○"}  $label",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = 15.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun rowColors(selected: Boolean) = ClickableSurfaceDefaults.colors(
    containerColor = if (selected) ColorFooter else ColorTile,
    focusedContainerColor = ColorBrand,
    contentColor = ColorText,
    focusedContentColor = ColorOnBrand,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ColorBrand else ColorFooter,
            focusedContainerColor = ColorBrand,
            contentColor = if (selected) ColorOnBrand else ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            fontSize = 15.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorFooter,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
            focusedContentColor = ColorOnBrand,
        ),
        modifier = Modifier.width(72.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            fontSize = 13.sp,
        )
    }
}
