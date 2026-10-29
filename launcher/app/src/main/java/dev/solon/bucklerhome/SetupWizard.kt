package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SetupStep(val title: String) {
    WELCOME("Welcome"),
    LOOK("Look"),
    SHELVES("Shelves"),
    SCREENSAVER("Screensaver"),
    APPS("Apps and icons"),
    CLEAN("Clean up the SHIELD"),
    SYSTEM("Speed and network"),
    REVIEW("Review"),
    APPLY("Applying"),
    DONE("Done"),
}

/** Where applying stands: not started, running, stopped on the connection, or finished. */
private sealed interface ApplyState {
    data object Idle : ApplyState
    data class Running(val step: String) : ApplyState
    data class Blocked(val reason: String) : ApplyState
    data object Finished : ApplyState
}

/**
 * The setup wizard, on first run and again from Settings. It opens on the TV as it is now, so a
 * second run changes only what is changed in it; Review lists exactly that before anything is
 * applied. Simple asks the plain questions; Advanced adds the font, app-by-app clean-up and the
 * system settings. SHIELD steps show on a SHIELD only. [resumed] are choices saved before a grant
 * restarted Buckler: the wizard reopens at Review with them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupWizard(resumed: SetupChoices?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstRun = remember { isFirstRunPending(context) }
    val from = remember { SetupState.current(context, firstRun = false, intended = false) }
    var choices by remember { mutableStateOf(resumed ?: SetupState.current(context, firstRun)) }
    var step by remember { mutableStateOf(if (resumed != null) SetupStep.REVIEW else SetupStep.WELCOME) }
    var apply by remember { mutableStateOf<ApplyState>(ApplyState.Idle) }
    val results = remember { mutableListOf<String>() }
    var localDone by remember { mutableStateOf(false) }
    var tvTouched by remember { mutableStateOf(false) }
    val shield = remember { SetupState.isShield() }

    val steps = SetupStep.entries.filter {
        when (it) {
            SetupStep.CLEAN, SetupStep.APPS -> shield
            SetupStep.SYSTEM -> shield && choices.mode == SetupMode.ADVANCED
            SetupStep.APPLY, SetupStep.DONE -> false
            else -> true
        }
    }
    val changes = remember(step) { if (step == SetupStep.REVIEW) SetupApply.changes(context, from, choices) else emptyList() }

    fun finish() {
        markFirstRunDone(context)
        SetupState.clearResume(context)
        onDone()
    }

    /** Leaves without applying: the scheme and font previewed on the way go back. */
    fun leave() {
        AppearanceStore.update(context) { it.copy(theme = from.theme, font = from.font) }
        SetupState.clearResume(context)
        if (firstRun) markFirstRunDone(context)
        onDone()
    }

    fun back() {
        val i = steps.indexOf(step)
        when {
            step == SetupStep.APPLY || step == SetupStep.DONE -> Unit
            i <= 0 -> if (!firstRun) leave()
            else -> step = steps[i - 1]
        }
    }

    fun next() {
        step = steps.getOrNull(steps.indexOf(step) + 1) ?: SetupStep.REVIEW
    }

    fun run(tvChanges: Boolean) {
        step = SetupStep.APPLY
        apply = ApplyState.Running("Saving Home settings…")
        scope.launch {
            apply = withContext(Dispatchers.IO) {
                if (!localDone) {
                    SetupApply.applyLocal(context, choices)
                    localDone = true
                    results += "Home settings saved."
                }
                if (!tvChanges) return@withContext ApplyState.Finished
                tvTouched = true
                try {
                    AdbSelf.connect(context) { apply = ApplyState.Running("Choose Allow on the TV's prompt (tick Always allow).") }.use { adb ->
                        results += SetupApply.applyTv(context, adb, choices) { apply = ApplyState.Running(it) }
                    }
                    ApplyState.Finished
                } catch (e: AdbSelf.Failure.NotListening) {
                    ApplyState.Blocked(
                        "Network debugging is off. In Developer options turn on Network debugging " +
                            "(or USB debugging, then Network debugging), come back and try again.",
                    )
                } catch (e: AdbSelf.Failure.NotAllowed) {
                    ApplyState.Blocked("The TV did not allow Buckler. Try again and choose Allow on the prompt.")
                } catch (e: Exception) {
                    ApplyState.Blocked("Stopped: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            if (apply == ApplyState.Finished) step = SetupStep.DONE
        }
    }

    BackHandler(onBack = ::back)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            // Each page starts at its top.
            .verticalScroll(remember(step) { ScrollState(0) })
            .padding(horizontal = 64.dp, vertical = 48.dp)
            .autoFocus(step to (apply is ApplyState.Blocked)),
    ) {
        val number = steps.indexOf(step).takeIf { it >= 0 }?.let { "${it + 1} of ${steps.size}" } ?: step.title
        StepHeader(step.title, number)
        when (step) {
            SetupStep.WELCOME -> {
                StepBody(
                    "Buckler is an ad-free Home for your TV. Choose how to set it up; every choice can be " +
                        "changed later, here or in Settings." + if (shield) " Nothing changes until you review it." else "",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("Simple") { choices = choices.copy(mode = SetupMode.SIMPLE); next() }
                    PrimaryButton("Advanced") { choices = choices.copy(mode = SetupMode.ADVANCED); next() }
                    SecondaryButton(if (firstRun) "Skip setup" else "Close", ::leave)
                }
                Spacer(modifier = Modifier.height(20.dp))
                SettingsRow(
                    "Simple: colours, layout, shelves, screensaver" +
                        (if (shield) ", icon packs and open apps, and three clean-up switches." else ".") +
                        "  Advanced: also the font" + (if (shield) ", app-by-app clean-up, animation speed and Private DNS." else "."),
                )
            }
            SetupStep.LOOK -> LookStep(choices) { choices = it }
            SetupStep.SHELVES -> {
                StepBody("Choose the rows Home shows. The Apps row always shows.")
                ShelvesStep(choices) { choices = it }
            }
            SetupStep.SCREENSAVER -> {
                StepBody("Shown when the TV is idle.")
                ScreensaverStep(choices) { choices = it }
            }
            SetupStep.APPS -> {
                StepBody("Optional extras, downloaded from their makers and checked against a pinned checksum.")
                AppsStep(choices) { choices = it }
            }
            SetupStep.CLEAN -> {
                StepBody(
                    "Turn off apps the SHIELD runs in the background. Nothing is uninstalled: each app " +
                        "is recorded and can be turned back on, here or in Settings → Setup.",
                )
                CleanStep(choices) { choices = it }
            }
            SetupStep.SYSTEM -> {
                StepBody("System settings Buckler can set once it has its permissions.")
                SystemStep(choices) { choices = it }
            }
            SetupStep.REVIEW -> ReviewStep(resumed != null, changes, ::back, ::finish, ::run)
            SetupStep.APPLY -> ApplyStep(apply, context, ::finish) { run(tvChanges = true) }
            SetupStep.DONE -> {
                StepBody(results.joinToString("\n"))
                if (tvTouched) {
                    SettingsRow("You can turn Network debugging off again in Developer options; Buckler asks to connect next time.")
                    Spacer(modifier = Modifier.height(16.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton("Go to Home", ::finish)
                    if (tvTouched) SecondaryButton("Developer options") { openDeveloperOptions(context) }
                }
            }
        }
        if (step in steps && step != SetupStep.WELCOME && step != SetupStep.REVIEW) {
            Spacer(modifier = Modifier.height(28.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton("Back", ::back)
                PrimaryButton("Next", ::next)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewStep(
    resumed: Boolean,
    changes: List<SetupChange>,
    onBack: () -> Unit,
    onFinish: () -> Unit,
    onApply: (tvChanges: Boolean) -> Unit,
) {
    val tv = changes.any { it.needsTv }
    StepBody(
        when {
            resumed -> "Buckler restarted after getting its permissions, as Android does. Here is what is left to do."
            changes.isEmpty() -> "Nothing to change: the TV already looks like this."
            else -> "These are the changes. Nothing has been changed yet."
        },
    )
    changes.forEach { SettingsRow("•  ${it.text}") }
    if (tv) {
        Spacer(modifier = Modifier.height(12.dp))
        SettingsRow(
            "The SHIELD changes go over Network debugging, from the TV itself. Turn it on in Developer options " +
                "(Settings → Device Preferences → About: press Build seven times to show them). The TV then asks " +
                "once to allow Buckler.",
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (changes.isEmpty()) {
            PrimaryButton("Go to Home", onFinish)
        } else {
            PrimaryButton(if (tv) "Apply all" else "Apply") { onApply(tv) }
            if (tv && changes.any { !it.needsTv }) SecondaryButton("Apply without SHIELD changes") { onApply(false) }
        }
        SecondaryButton("Back", onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApplyStep(apply: ApplyState, context: Context, onFinish: () -> Unit, onRetry: () -> Unit) {
    when (apply) {
        is ApplyState.Running -> StepBody(apply.step)
        is ApplyState.Blocked -> {
            StepBody(apply.reason)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton("Try again", onRetry)
                SecondaryButton("Developer options") { openDeveloperOptions(context) }
                SecondaryButton("Finish without them", onFinish)
            }
        }
        else -> StepBody("…")
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
