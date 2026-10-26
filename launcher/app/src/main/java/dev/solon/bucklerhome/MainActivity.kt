package dev.solon.bucklerhome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeLayout.importIfPresent(this)
        AppearanceStore.ensureLoaded(this)
        setContent {
            BucklerHomeTheme {
                var showFirstRun by remember { mutableStateOf(isFirstRunPending(this)) }
                var resumeTick by remember { mutableIntStateOf(0) }
                var layoutVersion by remember { mutableIntStateOf(0) }
                var screen by remember { mutableStateOf(RootScreen.HOME) }

                DisposableEffect(this) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            HomeLayout.importIfPresent(this@MainActivity)
                            resumeTick += 1
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                val openSettings = {
                    startActivity(Intent(this@MainActivity, BucklerSettingsActivity::class.java))
                }
                when {
                    showFirstRun -> FirstRunScreen(
                        onContinue = {
                            showFirstRun = false
                            layoutVersion += 1
                        },
                        onOpenSettings = openSettings,
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
}
