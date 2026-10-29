package dev.solon.bucklerhome

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** When the remote was last pressed on Home, so motion no one is watching can rest. */
object UserActivity {
    var lastInput by mutableLongStateOf(SystemClock.elapsedRealtime())
        private set

    fun touched() {
        lastInput = SystemClock.elapsedRealtime()
    }
}

/**
 * True once [afterMs] has passed without a press on Home, false again at the next one. Each frame
 * of motion repaints the whole of Home (on SHIELD about 40% of a core at 60 fps), so the ticker
 * steps instead of scrolling while idle.
 */
@Composable
fun rememberIdle(afterMs: Long): Boolean {
    var idle by remember { mutableStateOf(false) }
    val last = UserActivity.lastInput
    LaunchedEffect(last) {
        idle = false
        delay((last + afterMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        idle = true
    }
    return idle
}
