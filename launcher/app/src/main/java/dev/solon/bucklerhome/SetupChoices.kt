package dev.solon.bucklerhome

import android.annotation.SuppressLint
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
    /** Buckler's screensaver is the TV's screensaver (and Google's Backdrop is off). */
    val systemSaver: Boolean = false,
    /** Now Playing may read the TV's media notifications. */
    val mediaAccess: Boolean = false,
    /** Catalog swaps and suggestions chosen ([CatalogApp.id]); installed ones stay chosen. */
    val apps: Set<String> = emptySet(),
    /** The icon pack's package, or blank for apps' own icons. */
    val iconPack: String = "",
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
        .put("system_saver", systemSaver).put("media_access", mediaAccess)
        .put("apps", JSONArray(apps.toList())).put("icon_pack", iconPack)

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
                systemSaver = json.optBoolean("system_saver"),
                mediaAccess = json.optBoolean("media_access"),
                apps = json.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                iconPack = json.optString("icon_pack"),
            )
        }.getOrNull()
    }
}

/** Reads the TV as it is now, and keeps the wizard's place across the one restart granting can cause. */
object SetupState {
    private const val PREFS = "bucklerhome"
    private const val KEY_RESUME = "setup_resume"
    private const val KEY_INTENDED_OFF = "setup_off_apps"
    private const val KEY_COMPILED = "setup_compiled_version"
    private const val KEY_FINGERPRINT = "setup_checked_fingerprint"
    const val BACKDROP = "com.google.android.backdrop"
    private const val DREAM = ".ClockDream"

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

    /**
     * Apps of [CleanGroup]s present on this TV (a missing app counts as off), less any that
     * [keptOn] needs: those are no longer the clean-up's to turn off.
     */
    fun presentApps(context: Context, packages: List<String>, kept: Set<String> = keptOn(context)): List<String> {
        val pm = context.packageManager
        return packages.filter { it !in kept && runCatching { pm.getApplicationInfo(it, PackageManager.MATCH_DISABLED_COMPONENTS) }.isSuccess }
    }

    /** Clean-up apps a chosen widget needs: Google's calendar sync while a stage shows the calendar. */
    fun keptOn(context: Context): Set<String> {
        val stage = HomeLayout.stored(context).mainStage
        val calendar = stage == MainStage.GLANCE || stage == MainStage.CALENDAR || StagePrefs.sideIdle(context) == SideIdle.GLANCE
        return if (calendar) setOf(GOOGLE_CALENDAR_SYNC) else emptySet()
    }

    /**
     * The wizard's starting choices. [intended] adds back clean-up apps meant to be off that an
     * update turned on again, so Review offers to turn them off; without it this is the TV as it
     * is, which Review compares against.
     */
    fun current(context: Context, firstRun: Boolean, intended: Boolean = true): SetupChoices {
        val data = ShieldData.load(context)
        AppearanceStore.ensureLoaded(context)
        val appearance = AppearanceStore.config
        val layout = HomeLayout.load(context)
        val shield = isShield()
        val disabled = if (shield) disabledApps(context, data) else emptySet()
        val off = when {
            !shield -> emptySet()
            firstRun -> disabled + CleanGroup.entries.filter { it.onByDefault }.flatMap { it.packages(data) }
            intended -> disabled + cameBack(context, data)
            else -> disabled
        }
        // A group is on when every one of its apps here is off.
        val kept = keptOn(context)
        val groups = CleanGroup.entries.filter { g -> presentApps(context, g.packages(data), kept).let { it.isNotEmpty() && it.all(off::contains) } }
        val resolver = context.contentResolver
        val pins = layout.appsPackages()
        val apps = data.apps.filter { app ->
            when {
                app.group == CatalogApp.ICON_PACK -> false
                app.installable -> SetupApps.installed(context, app.packageName)
                else -> app.dock == "unpin-only" && app.stockPackage !in pins
            }
        }.map { it.id }.toSet()
        return SetupChoices(
            theme = appearance.theme,
            font = appearance.font,
            layout = layout.layout,
            tileSize = layout.tileSize,
            shelves = SetupChoices.OPTIONAL_SHELVES.associateWith { type -> layout.shelves.any { it.type == type && it.enabled } },
            saver = ScreensaverPrefs.load(context).mode,
            groups = if (firstRun && shield) CleanGroup.entries.filter { it.onByDefault }.toSet() else groups.toSet(),
            offApps = off,
            animation = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
            dns = DnsChoice(
                Settings.Global.getString(resolver, "private_dns_mode") ?: "opportunistic",
                Settings.Global.getString(resolver, "private_dns_specifier").orEmpty(),
            ),
            systemSaver = (firstRun && shield) || isSystemSaver(context),
            mediaAccess = (firstRun && shield) || hasMediaAccess(context),
            apps = apps,
            iconPack = IconPackStore.pack(context).orEmpty(),
        )
    }

    /** Buckler's dream is the TV's screensaver. */
    fun isSystemSaver(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, "screensaver_components").orEmpty().startsWith(context.packageName + "/")

    fun dreamComponent(context: Context): String = context.packageName + "/" + DREAM

    fun backdropOn(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationEnabledSetting(BACKDROP).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } && SetupApps.installed(context, BACKDROP)
    }.getOrDefault(false)

    fun hasMediaAccess(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Clean-up apps meant to be off: what the wizard last applied or, before it ever has, the ones
     * off when this was first asked (so an update that turns them back on is noticed either way).
     */
    fun intendedOff(context: Context, data: ShieldData = ShieldData.load(context)): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_INTENDED_OFF, null)?.let { return it.toSet() }
        return disabledApps(context, data).also { setIntendedOff(context, it) }
    }

    fun setIntendedOff(context: Context, packages: Set<String>) {
        val kept = keptOn(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_INTENDED_OFF, packages - kept).apply()
    }

    /** Clean-up apps meant to be off that are on again, usually after a system update. */
    fun cameBack(context: Context, data: ShieldData = ShieldData.load(context)): List<String> {
        if (!isShield()) return emptyList()
        val off = disabledApps(context, data)
        return presentApps(context, intendedOff(context, data).toList()).filter { it !in off }
    }

    /**
     * Apps that came back on since the last check, once per system build: empty unless the build
     * fingerprint changed (an update), so Home mentions it once and not on every start.
     */
    fun cameBackAfterUpdate(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(KEY_FINGERPRINT, null)
        prefs.edit().putString(KEY_FINGERPRINT, Build.FINGERPRINT).apply()
        return if (seen == null || seen == Build.FINGERPRINT) emptyList() else cameBack(context)
    }

    /** Buckler was compiled ahead of time for this version. */
    fun compiled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COMPILED, 0) == BuildConfig.VERSION_CODE

    fun setCompiled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_COMPILED, BuildConfig.VERSION_CODE).apply()
    }

    /** Saved choices to pick up after Buckler restarts mid-setup, or null. */
    fun resume(context: Context): SetupChoices? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESUME, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(SetupChoices::fromJson)

    @SuppressLint("ApplySharedPref")
    fun saveForResume(context: Context, choices: SetupChoices) {
        // commit(): the grant that follows can kill Buckler before an apply() reaches the disk.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RESUME, choices.toJson().toString()).commit()
    }

    fun clearResume(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RESUME).apply()
    }
}
