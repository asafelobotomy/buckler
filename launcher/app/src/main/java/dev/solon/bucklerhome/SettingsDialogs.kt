package dev.solon.bucklerhome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** City search backed by Open-Meteo geocoding; picking a result stores it as the weather place. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WeatherLocationDialog(onDismiss: () -> Unit, onPicked: (Place) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { fieldFocus.requestFocus() }
    }

    fun search() {
        if (query.isBlank() || searching) return
        searching = true
        status = "Searching…"
        // The on-screen keyboard swallows D-pad keys and hides the results, so drop it first.
        keyboard?.hide()
        scope.launch {
            val found = withContext(Dispatchers.IO) { WeatherClient.search(query) }
            results = found
            status = if (found.isEmpty()) "No matches — check spelling or your connection" else null
            searching = false
            if (found.isNotEmpty()) {
                delay(120)
                runCatching { firstResult.requestFocus() }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 460.dp, maxWidth = 640.dp) {
            Column {
                Text("Weather location", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
                Text(
                    text = "Type a city, then choose the match. Weather by Open-Meteo (free, no account).",
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
                FocusableTextField(
                    value = query,
                    onValueChange = { query = it },
                    hint = "City name",
                    onDone = ::search,
                    focusRequester = fieldFocus,
                )
                status?.let {
                    Text(text = it, fontSize = 13.sp, color = ColorMuted, modifier = Modifier.padding(top = 8.dp))
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    results.forEachIndexed { index, place ->
                        SettingsAction(
                            place.name,
                            focusRequester = if (index == 0) firstResult else null,
                        ) { onPicked(place) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                    SettingsAction("Search", onClick = ::search)
                    SettingsAction("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}

/** Shows a QR + URL while [UploadServer] accepts photos from a phone on the same network. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UploadPhotosDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val server = remember { UploadServer(context.applicationContext) }
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(ScreensaverImages.uploaded(context).size) }
    val qr = remember(url) { url?.let { QrBitmap.encode(it) } }

    DisposableEffect(server) {
        if (server.start()) {
            url = server.url()
            failed = url == null
        } else {
            failed = true
        }
        onDispose { server.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            count = withContext(Dispatchers.IO) { ScreensaverImages.uploaded(context).size }
            delay(1_000)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 480.dp, maxWidth = 720.dp, modifier = Modifier.autoFocus()) {
            Column {
                Text("Upload photos", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
                Text(
                    text = if (failed) {
                        "Could not start the upload server — is the TV on your network?"
                    } else {
                        "Scan the QR with a phone on the same Wi-Fi, pick images and tap Upload."
                    },
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Upload page QR",
                            modifier = Modifier.size(180.dp),
                        )
                    }
                    Column {
                        url?.let { Text(text = it, fontSize = 13.sp, color = ColorText) }
                        Text(
                            text = "$count photo(s) on this TV",
                            fontSize = 14.sp,
                            color = ColorSection,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        SettingsAction("Done", onClick = onDismiss, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }
}
