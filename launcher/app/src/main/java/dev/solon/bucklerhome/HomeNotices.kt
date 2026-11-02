package dev.solon.bucklerhome

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * One notice per return to Home. Several things may want a word (remote buttons that stopped,
 * news from bucklerStore, unused apps), each on its own timer; left alone they would stack up as
 * toasts, or land on Buckler's own update dialog. Instead each [offer]s its text, and [HomeNoticeHost]
 * shows the most important one once Home has settled, and nothing while the update dialog is up.
 * An offer that is not shown is not marked as shown, so it comes again on a later return.
 */
object HomeNotices {
    /** Highest first. */
    enum class Priority { REMOTE_BUTTONS, STORE_UPDATES, UNUSED_APPS }

    class Offer(val priority: Priority, val text: String, val onShown: () -> Unit)

    private var tick = -1
    private val offers = ArrayList<Offer>()

    @Synchronized
    fun offer(resumeTick: Int, priority: Priority, text: String, onShown: () -> Unit = {}) {
        if (resumeTick != tick) {
            tick = resumeTick
            offers.clear()
        }
        offers += Offer(priority, text, onShown)
    }

    /** The most important offer for [resumeTick], taken: the rest wait for a later return. */
    @Synchronized
    fun take(resumeTick: Int): Offer? {
        if (resumeTick != tick) return null
        return offers.minByOrNull { it.priority.ordinal }.also { offers.clear() }
    }
}

/**
 * Shows [HomeNotices]' pick for this return to Home, after the notices have had time to offer.
 * While [held] (Buckler's update dialog is open) it waits; if the dialog outlasts the wait, the
 * notice is left for next time rather than shown over it.
 */
@Composable
fun HomeNoticeHost(resumeTick: Int, held: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(resumeTick, held) {
        if (held) return@LaunchedEffect
        delay(SETTLE_MS)
        val offer = HomeNotices.take(resumeTick) ?: return@LaunchedEffect
        Toast.makeText(context, offer.text, Toast.LENGTH_LONG).show()
        offer.onShown()
    }
}

/** Long enough for every notice to have looked (the store's may first fetch its index). */
private const val SETTLE_MS = 12_000L
