package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private const val LIBRARY_COLUMNS = 5

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShelfSection(
    shelf: ResolvedShelf,
    coverLayout: CoverLayout,
    firstFocus: FocusRequester?,
    onLaunch: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
) {
    Column {
        Text(
            text = shelf.config.title,
            color = ColorSection,
            fontSize = 14.sp,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .semantics {
                    contentDescription = shelf.config.title
                    heading()
                },
        )
        if (shelf.items.isEmpty()) {
            Text(
                text = emptyShelfHint(shelf.config.type),
                color = ColorHint,
                fontSize = 14.sp,
            )
            return
        }
        if (shelf.config.type == ShelfType.LIBRARY) {
            LibraryGrid(shelf.items, coverLayout, onLaunch, onLongPress)
        } else {
            ShelfRow(shelf.items, coverLayout, firstFocus, onLaunch, onLongPress)
        }
    }
}

private fun emptyShelfHint(type: ShelfType): String = when (type) {
    ShelfType.WATCH_NEXT -> "Nothing in Up Next yet — apps publish here when you watch."
    ShelfType.SUGGESTED -> "Launch a few apps to build suggestions."
    ShelfType.APPS, ShelfType.LIBRARY, ShelfType.RECENTS -> "No items"
}

@Composable
private fun LibraryGrid(
    items: List<ShelfItem>,
    coverLayout: CoverLayout,
    onLaunch: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        items.chunked(LIBRARY_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    CoverTile(
                        item = item,
                        layout = coverLayout,
                        onLaunch = { onLaunch(item) },
                        onLongPress = { onLongPress(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    items: List<ShelfItem>,
    coverLayout: CoverLayout,
    firstFocus: FocusRequester?,
    onLaunch: (ShelfItem) -> Unit,
    onLongPress: (ShelfItem) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(end = 16.dp),
    ) {
        // Item ids are de-duplicated in Shelves; duplicate LazyRow keys would crash.
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            CoverTile(
                item = item,
                layout = coverLayout,
                onLaunch = { onLaunch(item) },
                onLongPress = { onLongPress(item) },
                focusRequester = if (index == 0) firstFocus else null,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FooterButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(180.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorFooter,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
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
