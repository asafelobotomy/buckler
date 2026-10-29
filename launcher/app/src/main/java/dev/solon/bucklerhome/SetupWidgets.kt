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
    Spacer(modifier = Modifier.height(10.dp))
    Text(text = title, color = ColorText, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StepBody(text: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = text, color = ColorMuted, fontSize = 18.sp, lineHeight = 27.sp, modifier = Modifier.widthIn(max = 900.dp))
    Spacer(modifier = Modifier.height(22.dp))
}

/** An on/off row with an optional line of detail under its name; [indent] sets it under a group row. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ToggleRow(label: String, enabled: Boolean, detail: String? = null, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onChange(!enabled) },
        modifier = Modifier.padding(start = if (indent) 40.dp else 0.dp, bottom = 10.dp).width(if (indent) 560.dp else 600.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ColorTile,
            focusedContainerColor = ColorBrand,
            contentColor = ColorText,
            focusedContentColor = ColorOnBrand,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(text = "${if (enabled) "●" else "○"}  $label", fontSize = 17.sp)
            if (detail != null) Text(text = detail, fontSize = 14.sp, modifier = Modifier.padding(start = 26.dp, top = 2.dp))
        }
    }
}

/** The wizard's buttons. Focus turns either kind the page's text colour, so it never looks like Primary. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
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
fun SecondaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
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
