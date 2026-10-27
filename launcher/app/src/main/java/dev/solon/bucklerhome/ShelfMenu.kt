package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Shelf menu, Up Next sources, and the news reader. Kept together so Home stays readable. */
@Composable
fun HomeShelfDialogs(
    menu: ShelfConfig?,
    sources: ShelfConfig?,
    reading: Headline?,
    shelfCount: Int,
    /** False for the last shelf that keeps Home navigable. */
    canHide: Boolean,
    onMove: (ShelfConfig) -> Unit,
    onHide: (ShelfConfig) -> Unit,
    onSources: (ShelfConfig) -> Unit,
    onDismissMenu: () -> Unit,
    onApplySources: (ShelfConfig, Boolean, List<String>) -> Unit,
    onDismissSources: () -> Unit,
    onDismissStory: () -> Unit,
) {
    menu?.let { shelf ->
        ShelfMenuDialog(
            title = shelf.title,
            canMove = shelfCount > 1,
            canHide = canHide,
            showSources = shelf.type == ShelfType.WATCH_NEXT,
            onMove = { onMove(shelf) },
            onHide = { onHide(shelf) },
            onSources = { onSources(shelf) },
            onDismiss = onDismissMenu,
        )
    }
    sources?.let { shelf ->
        UpNextSourcesDialog(
            shelf = shelf,
            onApply = { restrict, packages -> onApplySources(shelf, restrict, packages) },
            onDismiss = onDismissSources,
        )
    }
    reading?.let { story -> StoryReaderDialog(story, onDismissStory) }
}

/** Options for one shelf, opened from the circle at the start of that shelf. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShelfMenuDialog(
    title: String,
    canMove: Boolean,
    canHide: Boolean,
    showSources: Boolean,
    onMove: () -> Unit,
    onHide: () -> Unit,
    onSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 400.dp) {
            Column {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorText,
                )
                Text(
                    text = "Shelf options",
                    fontSize = 13.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (canMove) {
                        SettingsAction("Move", focusRequester = firstFocus, onClick = onMove)
                    }
                    if (canHide) {
                        SettingsAction(
                            "Hide shelf",
                            focusRequester = if (canMove) null else firstFocus,
                            onClick = onHide,
                        )
                    }
                    if (showSources) {
                        SettingsAction(
                            "Sources",
                            focusRequester = if (canMove || canHide) null else firstFocus,
                            onClick = onSources,
                        )
                    }
                    SettingsAction(
                        "Close",
                        focusRequester = if (canMove || canHide || showSources) null else firstFocus,
                        onClick = onDismiss,
                    )
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { firstFocus.requestFocus() }
        }
    }
}
