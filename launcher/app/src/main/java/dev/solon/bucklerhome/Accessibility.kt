package dev.solon.bucklerhome

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Lazily started text-to-speech for the "speak focused item" option. */
object SpokenFocus {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val tts = engine
        if (tts == null) {
            pending = text
            engine = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) pending?.let { engine?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "buckler") }
                pending = null
            }
        } else if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "buckler")
        } else {
            pending = text
        }
    }

    fun stop() {
        engine?.stop()
    }
}

/** Speaks [text] whenever [focused] turns true, if Settings → Accessibility turned that on. */
@Composable
fun AnnounceOnFocus(focused: Boolean, text: String) {
    val context = LocalContext.current
    val enabled = AppearanceStore.config.spokenFocus
    LaunchedEffect(focused, enabled, text) {
        if (focused && enabled) SpokenFocus.speak(context, text)
    }
}

/** Extra-thick outline on the focused control when "Strong focus outline" is on. */
@Composable
fun Modifier.strongFocusRing(focused: Boolean, shape: Shape): Modifier =
    if (focused && AppearanceStore.config.strongFocus) border(5.dp, ColorRing, shape) else this

/** Focus zoom, unless the user asked for reduced motion. */
@Composable
fun focusZoom(focused: Boolean, zoom: Float): Float =
    if (focused && !AppearanceStore.config.reduceMotion) zoom else 1f
