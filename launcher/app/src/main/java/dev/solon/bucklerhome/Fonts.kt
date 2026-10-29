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
 * low-vision readers; Inter through Merriweather are popular Google Fonts (SIL OFL), bundled in
 * Regular and Bold by scripts/gen_fonts.py; the rest are fonts every Android device ships.
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
    else -> bundled.getValue(this).let { (regular, bold) ->
        FontFamily(Font(regular, FontWeight.Normal), Font(bold, FontWeight.Bold))
    }
}

/** Regular and Bold resources of each bundled Google Font. */
private val bundled: Map<FontChoice, Pair<Int, Int>> = mapOf(
    FontChoice.INTER to (R.font.inter_regular to R.font.inter_bold),
    FontChoice.MONTSERRAT to (R.font.montserrat_regular to R.font.montserrat_bold),
    FontChoice.POPPINS to (R.font.poppins_regular to R.font.poppins_bold),
    FontChoice.LATO to (R.font.lato_regular to R.font.lato_bold),
    FontChoice.OPEN_SANS to (R.font.opensans_regular to R.font.opensans_bold),
    FontChoice.NUNITO to (R.font.nunito_regular to R.font.nunito_bold),
    FontChoice.RALEWAY to (R.font.raleway_regular to R.font.raleway_bold),
    FontChoice.OSWALD to (R.font.oswald_regular to R.font.oswald_bold),
    FontChoice.PLAYFAIR to (R.font.playfair_regular to R.font.playfair_bold),
    FontChoice.MERRIWEATHER to (R.font.merriweather_regular to R.font.merriweather_bold),
)

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
    else -> ResourcesCompat.getFont(context, bundled.getValue(this).first) ?: Typeface.SANS_SERIF
}
