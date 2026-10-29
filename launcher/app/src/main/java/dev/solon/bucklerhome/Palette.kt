package dev.solon.bucklerhome

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Every colour the launcher draws with. Screens read these through the `Color*` accessors. */
@Immutable
data class Palette(
    val dark: Boolean,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val brand: Color,
    val onBrand: Color,
    val text: Color,
    val muted: Color,
    val hint: Color,
    val section: Color,
    val tile: Color,
    val footer: Color,
    val border: Color,
    /** Outline colour for the strong-focus accessibility option. */
    val ring: Color,
) {
    val background: Brush = Brush.verticalGradient(colors = listOf(bgTop, bgMid, bgBottom))
}

private fun c(argb: Long) = Color(argb)

object Palettes {
    /** Teal and sage from the Buckler mark: the default scheme. */
    private val BUCKLER = Palette(
        dark = true,
        bgTop = c(0xFF0F1B1C), bgMid = c(0xFF132829), bgBottom = c(0xFF0F1B1C),
        brand = c(0xFF83A598), onBrand = c(0xFF0F1B1C),
        text = c(0xFFE6EEEC), muted = c(0xFF9FB5B0), hint = c(0xFF738A86), section = c(0xFF6FB0B3),
        tile = c(0xFF1B3132), footer = c(0xFF24403F), border = c(0xFF3C5A59), ring = c(0xFFFFFFFF),
    )

    /** The original gold-on-navy look. */
    private val DARK = Palette(
        dark = true,
        bgTop = c(0xFF0B0E14), bgMid = c(0xFF161A12), bgBottom = c(0xFF0B0E14),
        brand = c(0xFFD4A84B), onBrand = c(0xFF1A1408),
        text = c(0xFFE8ECF2), muted = c(0xFF9AA6B8), hint = c(0xFF6B778A), section = c(0xFF8FAE7A),
        tile = c(0xFF1C2433), footer = c(0xFF243044), border = c(0xFF3A4558), ring = c(0xFFFFFFFF),
    )

    private val LIGHT = Palette(
        dark = false,
        bgTop = c(0xFFF7F4EC), bgMid = c(0xFFEFEADD), bgBottom = c(0xFFF7F4EC),
        brand = c(0xFF8A5A00), onBrand = c(0xFFFFFFFF),
        text = c(0xFF1B1F27), muted = c(0xFF454E5E), hint = c(0xFF5F6877), section = c(0xFF2F6B1F),
        tile = c(0xFFE3DECF), footer = c(0xFFD6D0BE), border = c(0xFFB5AD99), ring = c(0xFF000000),
    )

    private val GRUVBOX = Palette(
        dark = true,
        bgTop = c(0xFF282828), bgMid = c(0xFF1D2021), bgBottom = c(0xFF282828),
        brand = c(0xFFFABD2F), onBrand = c(0xFF282828),
        text = c(0xFFEBDBB2), muted = c(0xFFBDAE93), hint = c(0xFF928374), section = c(0xFFB8BB26),
        tile = c(0xFF3C3836), footer = c(0xFF504945), border = c(0xFF665C54), ring = c(0xFFFBF1C7),
    )

    /** PAL / Super Famicom pad: grey shell, red / yellow / green / blue buttons. */
    private val SNES_UK = Palette(
        dark = true,
        bgTop = c(0xFF34353B), bgMid = c(0xFF28292E), bgBottom = c(0xFF34353B),
        brand = c(0xFFEF5B5B), onBrand = c(0xFF1E0707),
        text = c(0xFFEEEEF2), muted = c(0xFFBABBC5), hint = c(0xFF8E8F9A), section = c(0xFF6FD08A),
        tile = c(0xFF46474F), footer = c(0xFF595A64), border = c(0xFF6C6D78), ring = c(0xFFFFD84A),
    )

    /** North American pad: pale grey shell with lavender-purple buttons. */
    private val SNES_US = Palette(
        dark = true,
        bgTop = c(0xFF34333F), bgMid = c(0xFF282731), bgBottom = c(0xFF34333F),
        brand = c(0xFFA98CE6), onBrand = c(0xFF1A1033),
        text = c(0xFFEFEEF5), muted = c(0xFFBFBDCF), hint = c(0xFF9391A5), section = c(0xFFCFC3F2),
        tile = c(0xFF4A4959), footer = c(0xFF5E5D6E), border = c(0xFF727184), ring = c(0xFFFFFFFF),
    )

