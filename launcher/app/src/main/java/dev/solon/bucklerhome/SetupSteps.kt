package dev.solon.bucklerhome

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/*
 * The wizard's choice pages, each laid out like Settings → Appearance ([SetupSplit]): the page's
 * items on the left, the focused one's choices on the right, as pictures where they can be.
 * Each page shows its choices and reports a changed copy through `onChange`.
 */

internal val CardGapSetup = Arrangement.spacedBy(12.dp)

/** A line of explanation under a page's choices. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SetupNote(text: String) {
    Text(text, color = ColorMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
fun LookStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val context = LocalContext.current
    var focus by remember { mutableStateOf("scheme") }
    val items = listOfNotNull(
        SplitItem("scheme", "Colour scheme", choices.theme.label),
        SplitItem("font", "Font", choices.font.label).takeIf { choices.mode == SetupMode.ADVANCED },
        SplitItem("layout", "Layout", choices.layout.label()),
        SplitItem("size", "App size", choices.tileSize.label),
    )
    SetupSplit(items, focus, { focus = it }) { key ->
        when (key) {
            "scheme" -> CardColumns(columns = 3) { width ->
                AppTheme.entries.forEach { theme ->
                    PreviewCard(theme.label, theme == choices.theme, width = width, onClick = {
                        // Shown at once, so the scheme can be judged on the wizard itself.
                        AppearanceStore.update(context) { it.copy(theme = theme) }
                        onChange(choices.copy(theme = theme))
                    }) { ThemeMiniature(theme, AppearanceStore.config.highContrast) }
                }
            }
            "font" -> CardColumns(columns = 3) { width ->
                FontChoice.entries.forEach { font ->
                    PreviewCard(font.label, font == choices.font, width = width, onClick = {
                        AppearanceStore.update(context) { it.copy(font = font) }
                        onChange(choices.copy(font = font))
                    }) { FontMiniature(font) }
                }
            }
            "layout" -> CardColumns(columns = 3) { width ->
                CoverLayout.entries.forEach { style ->
                    PreviewCard(style.label(), style == choices.layout, width = width, onClick = { onChange(choices.copy(layout = style)) }) {
                        LayoutMiniature(style, choices.tileSize)
                    }
                }
            }
            "size" -> CardColumns(columns = 3) { width ->
                TileSize.entries.forEach { size ->
                    PreviewCard(size.label, size == choices.tileSize, width = width, onClick = { onChange(choices.copy(tileSize = size)) }) {
                        LayoutMiniature(choices.layout, size)
                    }
                }
            }
        }
    }
}

@Composable
fun ShelvesStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val shield = SetupState.isShield()
    var focus by remember { mutableStateOf(ShelfType.WATCH_NEXT.name) }
    val items = SetupChoices.OPTIONAL_SHELVES.map { SplitItem(it.name, SetupApply.shelfName(it), on = choices.shelves[it] == true) } +
        listOfNotNull(SplitItem(NOW_PLAYING, "Now Playing", on = choices.mediaAccess).takeIf { shield })
    SetupSplit(
        items = items,
        selected = focus,
        onSelect = { focus = it },
        onToggle = { key ->
            if (key == NOW_PLAYING) {
                onChange(choices.copy(mediaAccess = !choices.mediaAccess))
            } else {
                val type = ShelfType.valueOf(key)
                onChange(choices.copy(shelves = choices.shelves + (type to (choices.shelves[type] != true))))
            }
        },
    ) { key ->
        // Above the picture: nothing on this side takes focus, so nothing below it could scroll into view.
        Text(
            if (key == NOW_PLAYING) {
                "The song or video playing in any app, with its controls, in the header and on the Main Stage. OK switches it."
            } else {
                shelfDetail(ShelfType.valueOf(key)) + " OK switches it on or off; the Apps row always shows."
            },
            color = ColorMuted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        HomeShelvesMiniature(choices.shelves, nowPlaying = shield && choices.mediaAccess, focus = key, modifier = Modifier.fillMaxWidth())
    }
}

private const val NOW_PLAYING = "now_playing"

private fun shelfDetail(type: ShelfType): String = when (type) {
    ShelfType.WATCH_NEXT -> "What to carry on watching, from the apps that share it."
    ShelfType.SUGGESTED -> "Apps' own picks, as their channels offer them."
    ShelfType.RECENTS -> "The apps opened last."
    ShelfType.LIBRARY -> "Every app, in a grid below the shelves."
    ShelfType.APPS -> "Your pinned apps."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScreensaverStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    val shield = SetupState.isShield()
    var focus by remember { mutableStateOf("style") }
    // The highlighted card's screensaver runs; the others show a still of it.
    var running by remember { mutableStateOf<String?>(null) }
    fun Modifier.runsWhenFocused(key: String) = onFocusChanged { state ->
        if (state.hasFocus) running = key else if (running == key) running = null
    }
    val items = listOfNotNull(
        SplitItem("style", "Screensaver", choices.saver.label.substringBefore(" (")),
        SplitItem("system", "The TV's screensaver", if (choices.systemSaver) "Buckler" else "Backdrop").takeIf { shield },
    )
    SetupSplit(items, focus, { focus = it }) { key ->
        when (key) {
            "style" -> {
                SetupNote("A still of each; the highlighted one runs. Opens on the TV as it looks here.")
                ScreensaverPicker(choices.saver) { onChange(choices.copy(saver = it)) }
            }
            "system" -> {
                SetupNote("Choosing Buckler's turns Google's Backdrop off; choosing Backdrop turns it back on.")
                FlowRow(horizontalArrangement = CardGapSetup, verticalArrangement = CardGapSetup) {
                    PreviewCard(
                        "Buckler's",
                        choices.systemSaver,
                        modifier = Modifier.runsWhenFocused("buckler"),
                        width = 220.dp,
                        onClick = { onChange(choices.copy(systemSaver = true)) },
                    ) { ScreensaverCard(choices.saver, live = running == "buckler") }
                    PreviewCard("Google's Backdrop", !choices.systemSaver, width = 220.dp, onClick = { onChange(choices.copy(systemSaver = false)) }) {
                        BackdropMiniature()
                    }
                }
            }
        }
    }
}

private val ANIMATIONS = listOf(1f to "Normal", 0.5f to "Fast", 0f to "Off")
private val DNS = listOf(
    DnsChoice.AUTOMATIC to "Automatic",
    DnsChoice.OFF to "Off",
    DnsChoice("hostname", "dns.adguard-dns.com") to "AdGuard (blocks ads)",
    DnsChoice("hostname", "one.one.one.one") to "Cloudflare",
    DnsChoice("hostname", "dns.quad9.net") to "Quad9 (blocks malware)",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemStep(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    var focus by remember { mutableStateOf("animations") }
    val dnsLabel = DNS.firstOrNull { it.first == choices.dns }?.second ?: SetupApply.dnsLabel(choices.dns)
    val items = listOf(
        SplitItem("animations", "Animations", SetupApply.animationLabel(choices.animation).substringBefore(" (").replaceFirstChar { it.uppercase() }),
        SplitItem("dns", "Private DNS", dnsLabel.substringBefore(" (")),
    )
    SetupSplit(items, focus, { focus = it }) { key ->
        when (key) {
            "animations" -> {
                SetupNote("How long menus and windows take to open and close. Fast is half the time.")
                FlowRow(horizontalArrangement = CardGapSetup, verticalArrangement = CardGapSetup) {
                    ANIMATIONS.forEach { (scale, label) ->
                        PreviewCard(label, choices.animation == scale, onClick = { onChange(choices.copy(animation = scale)) }) {
                            SpeedMiniature(scale)
                        }
                    }
                }
            }
            "dns" -> {
                SetupNote("Looks up websites over an encrypted connection. AdGuard also stops ads, and Quad9 malware, in every app.")
                CardColumns(columns = 3) { width ->
                    DNS.forEach { (dns, label) ->
                        PreviewCard(label.substringBefore(" ("), choices.dns == dns, width = width, onClick = { onChange(choices.copy(dns = dns)) }) {
                            DnsMiniature(dns, label)
                        }
                    }
                }
            }
        }
    }
}

internal fun appName(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm).toString()
}.getOrDefault(pkg)
