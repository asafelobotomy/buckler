package dev.solon.bucklerhome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private enum class RootScreen {
    HOME,
    EDIT,
}

class MainActivity : ComponentActivity() {
    /** Bumped when Settings asks for Edit Home; singleTask delivers that to [onNewIntent]. */
    private val editRequests = mutableIntStateOf(0)

    /** Whether the setup wizard shows: first run, asked for from Settings, or resumed after a grant restart. */
    private val showSetup = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EDIT_HOME, false)) editRequests.intValue++
        if (intent.getBooleanExtra(EXTRA_SETUP, false)) showSetup.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_EDIT_HOME, false)) editRequests.intValue++
        AppearanceStore.ensureLoaded(this)
        showSetup.value = isFirstRunPending(this) || intent.getBooleanExtra(EXTRA_SETUP, false) || SetupState.resume(this) != null
        setContent {
            BucklerHomeTheme {
                var resumeTick by remember { mutableIntStateOf(0) }
                var layoutVersion by remember { mutableIntStateOf(0) }
                var screen by remember { mutableStateOf(RootScreen.HOME) }
                LaunchedEffect(editRequests.intValue) {
                    if (editRequests.intValue > 0) screen = RootScreen.EDIT
                }

                DisposableEffect(this) {
                    val observer = LifecycleEventObserver { _, event ->
                        // A pushed layout is imported by HomeLayout.load(), on IO, which this triggers.
                        if (event == Lifecycle.Event.ON_RESUME) resumeTick += 1
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                when {
                    showSetup.value -> SetupWizard(
                        resumed = remember { SetupState.resume(this@MainActivity) },
                        onDone = {
                            showSetup.value = false
                            layoutVersion += 1
                        },
                    )
                    screen == RootScreen.EDIT -> EditHomeScreen(
                        onDone = {
                            screen = RootScreen.HOME
                            layoutVersion += 1
                        },
                    )
                    else -> HomeScreen(
                        resumeTick = resumeTick,
                        layoutVersion = layoutVersion,
                        onOpenEditHome = { screen = RootScreen.EDIT },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_EDIT_HOME = "dev.solon.bucklerhome.EDIT_HOME"
        const val EXTRA_SETUP = "dev.solon.bucklerhome.SETUP"
    }
}
