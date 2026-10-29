package dev.solon.bucklerhome

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Simple asks a few plain questions; Advanced shows every choice, app by app. */
enum class SetupMode { SIMPLE, ADVANCED }

/**
 * The clean-up switches Simple shows, built from the CLI's profiles. Google Assistant sits with
 * Voice rather than Privacy, so turning Privacy on never removes the Assistant by surprise.
 */
enum class CleanGroup(val label: String, val detail: String, val onByDefault: Boolean) {
    PRIVACY(
        "Privacy",
        "Google telemetry, recommendations, partner auto-installs, phone Smart Connect and GameStream leftovers.",
        true,
    ),
    EXTRAS("Extras", "Factory and setup leftovers, the SMB share, the OSC overlay, registration and welcome apps.", true),
    VOICE("Voice & Assistant", "Google Assistant, the hotword and on-device speech. Chromecast keeps working.", false),
    ;

    fun packages(data: ShieldData): List<String> {
        fun of(name: String) = data.profiles[name]?.packages.orEmpty()
        return when (this) {
            PRIVACY -> (of("minimal") + of("privacy-core")).filterNot { it == ASSISTANT }
            EXTRAS -> of("extras")
            VOICE -> of("speech") + ASSISTANT
        }.distinct()
    }

    companion object {
        const val ASSISTANT = "com.google.android.katniss"
    }
}

/** Private DNS: off, the network's own, or a named DNS-over-TLS host. */
data class DnsChoice(val mode: String, val host: String = "") {
    companion object {
        val OFF = DnsChoice("off")
        val AUTOMATIC = DnsChoice("opportunistic")
    }
}

/**
 * Everything the setup wizard can set, read from how the TV is now when it opens (a second run
 * starts from the current state, and changing nothing changes nothing). [offApps] are the
 * clean-up apps meant to be off; Simple keeps them in step with [groups].
 */
data class SetupChoices(
    val mode: SetupMode = SetupMode.SIMPLE,
    val theme: AppTheme,
    val font: FontChoice,
    val layout: CoverLayout,
    val tileSize: TileSize,
    val shelves: Map<ShelfType, Boolean>,
    val saver: ScreensaverMode,
    val groups: Set<CleanGroup>,
    val offApps: Set<String>,
    val animation: Float,
    val dns: DnsChoice,
) {
    fun withGroup(group: CleanGroup, on: Boolean, data: ShieldData): SetupChoices {
        val apps = group.packages(data).toSet()
        return copy(groups = if (on) groups + group else groups - group, offApps = if (on) offApps + apps else offApps - apps)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name).put("theme", theme.name).put("font", font.name).put("layout", layout.name)
        .put("tile_size", tileSize.name).put("saver", saver.name).put("animation", animation.toDouble())
        .put("dns_mode", dns.mode).put("dns_host", dns.host)
        .put("shelves", JSONObject().apply { shelves.forEach { (k, v) -> put(k.name, v) } })
        .put("groups", JSONArray(groups.map { it.name }))
        .put("off_apps", JSONArray(offApps.toList()))

    companion object {
        /** Shelves the wizard offers (the Apps shelf always shows). */
        val OPTIONAL_SHELVES = listOf(ShelfType.WATCH_NEXT, ShelfType.SUGGESTED, ShelfType.RECENTS, ShelfType.LIBRARY)

        fun fromJson(json: JSONObject): SetupChoices? = runCatching {
            fun strings(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
            SetupChoices(
                mode = SetupMode.valueOf(json.getString("mode")),
                theme = AppTheme.valueOf(json.getString("theme")),
                font = FontChoice.valueOf(json.getString("font")),
                layout = CoverLayout.valueOf(json.getString("layout")),
                tileSize = TileSize.valueOf(json.getString("tile_size")),
                shelves = json.getJSONObject("shelves").let { o -> o.keys().asSequence().associate { ShelfType.valueOf(it) to o.getBoolean(it) } },
                saver = ScreensaverMode.valueOf(json.getString("saver")),
                groups = strings("groups").map(CleanGroup::valueOf).toSet(),
                offApps = strings("off_apps").toSet(),
                animation = json.getDouble("animation").toFloat(),
                dns = DnsChoice(json.getString("dns_mode"), json.optString("dns_host")),
            )
        }.getOrNull()
    }
}

/** Reads the TV as it is now, and keeps the wizard's place across the one restart granting can cause. */
object SetupState {
    private const val PREFS = "bucklerhome"
    private const val KEY_RESUME = "setup_resume"

    /** A SHIELD: the clean-up and SHIELD steps apply. Other TVs get the Home settings only. */
    fun isShield(): Boolean = Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true)

    /** The clean-up apps currently turned off for this user. */
    fun disabledApps(context: Context, data: ShieldData = ShieldData.load(context)): Set<String> {
        val pm = context.packageManager
        return CleanGroup.entries.flatMap { it.packages(data) }.filter { pkg ->
            val state = runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull()
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.toSet()
    }

    /** Apps of [CleanGroup]s present on this TV (a missing app counts as off). */
    fun presentApps(context: Context, packages: List<String>): List<String> {
        val pm = context.packageManager
        return packages.filter { runCatching { pm.getApplicationInfo(it, PackageManager.MATCH_DISABLED_COMPONENTS) }.isSuccess }
    }

    fun current(context: Context, firstRun: Boolean): SetupChoices {
        val data = ShieldData.load(context)
        AppearanceStore.ensureLoaded(context)
        val appearance = AppearanceStore.config
        val layout = HomeLayout.load(context)
        val off = if (isShield()) disabledApps(context, data) else emptySet()
        // First run: the suggested clean-up. Later runs: a group is on when all its apps here are off.
        val groups = if (firstRun) {
            CleanGroup.entries.filter { it.onByDefault }.toSet()
        } else {
            CleanGroup.entries.filter { g -> presentApps(context, g.packages(data)).let { it.isNotEmpty() && it.all(off::contains) } }.toSet()
        }
        val offApps = if (firstRun) off + groups.flatMap { it.packages(data) } else off
        val resolver = context.contentResolver
        return SetupChoices(
            theme = appearance.theme,
            font = appearance.font,
            layout = layout.layout,
            tileSize = layout.tileSize,
            shelves = SetupChoices.OPTIONAL_SHELVES.associateWith { type -> layout.shelves.any { it.type == type && it.enabled } },
            saver = ScreensaverPrefs.load(context).mode,
            groups = groups,
            offApps = offApps,
            animation = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
            dns = DnsChoice(
                Settings.Global.getString(resolver, "private_dns_mode") ?: "opportunistic",
                Settings.Global.getString(resolver, "private_dns_specifier").orEmpty(),
            ),
        )
    }

    /** Saved choices to pick up after Buckler restarts mid-setup, or null. */
    fun resume(context: Context): SetupChoices? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESUME, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(SetupChoices::fromJson)

    fun saveForResume(context: Context, choices: SetupChoices) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RESUME, choices.toJson().toString()).apply()
    }

    fun clearResume(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RESUME).apply()
    }
}
