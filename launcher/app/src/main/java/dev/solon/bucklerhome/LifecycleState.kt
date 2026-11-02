package dev.solon.bucklerhome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * True while the screen's activity is at least [state]: STARTED is on screen, RESUMED is in front
 * taking keys. Compose keeps coroutines running while Home is hidden behind an app, so polling,
 * probing and per-frame loops key on this to stop when nobody can see them.
 */
@Composable
fun rememberLifecycleAtLeast(state: Lifecycle.State): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var atLeast by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(state)) }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, _ -> atLeast = lifecycle.currentState.isAtLeast(state) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return atLeast
}

/** A counter that goes up each time the screen comes back to the front (after an uninstall, an app page or a pushed layout). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return tick
}
