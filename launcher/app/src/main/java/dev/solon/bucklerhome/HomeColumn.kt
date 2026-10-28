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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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

/** Header, Main Stage, and shelves. State and dialogs stay in [HomeScreen]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeColumn(
    config: DisplayConfig,
    layout: HomeLayout,
    clock: String,
    date: String,
    weather: Weather?,
    headlines: List<Headline>,
    forecast: Forecast?,
    net: NetState,
    nowPlaying: NowPlayingModel,
    actions: HeaderActions,
    shelves: List<ResolvedShelf>,
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
    val homeOptions = needsHomeOptions(config, shelves)
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
        val shelfArt = coverSize(layout.layout, layout.tileSize).artHeight
        val metrics = stageMetrics(maxHeight, shelfArt, layout.stageSize, config.headerVisible)
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
                modifier = Modifier.wrapFocus(FocusDirection.Up, focusManager, ::wrapToBottom),
            )
            Spacer(modifier = Modifier.height(14.dp))
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
                headlines = headlines,
                newsSources = config.newsSources,
                newsPace = config.newsPace,
                forecast = forecast,
                place = config.place,
                fahrenheit = config.fahrenheit,
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
        Spacer(modifier = Modifier.height(20.dp))

        val topShelf = shelves.firstOrNull()
        val lastShelf = shelves.lastOrNull()?.takeIf { !homeOptions }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(shelves, key = { it.config.id }) { shelf ->
                val id = shelf.config.id
                val last = shelf == lastShelf
                val libraryBottom = last && shelf.config.type == ShelfType.LIBRARY && shelf.items.isNotEmpty()
                // Stage-down and wrap targets go on the shelf container: a lazy row's first tile is
                // disposed once scrolled, and a request to it would silently do nothing.
                val targets = listOfNotNull(
                    stageDown.takeIf { shelf == topShelf },
                    bottom.takeIf { last && !libraryBottom },
                )
                Box(
                    Modifier
                        .then(if (last) Modifier.wrapFocus(FocusDirection.Down, focusManager, ::wrapToTop) else Modifier)
                        .focusGroupTarget(targets),
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
                            first = if (shelf == shelves.firstOrNull { it.items.isNotEmpty() }) firstFocus else null,
                            shelfRequester = if (id == focusShelfId) shelfRequester else null,
                            movingShelf = id == shelfMove.target,
                            movingPackage = move.target,
                            focusPackage = focusPkg,
                            moveFocus = moveFocus,
                            up = if (shelf == topShelf) stageFocus else null,
                            bottom = if (libraryBottom) bottom else null,
                        ),
                    )
                }
            }
            if (homeOptions) {
                item(key = "home_options") {
                    Box(Modifier.wrapFocus(FocusDirection.Down, focusManager, ::wrapToTop)) {
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
