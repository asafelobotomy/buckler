package dev.solon.bucklerhome

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
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
                val poll = withContext(Dispatchers.IO) { TwitchDeviceAuth.poll(s) }
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConnectGithubDialog(onDismiss: () -> Unit, onLinked: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    val qr = remember { QrBitmap.encode(GITHUB_TOKEN_URL) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            onClick = {},
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ColorFooter,
                focusedContainerColor = ColorFooter,
                contentColor = ColorText,
                focusedContentColor = ColorText,
            ),
            modifier = Modifier.widthIn(min = 480.dp, max = 720.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text("Link GitHub (private repo)", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Scan QR → create a fine-grained PAT with Contents + Releases read → paste below.",
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR to GitHub token settings",
                            modifier = Modifier.size(180.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = GITHUB_TOKEN_URL, fontSize = 12.sp, color = ColorMuted)
                        Text(
                            text = "Personal access token",
                            fontSize = 13.sp,
                            color = ColorSection,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                        BasicTextField(
                            value = token,
                            onValueChange = { token = it },
                            singleLine = true,
                            textStyle = TextStyle(color = ColorText, fontSize = 16.sp),
                            cursorBrush = SolidColor(ColorBrand),
                            modifier = Modifier
                                .widthIn(min = 240.dp)
                                .padding(vertical = 8.dp),
                        )
                        error?.let {
                            Text(text = it, color = Color(0xFFFF8A80), fontSize = 13.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            SettingsAction("Link") {
                                if (token.isBlank()) {
                                    error = "Paste a GitHub token"
                                    return@SettingsAction
                                }
                                CoverAccounts.setGithubToken(context, token)
                                onLinked()
                            }
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
fun ConnectSgdbDialog(onDismiss: () -> Unit, onLinked: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    val qr = remember { QrBitmap.encode(SteamGridDbClient.PREFERENCES_URL) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            onClick = {},
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ColorFooter,
                focusedContainerColor = ColorFooter,
                contentColor = ColorText,
                focusedContentColor = ColorText,
            ),
            modifier = Modifier.widthIn(min = 480.dp, max = 720.dp),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text("Connect SteamGridDB", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Scan QR → create an API key on your phone → enter the key below.",
                    fontSize = 14.sp,
                    color = ColorMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "QR to SteamGridDB preferences",
                            modifier = Modifier.size(180.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = SteamGridDbClient.PREFERENCES_URL, fontSize = 12.sp, color = ColorMuted)
                        Text(
                            text = "API key",
                            fontSize = 13.sp,
                            color = ColorSection,
                            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                        )
                        BasicTextField(
                            value = token,
                            onValueChange = { token = it },
                            singleLine = true,
                            textStyle = TextStyle(color = ColorText, fontSize = 16.sp),
                            cursorBrush = SolidColor(ColorBrand),
                            modifier = Modifier
                                .widthIn(min = 240.dp)
                                .padding(vertical = 8.dp),
                        )
                        error?.let {
                            Text(text = it, color = Color(0xFFFF8A80), fontSize = 13.sp)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            SettingsAction("Link") {
                                if (token.isBlank()) {
                                    error = "Paste the API key from preferences"
                                    return@SettingsAction
                                }
                                CoverAccounts.setSgdbToken(context, token)
                                onLinked()
                            }
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
    Surface(
        onClick = {},
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorFooter,
            focusedContainerColor = ColorFooter,
            contentColor = ColorText,
            focusedContentColor = ColorText,
        ),
        modifier = Modifier.widthIn(min = 480.dp, max = 720.dp),
    ) {
        Column(modifier = Modifier.padding(28.dp)) {
            Text(text = title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
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
                    SettingsAction("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}
