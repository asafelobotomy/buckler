package dev.solon.bucklerhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
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
    shelfRequester: FocusRequester,
    moveFocus: FocusRequester,
    focusShelfId: String?,
    focusPkg: String?,
    onLaunch: (ShelfItem) -> Unit,
    onEdit: (ShelfItem) -> Unit,
    onShelfMenu: (ShelfConfig) -> Unit,
    onOpenStory: (Headline) -> Unit,
    onStepMove: (Int) -> Unit,
    onPlaceMove: () -> Unit,
    onStepShelf: (Int) -> Unit,
    onPlaceShelf: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .moveModeKeys(move, onStep = onStepMove, onPlace = onPlaceMove)
            .moveModeKeys(shelfMove, onStep = onStepShelf, onPlace = onPlaceShelf)
            .padding(horizontal = 56.dp, vertical = 28.dp),
    ) {
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
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        Box(
            Modifier.onFocusChanged { state ->
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
                below = firstFocus,
                onOpenStory = onOpenStory,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(shelves, key = { it.config.id }) { shelf ->
                val id = shelf.config.id
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
                        up = if (id == shelves.firstOrNull()?.config?.id) stageFocus else null,
                    ),
                )
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
