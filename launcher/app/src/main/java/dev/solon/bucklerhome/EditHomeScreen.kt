package dev.solon.bucklerhome

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditHomeScreen(
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var layout by remember { mutableStateOf(HomeLayout.load(context)) }
    val apps = remember { loadLaunchableApps(context.packageManager) }

    fun persist(next: HomeLayout) {
        layout = next
        HomeLayout.save(context, next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .padding(horizontal = 56.dp, vertical = 40.dp),
    ) {
        Text(
            text = "Edit Home",
            color = ColorBrand,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Layout, shelves, and apps pins",
            color = ColorMuted,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        Text(text = "Cover layout", color = ColorSection, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoverLayout.entries.forEach { style ->
                val selected = layout.layout == style
                Chip(
                    label = style.name.lowercase().replaceFirstChar { it.titlecase() },
                    selected = selected,
                ) {
                    persist(layout.copy(layout = style))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Shelves", color = ColorSection, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(layout.shelves, key = { _, s -> s.id }) { index, shelf ->
                Surface(
                    onClick = {
                        val shelves = layout.shelves.toMutableList()
                        shelves[index] = shelf.copy(enabled = !shelf.enabled)
                        persist(layout.copy(shelves = shelves))
                    },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = ColorTile,
                        focusedContainerColor = ColorBrand,
                        contentColor = ColorText,
                        focusedContentColor = ColorOnBrand,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = shelf.title, fontSize = 17.sp)
                            Text(
                                text = "${shelf.type.wire()} · ${if (shelf.enabled) "on" else "off"}",
                                fontSize = 13.sp,
                                color = ColorMuted,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (index > 0) {
                                MiniChip("Up") {
                                    val shelves = layout.shelves.toMutableList()
                                    val tmp = shelves[index]
                                    shelves[index] = shelves[index - 1]
                                    shelves[index - 1] = tmp
                                    persist(layout.copy(shelves = shelves))
                                }
                            }
                            if (index < layout.shelves.lastIndex) {
                                MiniChip("Down") {
                                    val shelves = layout.shelves.toMutableList()
                                    val tmp = shelves[index]
                                    shelves[index] = shelves[index + 1]
                                    shelves[index + 1] = tmp
                                    persist(layout.copy(shelves = shelves))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Apps shelf pins", color = ColorSection, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Focus an app and press Select to pin/unpin",
                    color = ColorHint,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            itemsIndexed(
                apps.filterNot { it.isSystem }.take(40),
                key = { _, a -> a.packageName },
            ) { _, app ->
                val pinned = app.packageName in layout.appsPackages()
                Surface(
                    onClick = {
                        val pins = layout.appsPackages().toMutableList()
                        if (pinned) pins.remove(app.packageName) else pins.add(app.packageName)
                        persist(layout.withAppsPackages(pins))
                    },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (pinned) ColorFooter else ColorTile,
                        focusedContainerColor = ColorBrand,
                        contentColor = ColorText,
                        focusedContentColor = ColorOnBrand,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${if (pinned) "●" else "○"}  ${app.label}",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        fontSize = 15.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip("Reset from buckler file", selected = false) {
                val imported = HomeLayout.importIfPresent(context)
                if (!imported) {
                    // Re-read file even if already consumed — try again from disk
                    val file = HomeLayout.importFile(context)
                    if (file.isFile) {
                        runCatching {
                            persist(HomeLayout.fromJson(file.readText()))
                        }
                    } else {
                        persist(HomeLayout.default(DockPrefs.loadPins(context)))
                    }
                } else {
                    layout = HomeLayout.load(context)
                }
            }
            Chip("Done", selected = true, onClick = onDone)
        }
    }
}

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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            fontSize = 12.sp,
        )
    }
}
