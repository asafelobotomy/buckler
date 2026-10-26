package dev.solon.bucklerhome

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private const val PREFS = "bucklerhome"
private const val KEY_FIRST_RUN_DONE = "first_run_done"

fun isFirstRunPending(context: Context): Boolean =
    !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_FIRST_RUN_DONE, false)

fun markFirstRunDone(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_FIRST_RUN_DONE, true)
        .apply()
}

private enum class FirstRunStep {
    WELCOME,
    LAYOUT,
    SHELVES,
    DONE,
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FirstRunScreen(
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(FirstRunStep.WELCOME) }
    var cover by remember { mutableStateOf(CoverLayout.BANNER) }
    var watchNext by remember { mutableStateOf(true) }
    var suggested by remember { mutableStateOf(true) }
    var recents by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(true) }

    fun finish() {
        val pins = DockPrefs.loadPins(context)
        val shelves = listOf(
            ShelfConfig("watch_next", ShelfType.WATCH_NEXT, "Up Next", enabled = watchNext),
            ShelfConfig("apps", ShelfType.APPS, "Apps", enabled = true, packages = pins),
            ShelfConfig("suggested", ShelfType.SUGGESTED, "Suggested", enabled = suggested),
            ShelfConfig("recents", ShelfType.RECENTS, "Recent", enabled = recents),
            ShelfConfig("library", ShelfType.LIBRARY, "Library", enabled = library),
        )
        HomeLayout.save(context, HomeLayout(layout = cover, shelves = shelves))
        markFirstRunDone(context)
        onContinue()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .padding(horizontal = 64.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (step) {
            FirstRunStep.WELCOME -> {
                StepHeader("Welcome", "1/4")
                Body(
                    "buckler-home is your ad-free HOME with shelves and cover styles.\n" +
                        "Host tool: Buckler · Future OS: kitetvOS.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("Next") { step = FirstRunStep.LAYOUT }
                    SecondaryButton("Android TV Settings", onOpenSettings)
                }
            }
            FirstRunStep.LAYOUT -> {
                StepHeader("Cover layout", "2/4")
                Body("How should apps and Up Next cards look?")
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoverLayout.entries.forEach { style ->
                        LayoutChoice(
                            label = when (style) {
                                CoverLayout.ICON -> "Icon"
                                CoverLayout.POSTER -> "Poster"
                                CoverLayout.BANNER -> "Banner"
                            },
                            selected = cover == style,
                        ) { cover = style }
                    }
                }
                Spacer(modifier = Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton("Back") { step = FirstRunStep.WELCOME }
                    PrimaryButton("Next") { step = FirstRunStep.SHELVES }
                }
            }
            FirstRunStep.SHELVES -> {
                StepHeader("Shelves", "3/4")
                Body("Choose which rows appear on Home. Apps shelf stays on.")
                Spacer(modifier = Modifier.height(16.dp))
                ToggleRow("Up Next (Watch Next)", watchNext) { watchNext = it }
                ToggleRow("Suggested", suggested) { suggested = it }
                ToggleRow("Recent", recents) { recents = it }
                ToggleRow("Library grid", library) { library = it }
                Spacer(modifier = Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton("Back") { step = FirstRunStep.LAYOUT }
                    PrimaryButton("Next") { step = FirstRunStep.DONE }
                }
            }
            FirstRunStep.DONE -> {
                StepHeader("You're set", "4/4")
                Body(
                    "Edit Home anytime from the footer. Long-press or Menu to pin apps.\n" +
                        "Buckler can push layout defaults after setup on your PC.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton("Back") { step = FirstRunStep.SHELVES }
                    PrimaryButton("Continue to Home", ::finish)
                    SecondaryButton("Android TV Settings", onOpenSettings)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StepHeader(title: String, progress: String) {
    Text(
        text = "buckler-home setup  ·  $progress",
        color = ColorBrand,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = title,
        color = ColorText,
        fontSize = 36.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Body(text: String) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = text,
        color = ColorMuted,
        fontSize = 18.sp,
        lineHeight = 28.sp,
    )
    Spacer(modifier = Modifier.height(32.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LayoutChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(160.dp),
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
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onChange(!enabled) },
        modifier = Modifier
            .padding(bottom = 10.dp)
            .width(420.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorTile,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Text(
            text = "${if (enabled) "●" else "○"}  $label",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            fontSize = 16.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorBrand,
            focusedContainerColor = Color(0xFFA8D0FF),
            contentColor = ColorOnBrand,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
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
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            fontSize = 16.sp,
        )
    }
}
