package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val TileGap = 12.dp

/** What a shelf needs from Home besides its content; grouped so call sites stay readable. */
class ShelfActions(
    val onLaunch: (ShelfItem) -> Unit,
    val onLongPress: (ShelfItem) -> Unit,
    /** Circle at the start of the shelf. */
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
    /** Main Stage above this shelf, when this is the first shelf. */
    val up: FocusRequester? = null,
    /** Down from the Main Stage. Kept on the first shelf focus should land on. */
    val fromStage: FocusRequester? = null,
    /** Wrap target for Up from the top of Home, when this is the last shelf. */
    val bottom: FocusRequester? = null,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShelfSection(
    shelf: ResolvedShelf,
    coverLayout: CoverLayout,
    tileSize: TileSize,
    showLabels: Boolean,
    actions: ShelfActions,
    focus: ShelfFocus,
) {
    val menuFocus = remember { FocusRequester() }
    var showOptions by remember(shelf.config.id) { mutableStateOf(false) }
    var leaveOptions by remember(shelf.config.id) { mutableStateOf(false) }
    val artHeight = coverSize(coverLayout, tileSize).artHeight
    LaunchedEffect(showOptions) {
        if (!showOptions) {
            leaveOptions = false
            return@LaunchedEffect
        }
        delay(40)
        val focused = runCatching {
            menuFocus.requestFocus()
            true
        }.getOrDefault(false)
        if (!focused) showOptions = false
    }
    Column(modifier = Modifier.clipToBounds()) {
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
        Row(verticalAlignment = Alignment.Top) {
            if (showOptions) {
                Box(modifier = Modifier.height(artHeight), contentAlignment = Alignment.Center) {
                    ShelfOptionsButton(
                        description = "${shelf.config.title} options",
                        onClick = actions.onShelfMenu,
                        up = focus.up,
                        focusRequester = menuFocus,
                        onLeave = { leaveOptions = true },
                        onFocusLost = { if (leaveOptions) showOptions = false },
                    )
                }
                Spacer(modifier = Modifier.width(TileGap))
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    shelf.items.isEmpty() -> EmptyShelfHint(
                        type = shelf.config.type,
                        up = focus.up,
                        focusRequester = focus.shelfRequester ?: focus.fromStage ?: focus.first,
                        bottom = focus.bottom,
                        onLeft = { showOptions = true },
                    )
                    shelf.config.type == ShelfType.LIBRARY ->
                        LibraryGrid(
                            shelf.items, coverLayout, tileSize, showLabels, actions, focus,
                            onLeftEdge = { showOptions = true },
                        )
                    else -> {
                        val isApps = shelf.config.type == ShelfType.APPS
                        ShelfRow(
                            items = shelf.items,
                            coverLayout = coverLayout,
                            tileSize = tileSize,
                            showLabels = showLabels,
                            actions = actions,
                            focus = if (isApps) focus else focus.withoutAppMove(),
                            onLeftEdge = { showOptions = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfOptionsButton(
    description: String,
    onClick: () -> Unit,
    up: FocusRequester?,
    focusRequester: FocusRequester,
    onLeave: () -> Unit,
    onFocusLost: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = CircleShape
    AnnounceOnFocus(focused, description)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .scale(focusZoom(focused, 1.08f))
            .clip(shape)
            .background(if (focused) ColorBrand else ColorTile)
            .strongFocusRing(focused, shape)
            .semantics { contentDescription = description }
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val leaving = event.key == Key.DirectionRight ||
                    event.key == Key.DirectionUp ||
                    event.key == Key.DirectionDown
                if (event.type == KeyEventType.KeyDown && leaving) onLeave()
                false
            }
            .onFocusChanged { if (!it.isFocused) onFocusLost() }
            .then(if (up != null) Modifier.focusProperties { this.up = up } else Modifier)
            .focusable(interactionSource = interaction)
            .onSelectKeyUp(onClick)
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
    ) {
        Image(
            painter = painterResource(R.drawable.ic_more),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (focused) ColorOnBrand else ColorText),
            modifier = Modifier.size(28.dp),
        )
    }
}

private fun ShelfFocus.withoutAppMove() = ShelfFocus(
    first = first,
    shelfRequester = shelfRequester,
    movingShelf = movingShelf,
    up = up,
    fromStage = fromStage,
    bottom = bottom,
)

private fun FocusRequester?.asRequester(): Modifier =
    if (this != null) Modifier.focusRequester(this) else Modifier

private fun ShelfFocus.firstTileRequester(index: Int, item: ShelfItem): FocusRequester? = when {
    focusPackage != null && item.packageName == focusPackage -> moveFocus
    index == 0 -> shelfRequester ?: first
    else -> null
}

@Composable
private fun EmptyShelfHint(
    type: ShelfType,
    up: FocusRequester?,
    focusRequester: FocusRequester?,
    bottom: FocusRequester?,
    onLeft: () -> Unit,
) {
    // A D-pad stop like any shelf: Left opens the shelf circle, OK does nothing.
    val modifier = Modifier
        .padding(top = 18.dp)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .then(if (bottom != null) Modifier.focusRequester(bottom) else Modifier)
        .then(if (up != null) Modifier.focusProperties { this.up = up } else Modifier)
        .onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown && !event.isKeyRepeat()) onLeft()
            true
        }
        .focusable()
    Text(
        text = emptyShelfHint(type),
        color = ColorHint,
        fontSize = 14.sp,
        modifier = modifier,
    )
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
    showLabels: Boolean,
    actions: ShelfActions,
    focus: ShelfFocus,
    onLeftEdge: () -> Unit,
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
            val rows = items.chunked(columns)
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    row.forEachIndexed { col, item ->
                        CoverTile(
                            item = item,
                            modifier = focus.bottom.takeIf { rowIndex == rows.lastIndex && col == 0 }.asRequester(),
                            layout = coverLayout,
                            tileSize = tileSize,
                            showLabels = showLabels,
                            onLaunch = { actions.onLaunch(item) },
                            onLongPress = { actions.onLongPress(item) },
                            onLeftEdge = if (rowIndex == 0 && col == 0) onLeftEdge else null,
                            up = if (rowIndex == 0) focus.up else null,
                            focusRequester = if (rowIndex == 0 && col == 0) focus.shelfRequester ?: focus.first else null,
                            alsoRequester = if (rowIndex == 0 && col == 0) focus.fromStage else null,
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
    showLabels: Boolean,
    actions: ShelfActions,
    focus: ShelfFocus,
    onLeftEdge: () -> Unit,
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
                showLabels = showLabels,
                onLaunch = { actions.onLaunch(item) },
                onLongPress = { actions.onLongPress(item) },
                modifier = focus.bottom.takeIf { index == 0 }.asRequester(),
                onLeftEdge = if (index == 0) onLeftEdge else null,
                up = focus.up,
                focusRequester = focus.firstTileRequester(index, item),
                alsoRequester = if (index == 0) focus.fromStage else null,
                moving = focus.movingPackage != null && item.packageName == focus.movingPackage,
            )
        }
    }
}
