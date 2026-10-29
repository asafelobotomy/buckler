package dev.solon.bucklerhome

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private const val PREFS = "bucklerhome"
private const val KEY_FIRST_RUN_DONE = "first_run_done"

/** The wizard has not been finished yet on this TV: Home opens it first. */
fun isFirstRunPending(context: Context): Boolean =
    !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FIRST_RUN_DONE, false)

fun markFirstRunDone(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FIRST_RUN_DONE, true).apply()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StepHeader(title: String, progress: String) {
    Text(text = "Buckler setup  ·  $progress", color = ColorBrand, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(6.dp))
    Text(text = title, color = ColorText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StepBody(text: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = text, color = ColorMuted, fontSize = 18.sp, lineHeight = 27.sp, modifier = Modifier.widthIn(max = 900.dp))
    Spacer(modifier = Modifier.height(22.dp))
}

/** The wizard's buttons. Focus turns either kind the page's text colour, so it never looks like Primary. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(260.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorBrand,
            focusedContainerColor = ColorText,
            contentColor = ColorOnBrand,
            focusedContentColor = ColorFooter,
        ),
    ) {
        Text(text = label, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(260.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorFooter,
            focusedContainerColor = ColorText,
            contentColor = ColorText,
            focusedContentColor = ColorFooter,
        ),
    ) {
        Text(text = label, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), fontSize = 16.sp)
    }
}
