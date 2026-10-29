package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Flow = Arrangement.spacedBy(12.dp)

/** Stremio's Continue watching, for a tile; [tick] reloads it after signing in or out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StremioChoices(tick: Int, onChoose: (MultiviewTile) -> Unit) {
    val context = LocalContext.current
    val auth = remember(tick) { StremioAccount.authKey(context) } ?: return
    val items by produceState<List<StremioItem>?>(null, auth) {
        value = withContext(Dispatchers.IO) { StremioClient.continueWatching(auth) }
    }
    PickerTitle("Stremio · Continue watching")
    when {
        items == null -> SettingsRow("Loading…")
        items.isNullOrEmpty() -> SettingsRow("Nothing under way in Stremio.")
        else -> FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
            items.orEmpty().forEach { item ->
                SettingsAction(item.title) {
                    onChoose(MultiviewTile(MultiviewKind.STREMIO, item.title, "${item.type}|${item.videoId}", item.startMs))
                }
            }
        }
    }
}

/**
 * Sign in to Stremio with a code approved on a phone or computer (or its QR code), so nothing is
 * typed on the TV. Its add-ons give the streams; Buckler plays the direct links among them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StremioSection(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val auth = remember(tick) { StremioAccount.authKey(context) }
    var pending by remember { mutableStateOf<StremioLink?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Waits for the code to be approved, for up to five minutes.
    LaunchedEffect(pending) {
        val waiting = pending ?: return@LaunchedEffect
        repeat(100) {
            delay(3_000)
            val key = withContext(Dispatchers.IO) { StremioClient.finishLink(waiting) }
            if (key != null) {
                val email = withContext(Dispatchers.IO) { StremioClient.email(key) }.orEmpty()
                StremioAccount.set(context, key, email)
                pending = null
                tick++
                onChanged()
                return@LaunchedEffect
            }
        }
        status = "The code ran out. Ask for a new one."
        pending = null
    }
    PickerTitle("Stremio")
    SettingsRow(
        "Your Continue watching, played from your add-ons' direct links (such as a debrid service's). " +
            "Torrent streams need Stremio's own engine, so those stay in the Stremio app.",
    )
    if (auth != null) {
        SettingsRow("Signed in${StremioAccount.email(context).takeIf { it.isNotBlank() }?.let { " as $it" } ?: ""}.")
        FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
            SettingsAction("Sign out") {
                StremioAccount.set(context, null)
                tick++
                onChanged()
            }
        }
        return
    }
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        SettingsAction("Sign in with a code") {
            status = "Asking Stremio…"
            scope.launch {
                pending = withContext(Dispatchers.IO) { StremioClient.startLink() }
                status = if (pending == null) "Stremio did not answer. Try again in a moment." else null
            }
        }
    }
    pending?.let { link ->
        SettingsRow("Go to ${link.link.removePrefix("https://")} on your phone or computer (or scan this) and sign in. Code ${link.code}.")
        val qr = remember(link.link) { QrBitmap.encode(link.link, 320)?.asImageBitmap() }
        qr?.let { Image(it, contentDescription = "Stremio sign-in", modifier = Modifier.size(160.dp)) }
    }
    status?.let { SettingsRow(it) }
}
