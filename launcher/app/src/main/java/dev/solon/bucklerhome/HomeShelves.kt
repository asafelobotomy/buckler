package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private val TileGap = 12.dp

/** What a shelf needs from Home besides its content; grouped so call sites stay readable. */
class ShelfActions(
    val onLaunch: (ShelfItem) -> Unit,
    val onLongPress: (ShelfItem) -> Unit,
    /** Left pressed on the first tile of the shelf. */
    val onShelfMenu: () -> Unit,
)

/** Focus bookkeeping while an app or a whole shelf is being moved. */
class ShelfFocus(
    /** First tile of the first shelf, so the header's Down key has a target. */
    val first: FocusRequester?,
    /** Overrides [first] on the first tile of this shelf while it is being moved. */
    val shelfRequester: FocusRequester? = null,
    val movingShelf: Boolean = false,
    val movingPackage: String? = null,
    /** Tile that owns [moveFocus]: the moved app, until focus has settled after a place / cancel. */
    val focusPackage: String? = null,
    val moveFocus: FocusRequester? = null,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShelfSection(
    shelf: ResolvedShelf,
    coverLayout: CoverLayout,
    tileSize: TileSize,
    actions: ShelfActions,
    focus: ShelfFocus,
) {
    Column {
        Text(
            text = if (focus.movingShelf) "▲▼  ${shelf.config.title}" else shelf.config.title,
            color = if (focus.movingShelf) ColorBrand else ColorSection,
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
            LibraryGrid(shelf.items, coverLayout, tileSize, actions, focus)
        } else {
            val isApps = shelf.config.type == ShelfType.APPS
            ShelfRow(
                items = shelf.items,
                coverLayout = coverLayout,
                tileSize = tileSize,
                actions = actions,
                focus = if (isApps) focus else focus.withoutAppMove(),
            )
        }
    }
}

private fun ShelfFocus.withoutAppMove() =
    ShelfFocus(first = first, shelfRequester = shelfRequester, movingShelf = movingShelf)

private fun ShelfFocus.firstTileRequester(index: Int, item: ShelfItem): FocusRequester? = when {
    focusPackage != null && item.packageName == focusPackage -> moveFocus
    index == 0 -> shelfRequester ?: first
    else -> null
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
    tileSize: TileSize,
    actions: ShelfActions,
    focus: ShelfFocus,
) {
    val tileWidth = coverSize(coverLayout, tileSize).width
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // As many tiles per row as fit, so Small shows more and Large fewer.
        val columns = ((maxWidth + TileGap) / (tileWidth + TileGap)).toInt().coerceAtLeast(1)
        Column(
            verticalArrangement = Arrangement.spacedBy(TileGap),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            items.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    row.forEachIndexed { col, item ->
                        CoverTile(
                            item = item,
                            layout = coverLayout,
                            tileSize = tileSize,
                            onLaunch = { actions.onLaunch(item) },
                            onLongPress = { actions.onLongPress(item) },
                            onLeftEdge = if (col == 0) actions.onShelfMenu else null,
                            focusRequester = if (rowIndex == 0 && col == 0) focus.shelfRequester ?: focus.first else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    items: List<ShelfItem>,
    coverLayout: CoverLayout,
    tileSize: TileSize,
    actions: ShelfActions,
    focus: ShelfFocus,
) {
    val listState = rememberLazyListState()
    val trackIndex = items.indexOfFirst { focus.focusPackage != null && it.packageName == focus.focusPackage }
    // Keep the moved tile composed and on screen as it slides, and after a cancel snaps it back;
    // a tile scrolled out of the lazy window would drop D-pad focus.
    LaunchedEffect(trackIndex) {
        if (trackIndex >= 0) listState.animateScrollToItem((trackIndex - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        contentPadding = PaddingValues(end = 16.dp),
    ) {
        // Item ids are de-duplicated in Shelves; duplicate LazyRow keys would crash.
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            CoverTile(
                item = item,
                layout = coverLayout,
                tileSize = tileSize,
                onLaunch = { actions.onLaunch(item) },
                onLongPress = { actions.onLongPress(item) },
                onLeftEdge = if (index == 0) actions.onShelfMenu else null,
                focusRequester = focus.firstTileRequester(index, item),
                moving = focus.movingPackage != null && item.packageName == focus.movingPackage,
            )
        }
    }
}