    /** NVIDIA green on black, like the stock SHIELD interface. */
    private val SHIELD = Palette(
        dark = true,
        bgTop = c(0xFF0A0A0A), bgMid = c(0xFF111611), bgBottom = c(0xFF0A0A0A),
        brand = c(0xFF76B900), onBrand = c(0xFF0A0A0A),
        text = c(0xFFF2F2F2), muted = c(0xFFA8A8A8), hint = c(0xFF7A7A7A), section = c(0xFFA6DC4F),
        tile = c(0xFF1C1C1C), footer = c(0xFF2B2B2B), border = c(0xFF444444), ring = c(0xFFFFFFFF),
    )

    /** Cool slate blues with a sky-blue accent: the One Dark mood. */
    private val SLATE = Palette(
        dark = true,
        bgTop = c(0xFF1E2229), bgMid = c(0xFF262A32), bgBottom = c(0xFF1E2229),
        brand = c(0xFF6CB4F2), onBrand = c(0xFF0E141C),
        text = c(0xFFE4E8EE), muted = c(0xFFAAB3BF), hint = c(0xFF808A98), section = c(0xFF9CC77E),
        tile = c(0xFF2D323B), footer = c(0xFF383E49), border = c(0xFF4A5160), ring = c(0xFFFFFFFF),
    )

    /** Deep plum with violet, mint and pink: the Dracula mood. */
    private val VELVET = Palette(
        dark = true,
        bgTop = c(0xFF211B2D), bgMid = c(0xFF2A2338), bgBottom = c(0xFF211B2D),
        brand = c(0xFFB88CF4), onBrand = c(0xFF1A1030),
        text = c(0xFFF5F2F8), muted = c(0xFFBDB6D3), hint = c(0xFF8F88AA), section = c(0xFF5EE39A),
        tile = c(0xFF362E47), footer = c(0xFF433A57), border = c(0xFF594F70), ring = c(0xFFFF7AC2),
    )

    /** Warm charcoal with hot pink and lime: the Monokai mood. */
    private val EMBER = Palette(
        dark = true,
        bgTop = c(0xFF22201A), bgMid = c(0xFF2A2822), bgBottom = c(0xFF22201A),
        brand = c(0xFFFF5C8A), onBrand = c(0xFF1F0A12),
        text = c(0xFFF4F1E6), muted = c(0xFFC6C1AE), hint = c(0xFF98927C), section = c(0xFFAFDC3C),
        tile = c(0xFF37342C), footer = c(0xFF45423A), border = c(0xFF5B584D), ring = c(0xFFEADF7A),
    )

    /** Deep sea teal with clear blue and olive: the Solarized Dark mood. */
    private val TIDEPOOL = Palette(
        dark = true,
        bgTop = c(0xFF03262F), bgMid = c(0xFF0A3140), bgBottom = c(0xFF03262F),
        brand = c(0xFF31A8D6), onBrand = c(0xFF001C24),
        text = c(0xFFEFE9D8), muted = c(0xFFA9BBBA), hint = c(0xFF86989A), section = c(0xFFBCC63A),
        tile = c(0xFF113E4B), footer = c(0xFF1D4C5A), border = c(0xFF2F6170), ring = c(0xFFFDF6E3),
    )

    /** Arctic greys with frost blue and moss: the Nord mood. */
    private val FJORD = Palette(
        dark = true,
        bgTop = c(0xFF262C37), bgMid = c(0xFF2C333F), bgBottom = c(0xFF262C37),
        brand = c(0xFF8CC4D2), onBrand = c(0xFF1A212C),
        text = c(0xFFEDF0F5), muted = c(0xFFBAC4D3), hint = c(0xFF8D98AB), section = c(0xFFA8C291),
        tile = c(0xFF383F4E), footer = c(0xFF414A5B), border = c(0xFF4E586C), ring = c(0xFFFFFFFF),
    )

    /** Soft night with pastel lilac and mint: the Catppuccin mood. */
    private val MARSHMALLOW = Palette(
        dark = true,
        bgTop = c(0xFF1A1A29), bgMid = c(0xFF202032), bgBottom = c(0xFF1A1A29),
        brand = c(0xFFC9A4F5), onBrand = c(0xFF1D1A2E),
        text = c(0xFFD2D8F2), muted = c(0xFFA9AFC9), hint = c(0xFF81869E), section = c(0xFFA9E0A4),
        tile = c(0xFF313246), footer = c(0xFF3D3F55), border = c(0xFF4A4C62), ring = c(0xFFF7C4E4),
    )

    /** Late-night navy with electric blue and lime: the Tokyo Night mood. */
    private val NEON = Palette(
        dark = true,
        bgTop = c(0xFF14151E), bgMid = c(0xFF1A1C28), bgBottom = c(0xFF14151E),
        brand = c(0xFF7FA6F5), onBrand = c(0xFF10131C),
        text = c(0xFFD8DCF2), muted = c(0xFFAAB2D6), hint = c(0xFF7B83A8), section = c(0xFFA2CF6E),
        tile = c(0xFF24283A), footer = c(0xFF2F3448), border = c(0xFF42496A), ring = c(0xFFC29EF6),
    )

