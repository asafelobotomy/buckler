package dev.solon.shieldhome

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private const val PREFS = "shieldhome"
private const val KEY_FIRST_RUN_DONE = "first_run_done"

fun isFirstRunPending(context: Context): Boolean =
    !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_FIRST_RUN_DONE, false)

fun markFirstRunDone(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_FIRST_RUN_DONE, true)
        .apply()
}

private data class WizardPage(
    val title: String,
    val body: String,
)

/** On-TV pages aligned with Buckler / kitetvOS wizard phase names. */
private val PAGES = listOf(
    WizardPage(
        "Welcome",
        "shieldhome is your ad-free HOME.\n" +
            "Host tool: Buckler (buckler setup) · Future OS: kitetvOS first-boot.",
    ),
    WizardPage(
        "Privacy",
        "Telemetry, partner auto-installs, and GameStream leftovers are stripped " +
            "via Buckler profiles — AI upscaling stays on.",
    ),
    WizardPage(
        "Voice",
        "Mic / Assistant stacks are off. Chromecast built-in stays available.",
    ),
    WizardPage(
        "Home & screensaver",
        "This launcher owns the Home button. Screensaver is a drifting clock " +
            "(Backdrop removed).",
    ),
    WizardPage(
        "You're set",
        "Use the dock for media apps, Library for the rest, Settings for the system.\n" +
            "After a stock factory reset, run buckler setup again on your PC.\n" +
            "On kitetvOS, this wizard will return automatically after reset.",
    ),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FirstRunScreen(
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    val current = PAGES[page]
    val bg = Brush.verticalGradient(
        colors = listOf(Color(0xFF0B0E14), Color(0xFF1A2230), Color(0xFF0B0E14)),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 64.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "shieldhome setup  ·  ${page + 1}/${PAGES.size}",
            color = Color(0xFF7EB6FF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = current.title,
            color = Color(0xFFE8ECF2),
            fontSize = 36.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = current.body,
            color = Color(0xFF9AA6B8),
            fontSize = 18.sp,
            lineHeight = 28.sp,
        )
        Spacer(modifier = Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (page > 0) {
                SecondaryButton("Back") { page -= 1 }
            }
            if (page < PAGES.lastIndex) {
                PrimaryButton("Next") { page += 1 }
            } else {
                PrimaryButton("Continue to Home", onContinue)
            }
            SecondaryButton("Android TV Settings", onOpenSettings)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF7EB6FF),
            focusedContainerColor = Color(0xFFA8D0FF),
            contentColor = Color(0xFF001B3D),
            focusedContentColor = Color(0xFF001B3D),
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF243044),
            focusedContainerColor = Color(0xFF7EB6FF),
            contentColor = Color(0xFFE8ECF2),
            focusedContentColor = Color(0xFF001B3D),
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            fontSize = 16.sp,
        )
    }
}
