package dev.solon.bucklerhome

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat

/**
 * Compose family for a font choice. Hyperlegible is Atkinson Hyperlegible (SIL OFL), bundled for
 * low-vision readers; the rest are fonts every Android device ships.
 */
@OptIn(ExperimentalTextApi::class)
fun FontChoice.family(): FontFamily = when (this) {
    FontChoice.SYSTEM -> FontFamily.Default
    FontChoice.HYPERLEGIBLE -> FontFamily(
        Font(R.font.atkinson_regular, FontWeight.Normal),
        Font(R.font.atkinson_bold, FontWeight.Bold),
    )
    FontChoice.CONDENSED -> deviceFamily("sans-serif-condensed")
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.PLAYFUL -> deviceFamily("casual")
    FontChoice.MONO -> FontFamily.Monospace
}

@OptIn(ExperimentalTextApi::class)
private fun deviceFamily(name: String): FontFamily = FontFamily(
    Font(DeviceFontFamilyName(name), FontWeight.Normal),
    Font(DeviceFontFamilyName(name), FontWeight.Bold),
)

/** Android view typeface for the screensaver, which is not drawn with Compose. */
fun FontChoice.typeface(context: Context, light: Boolean): Typeface = when (this) {
    FontChoice.SYSTEM -> Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
    FontChoice.HYPERLEGIBLE ->
        ResourcesCompat.getFont(context, R.font.atkinson_regular) ?: Typeface.SANS_SERIF
    FontChoice.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    FontChoice.SERIF -> Typeface.create("serif", Typeface.NORMAL)
    FontChoice.PLAYFUL -> Typeface.create("casual", Typeface.NORMAL)
    FontChoice.MONO -> Typeface.MONOSPACE
}