    /** Near-black with bright blue and green: the GitHub Dark mood. */
    private val GRAPHITE = Palette(
        dark = true,
        bgTop = c(0xFF0E1218), bgMid = c(0xFF131922), bgBottom = c(0xFF0E1218),
        brand = c(0xFF58A2F8), onBrand = c(0xFF0A0F16),
        text = c(0xFFE8EDF2), muted = c(0xFFA0A9B4), hint = c(0xFF7F8791), section = c(0xFF4CC35E),
        tile = c(0xFF1E242D), footer = c(0xFF282E38), border = c(0xFF3E454F), ring = c(0xFFFFFFFF),
    )

    /** Dusky purple with rose and sea-foam: the Rosé Pine mood. */
    private val ROSEWOOD = Palette(
        dark = true,
        bgTop = c(0xFF1B1824), bgMid = c(0xFF221F31), bgBottom = c(0xFF1B1824),
        brand = c(0xFFEDB7B3), onBrand = c(0xFF1B1824),
        text = c(0xFFE3E0F2), muted = c(0xFFB6B1CD), hint = c(0xFF8E8AA7), section = c(0xFF98CBD5),
        tile = c(0xFF2C2940), footer = c(0xFF353149), border = c(0xFF4A4561), ring = c(0xFFF4C07A),
    )

    /** Ink black with saffron orange and lime: the Ayu mood. */
    private val SAFFRON = Palette(
        dark = true,
        bgTop = c(0xFF0E1118), bgMid = c(0xFF12161E), bgBottom = c(0xFF0E1118),
        brand = c(0xFFFFB64F), onBrand = c(0xFF1A1205),
        text = c(0xFFE8E3D1), muted = c(0xFFB5B3A8), hint = c(0xFF8C8B80), section = c(0xFFB0D84F),
        tile = c(0xFF1D222C), footer = c(0xFF282E39), border = c(0xFF3C4453), ring = c(0xFFFFFFFF),
    )

    private val HIGH_CONTRAST_DARK = Palette(
        dark = true,
        bgTop = c(0xFF000000), bgMid = c(0xFF000000), bgBottom = c(0xFF000000),
        brand = c(0xFFFFE600), onBrand = c(0xFF000000),
        text = c(0xFFFFFFFF), muted = c(0xFFE6E6E6), hint = c(0xFFCCCCCC), section = c(0xFF7CFF9B),
        tile = c(0xFF161616), footer = c(0xFF2A2A2A), border = c(0xFFFFFFFF), ring = c(0xFFFFFFFF),
    )

    private val HIGH_CONTRAST_LIGHT = Palette(
        dark = false,
        bgTop = c(0xFFFFFFFF), bgMid = c(0xFFFFFFFF), bgBottom = c(0xFFFFFFFF),
        brand = c(0xFF0A3DB0), onBrand = c(0xFFFFFFFF),
        text = c(0xFF000000), muted = c(0xFF1A1A1A), hint = c(0xFF333333), section = c(0xFF0B5C00),
        tile = c(0xFFEDEDED), footer = c(0xFFDADADA), border = c(0xFF000000), ring = c(0xFF000000),
    )

    fun of(theme: AppTheme): Palette = when (theme) {
        AppTheme.BUCKLER -> BUCKLER
        AppTheme.LIGHT -> LIGHT
        AppTheme.DARK -> DARK
        AppTheme.GRUVBOX -> GRUVBOX
        AppTheme.SNES_UK -> SNES_UK
        AppTheme.SNES_US -> SNES_US
        AppTheme.SHIELD -> SHIELD
        AppTheme.SLATE -> SLATE
        AppTheme.VELVET -> VELVET
        AppTheme.EMBER -> EMBER
        AppTheme.TIDEPOOL -> TIDEPOOL
        AppTheme.FJORD -> FJORD
        AppTheme.MARSHMALLOW -> MARSHMALLOW
        AppTheme.NEON -> NEON
        AppTheme.GRAPHITE -> GRAPHITE
        AppTheme.ROSEWOOD -> ROSEWOOD
        AppTheme.SAFFRON -> SAFFRON
    }

    /** High contrast keeps the light/dark choice but drops the decorative colours. */
    fun resolve(config: AppearanceConfig): Palette {
        val base = of(config.theme)
        return when {
            !config.highContrast -> base
            base.dark -> HIGH_CONTRAST_DARK
            else -> HIGH_CONTRAST_LIGHT
        }
    }

    /** WCAG relative luminance contrast ratio between two colours (1..21). */
    fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun luminance(color: Color): Float {
        fun channel(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
    }
}
