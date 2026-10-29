package dev.solon.bucklerhome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Flow = Arrangement.spacedBy(12.dp)

/**
 * Settings → Main Stage with Multiview chosen: what each of the three tiles plays, then where the
 * videos come from (a Jellyfin server, an IPTV playlist, cameras).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiviewSettings() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }
    // The tile rows, so focus goes back to the row once its video is chosen (its list closes under it).
    val rows = remember { List(MultiviewStore.TILES) { FocusRequester() } }
    var chosen by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(chosen) {
        val row = chosen ?: return@LaunchedEffect
        delay(60)
        runCatching { rows[row].requestFocus() }
        chosen = null
    }
    remember { MultiviewStore.load(context) }
    SectionTitle("Multiview")
    SettingsRow(
        "Up to three videos on the Main Stage, all playing; the highlighted one has sound. OK on a tile plays it " +
            "full screen. From Jellyfin, Stremio, an IPTV playlist or cameras on your network.",
    )
    PickerTitle("Tiles")
    MultiviewStore.tiles.forEachIndexed { i, tile ->
        FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
            SettingsAction("${i + 1}: ${tile?.title ?: "Empty"}", focusRequester = rows[i], selected = editing == i) {
                editing = if (editing == i) null else i
            }
        }
        if (editing == i) {
            TileChooser(tick) { picked ->
                MultiviewStore.setTile(context, i, picked)
                editing = null
                chosen = i
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(12.dp))
    JellyfinSection { tick++ }
    Spacer(Modifier.height(16.dp))
    StremioSection { tick++ }
    Spacer(Modifier.height(16.dp))
    PlaylistSection { tick++ }
    Spacer(Modifier.height(16.dp))
    CameraSection { tick++ }
}

/** Everything a tile can play, by source; [tick] reloads it after a source changes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileChooser(tick: Int, onChoose: (MultiviewTile?) -> Unit) {
    val context = LocalContext.current
    val account = remember(tick) { MultiviewStore.jellyfin(context) }
    val videos by produceState<List<Pair<String, List<JellyfinItem>>>?>(null, account) {
        value = account?.let { withContext(Dispatchers.IO) { JellyfinClient.videos(context, it) } }.orEmpty()
    }
    val channels by produceState<List<IptvChannel>>(emptyList(), tick) {
        value = withContext(Dispatchers.IO) { IptvPlaylist.channels(context) }
    }
    val cameras = remember(tick) { MultiviewStore.cameras(context) }
    var query by remember { mutableStateOf("") }
    Column {
        FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
            SettingsAction("Empty") { onChoose(null) }
        }
        if (account == null) SettingsRow("Jellyfin: sign in below to choose its videos.")
        if (account != null && videos == null) SettingsRow("Jellyfin: loading…")
        videos?.filter { it.second.isNotEmpty() }?.forEach { (label, items) ->
            PickerTitle("Jellyfin · $label")
            FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
                items.forEach { item ->
                    SettingsAction(item.title) { onChoose(MultiviewTile(MultiviewKind.JELLYFIN, item.title, item.id, item.startMs)) }
                }
            }
        }
        StremioChoices(tick, onChoose)
        if (channels.isNotEmpty()) {
            PickerTitle("IPTV · ${channels.size} channels")
            FocusableTextField(value = query, onValueChange = { query = it }, hint = "Search channels", onDone = {})
            Spacer(Modifier.height(8.dp))
            val matches = channels.asSequence().filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.take(30).toList()
            FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
                matches.forEach { ch ->
                    SettingsAction(if (ch.group.isBlank()) ch.name else "${ch.name} · ${ch.group}") {
                        onChoose(MultiviewTile(MultiviewKind.IPTV, ch.name, ch.url))
                    }
                }
            }
        }
        if (cameras.isNotEmpty()) {
            PickerTitle("Cameras")
            FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
                cameras.forEach { cam -> SettingsAction(cam.name) { onChoose(MultiviewTile(MultiviewKind.CAMERA, cam.name, cam.url)) } }
            }
        }
    }
}

/** Sign in to a Jellyfin server: found on the network or typed, then Quick Connect or name and password. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JellyfinSection(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val account = remember(tick) { MultiviewStore.jellyfin(context) }
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<QuickConnect?>(null) }
    fun signedIn(found: JellyfinAccount) {
        MultiviewStore.setJellyfin(context, found)
        pending = null
        status = null
        password = ""
        tick++
        onChanged()
    }
    // Waits for the code to be approved in another Jellyfin app, for up to five minutes.
    LaunchedEffect(pending) {
        val waiting = pending ?: return@LaunchedEffect
        repeat(100) {
            delay(3_000)
            withContext(Dispatchers.IO) { JellyfinClient.finishQuickConnect(context, waiting) }?.let { signedIn(it); return@LaunchedEffect }
        }
        status = "The code ran out. Ask for a new one."
        pending = null
    }
    PickerTitle("Jellyfin")
    if (account != null) {
        SettingsRow("Signed in to ${account.server} as ${account.userName}.")
        FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
            SettingsAction("Sign out") {
                MultiviewStore.setJellyfin(context, null)
                tick++
                onChanged()
            }
        }
        return
    }
    val found by produceState<List<JellyfinServer>?>(null) { value = withContext(Dispatchers.IO) { JellyfinClient.discover() } }
    SettingsRow(
        when {
            found == null -> "Looking for servers on your network…"
            found.isNullOrEmpty() -> "No server answered on your network: type its address (e.g. 192.168.1.20:8096)."
            else -> "Found on your network:"
        },
    )
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        found.orEmpty().forEach { server -> SettingsAction("${server.name} · ${server.address}", selected = address == server.address) { address = server.address } }
    }
    Spacer(Modifier.height(8.dp))
    FocusableTextField(value = address, onValueChange = { address = it }, hint = "Server address", onDone = {})
    Spacer(Modifier.height(10.dp))
    fun withServer(action: suspend (String) -> Unit) {
        if (address.isBlank()) {
            status = "Choose or type the server's address first."
            return
        }
        val server = JellyfinClient.normalise(address)
        status = "Connecting…"
        scope.launch {
            if (withContext(Dispatchers.IO) { JellyfinClient.serverName(server) } == null) {
                status = "No Jellyfin server at $server."
            } else {
                action(server)
            }
        }
    }
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        SettingsAction("Sign in with Quick Connect") {
            withServer { server ->
                val qc = withContext(Dispatchers.IO) { JellyfinClient.startQuickConnect(context, server) }
                pending = qc
                status = if (qc == null) "Quick Connect is off on this server: sign in with name and password." else null
            }
        }
    }
    pending?.let { SettingsRow("Code ${it.code}: enter it in Jellyfin on your phone or computer (your profile → Quick Connect).") }
    Spacer(Modifier.height(10.dp))
    FocusableTextField(value = user, onValueChange = { user = it }, hint = "Name", onDone = {})
    Spacer(Modifier.height(8.dp))
    FocusableTextField(value = password, onValueChange = { password = it }, hint = "Password", onDone = {}, password = true)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        SettingsAction("Sign in with name and password") {
            withServer { server ->
                val signed = withContext(Dispatchers.IO) { JellyfinClient.signIn(context, server, user.trim(), password) }
                if (signed == null) status = "That name and password were not accepted." else signedIn(signed)
            }
        }
    }
    status?.let { SettingsRow(it) }
}

/** The IPTV playlist's address (M3U), and loading its channels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistSection(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(MultiviewStore.playlist(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    PickerTitle("IPTV playlist")
    FocusableTextField(value = url, onValueChange = { url = it }, hint = "Playlist address (M3U)", onDone = {})
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        SettingsAction("Load channels") {
            MultiviewStore.setPlaylist(context, url)
            status = "Loading…"
            scope.launch {
                val count = withContext(Dispatchers.IO) { IptvPlaylist.refresh(context, url.trim()) }
                status = if (count == null) "Could not read that playlist." else "$count channels."
                onChanged()
            }
        }
    }
    status?.let { SettingsRow(it) }
}

/** Cameras (or any stream) added by name and address. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraSection(onChanged: () -> Unit) {
    val context = LocalContext.current
    var cameras by remember { mutableStateOf(MultiviewStore.cameras(context)) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    fun save(next: List<MultiviewCamera>) {
        MultiviewStore.setCameras(context, next)
        cameras = next
        onChanged()
    }
    PickerTitle("Cameras")
    SettingsRow("A camera's stream address, such as rtsp://192.168.1.30:554/stream1 (with its name and password if it needs them).")
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        cameras.forEach { cam -> SettingsAction("Remove ${cam.name}") { save(cameras - cam) } }
    }
    FocusableTextField(value = name, onValueChange = { name = it }, hint = "Name", onDone = {})
    Spacer(Modifier.height(8.dp))
    FocusableTextField(value = url, onValueChange = { url = it }, hint = "Stream address", onDone = {})
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Flow, verticalArrangement = Flow) {
        SettingsAction("Add camera") {
            if (url.isNotBlank()) {
                save(cameras + MultiviewCamera(name.trim().ifBlank { "Camera ${cameras.size + 1}" }, url.trim()))
                name = ""
                url = ""
            }
        }
    }
}
