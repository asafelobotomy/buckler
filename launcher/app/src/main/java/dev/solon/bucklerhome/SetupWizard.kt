package dev.solon.bucklerhome

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
internal sealed interface ApplyState {
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

    // Pages with choices: a fixed frame (title, choices, then Back / Next), the choices scrolling inside.
    val choicePage = step in steps && step != SetupStep.WELCOME && step != SetupStep.REVIEW
    val nextButton = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBgBrush)
            .padding(horizontal = 56.dp, vertical = 32.dp)
            .autoFocus(step to (apply is ApplyState.Blocked)),
    ) {
        val number = steps.indexOf(step).takeIf { it >= 0 }?.let { "${it + 1} of ${steps.size}" } ?: step.title
        StepHeader(step.title, number)
        stepIntro(step)?.let { SetupNote(it) }
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          CompositionLocalProvider(LocalSetupNext provides nextButton.takeIf { choicePage }) {
            when (step) {
                SetupStep.WELCOME -> WelcomeStep(shield, firstRun, onMode = { mode ->
                    choices = choices.copy(mode = mode)
                    next()
                }, onLeave = ::leave)
                SetupStep.LOOK -> LookStep(choices) { choices = it }
                SetupStep.SHELVES -> ShelvesStep(choices) { choices = it }
                SetupStep.SCREENSAVER -> ScreensaverStep(choices) { choices = it }
                SetupStep.APPS -> AppsStep(choices) { choices = it }
                SetupStep.CLEAN -> CleanStep(choices) { choices = it }
                SetupStep.SYSTEM -> SystemStep(choices) { choices = it }
                SetupStep.REVIEW -> ReviewStep(resumed != null, changes, ::back, ::finish, ::run)
                SetupStep.APPLY -> ApplyStep(apply, context, ::finish) { run(tvChanges = true) }
                SetupStep.DONE -> DoneStep(results, tvTouched, ::finish)
            }
          }
        }
        if (choicePage) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton("Back", onClick = ::back)
                PrimaryButton("Next", Modifier.focusRequester(nextButton), ::next)
            }
        }
    }
}

/** One line under a page's title saying what the page is for. */
private fun stepIntro(step: SetupStep): String? = when (step) {
    SetupStep.LOOK -> "How Home looks. Colours change as you choose them, so you can judge them here."
    SetupStep.SHELVES -> "The rows Home shows. The picture follows your choices."
    SetupStep.SCREENSAVER -> "What shows when the TV is idle."
    SetupStep.APPS -> "Optional: an icon pack, and open apps from their makers."
    SetupStep.CLEAN -> "Apps the SHIELD runs in the background, in groups. Nothing is uninstalled."
    SetupStep.SYSTEM -> "System settings Buckler sets once it has its permissions."
    else -> null
}
