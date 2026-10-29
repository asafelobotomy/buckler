package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HEADER_GAP = 14.dp
private val SHELF_GAP = 20.dp

/** How far the shelf list reaches into the side margins, for a zoomed tile at either end. */
private val LIST_BLEED = 16.dp

/** Header, Main Stage, and shelves. State and dialogs stay in [HomeScreen]. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeColumn(
    config: DisplayConfig,
    layout: HomeLayout,
    clock: String,
    date: String,
    weather: Weather?,
    headlines: List<Headline>,
    stageHeadlines: List<Headline>,
    forecast: Forecast?,
    net: NetState,
    nowPlaying: NowPlayingModel,
    actions: HeaderActions,
    shelves: List<ResolvedShelf>,
    /** False while the shelves are still loading: an empty list then is not an empty Home. */
    shelvesLoaded: Boolean,
    listState: LazyListState,
    scope: CoroutineScope,
    move: MoveMode,
    shelfMove: MoveMode,
    stageFocus: FocusRequester,
    firstFocus: FocusRequester,
    stageDown: FocusRequester,
    shelfRequester: FocusRequester,
    moveFocus: FocusRequester,
    focusShelfId: String?,
    focusPkg: String?,
    onLaunch: (ShelfItem) -> Unit,
    onEdit: (ShelfItem) -> Unit,
    onShelfMenu: (ShelfConfig) -> Unit,
    onOpenStory: (Headline) -> Unit,
    onHoldStage: () -> Unit,
    onPickStage: (MainStage) -> Unit,
    onPickSpan: (WeatherSpan) -> Unit,
    onResetHome: () -> Unit,
    onStepMove: (Int) -> Unit,
    onPlaceMove: () -> Unit,
    onStepShelf: (Int) -> Unit,
    onPlaceShelf: () -> Unit,
) {
    fun leaveStage() {
        scope.launch {
            if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            if (runCatching { stageDown.requestFocus(); true }.getOrDefault(false)) return@launch
            delay(48)
            runCatching { stageDown.requestFocus() }
        }
    }
    val focusManager = LocalFocusManager.current
    val headerEnd = remember { FocusRequester() }
    val bottom = remember { FocusRequester() }
    val homeOptions = if (shelvesLoaded) needsHomeOptions(config, shelves) else !config.reachesSettings
    val rows = shelves.size + if (homeOptions) 1 else 0
    val hasHeaderEnd = config.headerVisible && headerEndElement(config, actions.onPlayStore != null) != null
    // Down past the last row: the rightmost header icon, or the stage when the header has none.
    fun wrapToTop() {
        val top = if (hasHeaderEnd) headerEnd else stageFocus
        if (!runCatching { top.requestFocus(); true }.getOrDefault(false)) runCatching { stageFocus.requestFocus() }
        scope.launch { listState.scrollToItem(0) }
    }
    // Up past the top: the last shelf (or Home options), which may be scrolled out of the list.
    fun wrapToBottom() {
        if (rows == 0) return
        scope.launch {
            listState.scrollToItem(rows - 1)
            if (runCatching { bottom.requestFocus(); true }.getOrDefault(false)) return@launch
            delay(48)
            runCatching { bottom.requestFocus() }
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .moveModeKeys(move, onStep = onStepMove, onPlace = onPlaceMove)
            .moveModeKeys(shelfMove, onStep = onStepShelf, onPlace = onPlaceShelf)
            .padding(start = 56.dp, top = 28.dp, end = 56.dp, bottom = 8.dp),
    ) {
        val density = LocalDensity.current
        val tile = coverSize(layout.layout, layout.tileSize, layout.showLabels, density)
        val shelfHeight = shelfBlock(tile, density)
        // The header's real height (its size, the ticker, text size), measured; a guess until then.
        var headerHeight by remember { mutableStateOf(96.dp) }
        val header = if (config.headerVisible) headerHeight + HEADER_GAP else 0.dp
        val metrics = stageMetrics(maxHeight, shelfHeight, layout.stageSize, header)
        Column(modifier = Modifier.fillMaxSize()) {
        if (config.headerVisible) {
            HomeHeader(
                clock = clock,
                date = date,
                config = config,
                weather = weather,
                headlines = headlines,
                net = net,
                nowPlaying = if (layout.mainStage == MainStage.NOW_PLAYING) null else nowPlaying,
                actions = actions,
                below = stageFocus,
                endFocus = headerEnd,
                modifier = Modifier
                    .wrapFocus(FocusDirection.Up, focusManager, ::wrapToBottom)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
            )
            Spacer(modifier = Modifier.height(HEADER_GAP))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = metrics.band)
                .clipToBounds()
                .wrapFocus(FocusDirection.Up, focusManager, ::wrapToBottom)
                .onFocusChanged { state ->
                    if (state.hasFocus) scope.launch { listState.scrollToItem(0) }
                },
        ) {
            MainStage(
                mode = layout.mainStage,
                headlines = stageHeadlines,
                newsSources = config.stageNews,
                newsPace = config.newsPace,
                forecast = forecast,
                place = config.stageWeatherPlace,
                fahrenheit = config.stageWeatherFahrenheit,
                use24h = config.use24h,
                nowPlaying = nowPlaying,
                stageFocus = stageFocus,
                onDown = ::leaveStage,
                onOpenStory = onOpenStory,
                onHold = onHoldStage,
                onPickStage = onPickStage,
                onPickSpan = onPickSpan,
                metrics = metrics,
                weatherSpan = config.weatherSpan,
            )
        }
        Spacer(modifier = Modifier.height(STAGE_GAP))

        val topShelf = shelves.firstOrNull()
        val firstFilledId = shelves.firstOrNull { it.items.isNotEmpty() }?.config?.id
        val lastShelf = shelves.lastOrNull()?.takeIf { !homeOptions }
        // The focused shelf (or Library row) moves to the top of the list, whole; the next one
        // peeks in below and fades out. Space after the end lets the last ones reach the top too.
        fun showRow(index: Int, offset: Int) {
            scope.launch { listState.animateScrollToItem(index, offset) }
        }
        // A new tile size or layout changes every offset: start the shelf in view at its title again.
        LaunchedEffect(layout.layout, layout.tileSize, layout.showLabels) {
            if (listState.firstVisibleItemScrollOffset != 0) listState.scrollToItem(listState.firstVisibleItemIndex)
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val listHeight = maxHeight
        // Focus would otherwise also scroll the list, just far enough, and undo the placement.
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScroll) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .bleed(LIST_BLEED)
                .fadeBottom((listHeight - shelfHeight).coerceIn(0.dp, 40.dp))
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = LIST_BLEED,
                end = LIST_BLEED,
                // Enough for a Library's last row, without its shelf title, to reach the top.
                bottom = (listHeight - tile.height - zoomRoom(tile)).coerceAtLeast(0.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(SHELF_GAP),
        ) {
            itemsIndexed(shelves, key = { _, it -> it.config.id }) { index, shelf ->
              // Rows inside still scroll sideways to the focused tile.
              CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyAsNeeded) {
                val id = shelf.config.id
                val last = shelf.config.id == lastShelf?.config?.id
                val libraryBottom = last && shelf.config.type == ShelfType.LIBRARY && shelf.items.isNotEmpty()
                // Stage-down and wrap targets go on the shelf container: a lazy row's first tile is
                // disposed once scrolled, and a request to it would silently do nothing.
                val targets = listOfNotNull(
                    stageDown.takeIf { shelf.config.id == topShelf?.config?.id },
                    bottom.takeIf { last && !libraryBottom },
                )
                val library = shelf.config.type == ShelfType.LIBRARY && shelf.items.isNotEmpty()
                Box(
                    Modifier
                        .then(if (last) Modifier.wrapFocus(FocusDirection.Down, focusManager, ::wrapToTop) else Modifier)
                        .focusGroupTarget(targets)
                        // The Library places each of its rows itself.
                        .onFocusChanged { if (it.hasFocus && !library && !move.active) showRow(index, 0) },
                ) {
                    ShelfSection(
                        shelf = shelf,
                        coverLayout = layout.layout,
                        tileSize = layout.tileSize,
                        showLabels = layout.showLabels,
                        actions = ShelfActions(
                            onLaunch = onLaunch,
                            onLongPress = onEdit,
                            onShelfMenu = { onShelfMenu(shelf.config) },
                        ),
                        focus = ShelfFocus(
                            first = if (shelf.config.id == firstFilledId) firstFocus else null,
                            shelfRequester = if (id == focusShelfId) shelfRequester else null,
                            movingShelf = id == shelfMove.target,
                            movingPackage = move.target,
                            focusPackage = focusPkg,
                            moveFocus = moveFocus,
                            up = if (shelf.config.id == topShelf?.config?.id) stageFocus else null,
                            bottom = if (libraryBottom) bottom else null,
                        ),
                        onRowFocused = { offset -> if (!move.active) showRow(index, offset) },
                    )
                }
              }
            }
            if (homeOptions) {
                item(key = "home_options") {
                    Box(
                        Modifier
                            .wrapFocus(FocusDirection.Down, focusManager, ::wrapToTop)
                            .onFocusChanged { if (it.hasFocus) showRow(shelves.size, 0) },
                    ) {
                        HomeOptionsCard(
                            onReset = onResetHome,
                            onSettings = actions.onSettings,
                            fromStage = if (shelves.isEmpty()) stageDown else null,
                            up = if (shelves.isEmpty()) stageFocus else null,
                            bottom = bottom,
                        )
                    }
                }
            }
        }
        }
        }

        val hint = when {
            move.active -> "Moving app — ◀ ▶ to move · OK to place · Back to cancel"
            shelfMove.active -> "Moving shelf — ▲ ▼ to move · OK to place · Back to cancel"
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                color = ColorBrand,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        }
    }
}
