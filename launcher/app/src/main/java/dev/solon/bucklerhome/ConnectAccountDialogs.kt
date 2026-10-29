package dev.solon.bucklerhome

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConnectIgdbDialog(onDismiss: () -> Unit, onLinked: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<DeviceCodeSession?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Starting…") }

    LaunchedEffect(Unit) {
        if (!TwitchDeviceAuth.clientIdConfigured()) {
            error = "Twitch Client ID not set. Maintainers: set TWITCH_CLIENT_ID in build.gradle.kts."
            status = "Unavailable"
            return@LaunchedEffect
        }
        val started = withContext(Dispatchers.IO) { TwitchDeviceAuth.startDeviceCode() }
        started.onSuccess { s ->
            session = s
            qr = withContext(Dispatchers.Default) {
                QrBitmap.encode(s.verificationUriComplete ?: s.verificationUri)
            }
            status = "Scan QR or open the URL and enter the code"
            var interval = s.intervalSeconds
            val deadline = System.currentTimeMillis() + s.expiresInSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                // A network blip mid-poll is just another pending round, not a crash.
                val poll = withContext(Dispatchers.IO) {
                    runCatching { TwitchDeviceAuth.poll(s) }.getOrDefault(DevicePollResult.Pending)
                }
                when (poll) {
                    is DevicePollResult.Success -> {
                        CoverAccounts.setIgdbTokens(
                            context,
                            poll.accessToken,
                            poll.refreshToken,
                            poll.expiresIn,
                        )
                        onLinked()
                        return@LaunchedEffect
                    }
                    DevicePollResult.Pending -> status = "Waiting for approval…"
                    DevicePollResult.SlowDown -> {
                        interval += 2
                        status = "Slowing down…"
                    }
                    is DevicePollResult.Failed -> {
                        error = poll.message
                        return@LaunchedEffect
                    }
                }
            }
            error = "Timed out"
        }.onFailure {
            error = it.message
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        AccountConnectShell(
            title = "Connect IGDB",
            subtitle = status,
            userCode = session?.userCode,
            url = session?.verificationUri,
            qr = qr,
            error = error,
            onDismiss = onDismiss,
        )
    }
}

private const val GITHUB_TOKEN_URL = "https://github.com/settings/tokens?type=beta"

@Composable
fun ConnectGithubDialog(onDismiss: () -> Unit, onLinked: () -> Unit) {
    val context = LocalContext.current
    TokenDialog(
        title = "Link GitHub (optional)",
        instructions = "Scan QR → create a fine-grained PAT with Contents + Releases read → paste below.",
        url = GITHUB_TOKEN_URL,
        fieldLabel = "Personal access token",
        emptyError = "Paste a GitHub token",
        onDismiss = onDismiss,
        onSubmit = { token ->
            CoverAccounts.setGithubToken(context, token)
            onLinked()
        },
    )
}

@Composable
fun ConnectSgdbDialog(onDismiss: () -> Unit, onLinked: () -> Unit) {
    val context = LocalContext.current
    TokenDialog(
        title = "Connect SteamGridDB",
        instructions = "Scan QR → create an API key on your phone → enter the key below.",
        url = SteamGridDbClient.PREFERENCES_URL,
        fieldLabel = "API key",
        emptyError = "Paste the API key from preferences",
        onDismiss = onDismiss,
        onSubmit = { token ->
            CoverAccounts.setSgdbToken(context, token)
            onLinked()
        },
    )
}

/** QR + one text field + Link/Cancel. Focus starts on the field; D-pad down reaches the buttons. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TokenDialog(
    title: String,
    instructions: String,
    url: String,
    fieldLabel: String,
    emptyError: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val qr = remember(url) { QrBitmap.encode(url) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { fieldFocus.requestFocus() }
    }

    fun submit() {
        if (token.isBlank()) {
            error = emptyError
        } else {
            onSubmit(token.trim())
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(minWidth = 480.dp, maxWidth = 720.dp) {
            Column {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
                Text(
                    text = instructions,
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR to $title",
                            modifier = Modifier.size(180.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = url, fontSize = 12.sp, color = ColorMuted)
                        Text(
                            text = fieldLabel,
                            fontSize = 13.sp,
                            color = ColorSection,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                        FocusableTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                error = null
                            },
                            hint = "Type or paste token",
                            onDone = ::submit,
                            focusRequester = fieldFocus,
                        )
                        error?.let {
                            Text(
                                text = it,
                                color = Color(0xFFFF8A80),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            SettingsAction("Link", onClick = ::submit)
                            SettingsAction("Cancel", onClick = onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AccountConnectShell(
    title: String,
    subtitle: String,
    userCode: String?,
    url: String?,
    qr: Bitmap?,
    error: String?,
    onDismiss: () -> Unit,
) {
    DialogPanel(minWidth = 480.dp, maxWidth = 720.dp, modifier = Modifier.autoFocus()) {
        Column {
            Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ColorText)
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = ColorMuted,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                qr?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Login QR",
                        modifier = Modifier.size(180.dp),
                    )
                }
                Column {
                    userCode?.let {
                        Text(text = it, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = ColorBrand)
                    }
                    url?.let {
                        Text(text = it, fontSize = 13.sp, color = ColorMuted, modifier = Modifier.padding(top = 8.dp))
                    }
                    error?.let {
                        Text(
                            text = it,
                            color = Color(0xFFFF8A80),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    SettingsAction("Cancel", onClick = onDismiss, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
