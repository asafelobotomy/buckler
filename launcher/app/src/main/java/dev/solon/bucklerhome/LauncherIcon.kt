package dev.solon.bucklerhome

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

/** The Buckler mark drawn in a colour scheme's own colours (see scripts/gen_icons.py). */
@get:DrawableRes
val AppTheme.markRes: Int
    get() = when (this) {
        AppTheme.BUCKLER -> R.drawable.ic_mark_buckler
        AppTheme.LIGHT -> R.drawable.ic_mark_light
        AppTheme.DARK -> R.drawable.ic_mark_dark
        AppTheme.GRUVBOX -> R.drawable.ic_mark_gruvbox
        AppTheme.SNES_UK -> R.drawable.ic_mark_snes_uk
        AppTheme.SNES_US -> R.drawable.ic_mark_snes_us
        AppTheme.SHIELD -> R.drawable.ic_mark_shield
        AppTheme.SLATE -> R.drawable.ic_mark_slate
        AppTheme.VELVET -> R.drawable.ic_mark_velvet
        AppTheme.EMBER -> R.drawable.ic_mark_ember
        AppTheme.TIDEPOOL -> R.drawable.ic_mark_tidepool
        AppTheme.FJORD -> R.drawable.ic_mark_fjord
        AppTheme.MARSHMALLOW -> R.drawable.ic_mark_marshmallow
        AppTheme.NEON -> R.drawable.ic_mark_neon
        AppTheme.GRAPHITE -> R.drawable.ic_mark_graphite
        AppTheme.ROSEWOOD -> R.drawable.ic_mark_rosewood
        AppTheme.SAFFRON -> R.drawable.ic_mark_saffron
    }

/**
 * Swaps the app-list icon and banner to match the colour scheme. Android has no API to change an
 * app icon, so the manifest declares one activity-alias pair per scheme (Home + Settings entries)
 * and exactly one pair is enabled. The HOME role lives on MainActivity itself and is never touched.
 */
object LauncherIcon {
    /** "SNES_UK" → "SnesUk", matching `.LauncherSnesUk` / `.SettingsSnesUk` in the manifest. */
    fun aliasSuffix(theme: AppTheme): String =
        theme.name.split('_').joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

    private fun aliases(context: Context, theme: AppTheme): List<ComponentName> {
        val suffix = aliasSuffix(theme)
        return listOf("Launcher", "Settings").map { ComponentName(context, "${context.packageName}.$it$suffix") }
    }

    fun apply(context: Context, theme: AppTheme) {
        val prefs = context.getSharedPreferences("bucklerhome", Context.MODE_PRIVATE)
        // Done already for this scheme: no need to ask the system about every alias again.
        if (prefs.getString(KEY_APPLIED, null) == theme.name) return
        val pm = context.packageManager
        // Enable the new pair before disabling the old one so the app never vanishes from the list.
        val ordered = listOf(theme) + AppTheme.entries.filter { it != theme }
        for (each in ordered) {
            val state = if (each == theme) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            for (alias in aliases(context, each)) {
                if (current(pm, alias, each) == state) continue
                runCatching { pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP) }
            }
        }
        prefs.edit().putString(KEY_APPLIED, theme.name).apply()
    }

    private const val KEY_APPLIED = "launcher_icon_applied"

    private fun current(pm: PackageManager, alias: ComponentName, theme: AppTheme): Int =
        // A scheme without its alias (they are kept in step by hand) reads as "not enabled".
        when (val setting = runCatching { pm.getComponentEnabledSetting(alias) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)) {
            // Manifest default: only the Buckler pair ships enabled.
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ->
                if (theme == AppTheme.BUCKLER) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            else -> setting
        }
}
