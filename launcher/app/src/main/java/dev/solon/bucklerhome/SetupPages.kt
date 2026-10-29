package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** The wizard's pages around its choices: Welcome, Review, Apply and Done. */

/** Simple and Advanced as two cards, each showing the pages it has. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeStep(shield: Boolean, firstRun: Boolean, onMode: (SetupMode) -> Unit, onLeave: () -> Unit) {
    val simple = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // After the page's own first-focus pass, which would pick the last button.
        delay(120)
        runCatching { simple.requestFocus() }
    }
    Column {
        SetupNote(
            "Buckler is an ad-free Home for your TV. Choose how to set it up; every choice can be changed later, " +
                "here or in Settings." + if (shield) " Nothing changes until you review it." else "",
        )
        Spacer(modifier = Modifier.height(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PreviewCard("Simple", selected = false, focusRequester = simple, width = 380.dp, onClick = { onMode(SetupMode.SIMPLE) }) {
                ModeMiniature(SetupMode.SIMPLE, shield)
            }
            PreviewCard("Advanced", selected = false, width = 380.dp, onClick = { onMode(SetupMode.ADVANCED) }) {
                ModeMiniature(SetupMode.ADVANCED, shield)
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        SecondaryButton(if (firstRun) "Skip setup" else "Close", onClick = onLeave)
    }
}

/** The pages a mode has, as glyph chips: Advanced adds the font, app by app, and speed and network. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun BoxScope.ModeMiniature(mode: SetupMode, shield: Boolean) {
    val advanced = mode == SetupMode.ADVANCED
    val chips = listOfNotNull(
        "🎨 Look",
        "Aa Font".takeIf { advanced },
        "🏠 Shelves",
        "🌙 Screensaver",
        "🧩 Apps".takeIf { shield },
        (if (advanced) "🧹 App by app" else "🧹 3 switches").takeIf { shield },
        "⚡ Speed, DNS".takeIf { advanced && shield },
    )
    FlowRow(
        Modifier.fillMaxSize().background(LocalPalette.current.bgTop).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            Text(
                chip,
                color = ColorText,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(ColorTile).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Review: the changes grouped like the pages they came from (left), with each group's changes
 * on the right, marked when they go over Network debugging. Nothing is changed until Apply.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun ReviewStep(
    resumed: Boolean,
    changes: List<SetupChange>,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onApply: (tvChanges: Boolean) -> Unit,
) {
    val tv = changes.any { it.needsTv }
    val areas = SetupArea.entries.filter { a -> changes.any { it.area == a } }
    var focus by remember(areas) { mutableStateOf(areas.firstOrNull()?.name ?: "") }
    Column(Modifier.fillMaxSize()) {
        SetupNote(
            when {
                resumed -> "Buckler restarted after getting its permissions, as Android does. Here is what is left to do."
                changes.isEmpty() -> "Nothing to change: the TV already looks like this."
                else -> "${changes.size} changes. Nothing has been changed yet."
            },
        )
        Spacer(modifier = Modifier.height(14.dp))
        if (areas.isNotEmpty()) {
            SetupSplit(
                items = areas.map { a -> SplitItem(a.name, "${a.glyph}  ${a.label}", changes.count { it.area == a }.toString()) },
                selected = focus,
                onSelect = { focus = it },
                modifier = Modifier.weight(1f),
            ) { key ->
                changes.filter { it.area.name == key }.forEach { change ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("•", color = ColorBrand, fontSize = 16.sp, modifier = Modifier.width(18.dp))
                        Text(change.text, color = ColorText, fontSize = 16.sp, modifier = Modifier.weight(1f, fill = false))
                        if (change.needsTv) {
                            Text(
                                "Network debugging",
                                color = ColorMuted,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ColorTile)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        if (tv) {
            SetupNote(
                "Changes marked Network debugging are made from the TV itself: turn it on in Developer options " +
                    "(Settings → Device Preferences → About: press Build seven times to show them). The TV then asks once to allow Buckler.",
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (changes.isEmpty()) {
                PrimaryButton("Go to Home", onClick = onFinish)
            } else {
                PrimaryButton(if (tv) "Apply all" else "Apply") { onApply(tv) }
                if (tv && changes.any { !it.needsTv }) SecondaryButton("Apply without SHIELD changes") { onApply(false) }
            }
            SecondaryButton("Back", onClick = onBack)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApplyStep(apply: ApplyState, context: Context, onFinish: () -> Unit, onRetry: () -> Unit) {
    Column {
        when (apply) {
            is ApplyState.Running -> StepBody(apply.step)
            is ApplyState.Blocked -> {
                StepBody(apply.reason)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("Try again", onClick = onRetry)
                    SecondaryButton("Developer options") { openDeveloperOptions(context) }
                    SecondaryButton("Finish without them", onClick = onFinish)
                }
            }
            else -> StepBody("…")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoneStep(results: List<String>, tvTouched: Boolean, onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        results.forEach { Text("✓  $it", color = ColorText, fontSize = 17.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        if (tvTouched) SetupNote("You can turn Network debugging off again in Developer options; Buckler asks to connect next time.")
        Spacer(modifier = Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton("Go to Home", onClick = onFinish)
            if (tvTouched) SecondaryButton("Developer options") { openDeveloperOptions(context) }
        }
    }
}

fun openDeveloperOptions(context: Context) = launchFirstThatWorks(
    context,
    "Developer options",
    listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
)

/** Opens the setup wizard on Home, from Settings. */
fun openSetupWizard(context: Context) {
    val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETUP, true)
    safeLaunch(context, intent, "Setup wizard")
}
