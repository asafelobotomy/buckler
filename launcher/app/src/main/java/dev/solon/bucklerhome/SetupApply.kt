package dev.solon.bucklerhome

import android.content.Context
import android.provider.Settings

/** One line of the wizard's review. [needsTv]: made over Network debugging, not by Buckler alone. */
data class SetupChange(val text: String, val needsTv: Boolean)

/**
 * Turns the wizard's choices into changes: first a review of only what differs from how the TV
 * is now ([changes]), then the changes themselves. Buckler's own settings apply at once
 * ([applyLocal]); the SHIELD ones go over [AdbSelf] ([applyTv]) in a safe order: grants, Home,
 * apps off and on, then the system settings Buckler's grant lets it write.
 */
object SetupApply {
    fun changes(context: Context, from: SetupChoices, to: SetupChoices): List<SetupChange> {
        val out = mutableListOf<SetupChange>()
        fun local(text: String) = out.add(SetupChange(text, needsTv = false))
        fun tv(text: String) = out.add(SetupChange(text, needsTv = true))
        if (to.theme != from.theme) local("Colour scheme: ${to.theme.label}")
        if (to.font != from.font) local("Font: ${to.font.label}")
        if (to.layout != from.layout || to.tileSize != from.tileSize) local("Apps as ${to.layout.name.lowercase()}s, ${to.tileSize.label}")
        to.shelves.filter { (type, on) -> from.shelves[type] != on }.forEach { (type, on) ->
            local("${shelfName(type)} shelf: ${if (on) "shown" else "hidden"}")
        }
        if (to.saver != from.saver) local("Screensaver: ${to.saver.label}")
        val data = ShieldData.load(context)
        val dock = dockChange(context, to, data)
        if (dock != null) local(dock)
        val pack = data.apps.firstOrNull { it.packageName == to.iconPack }
        if (to.iconPack != from.iconPack && (to.iconPack.isBlank() || SetupApps.installed(context, to.iconPack))) {
            local("App icons: ${pack?.label ?: if (to.iconPack.isBlank()) "apps' own" else to.iconPack}")
        }
        if (!SetupState.isShield()) return out

        val grantsMissing = ShieldOps.missingPermissions(context).isNotEmpty() || ShieldOps.missingAppops(context).isNotEmpty()
        if (grantsMissing) tv("Give Buckler its permissions (system settings, installs, app usage)")
        if (HomeStatus.isHome(context).not()) tv("Make Buckler the Home screen (the stock one stays installed)")
        val (off, on) = appChanges(context, to)
        if (off.isNotEmpty()) tv("Turn off ${off.size} apps: ${labels(context, off)}")
        if (on.isNotEmpty()) tv("Turn back on ${on.size} apps: ${labels(context, on)}")
        // System settings need Buckler's grant; without it they go over the connection too.
        if (to.animation != from.animation) out += SetupChange("Animation speed: ${animationLabel(to.animation)}", grantsMissing)
        if (to.dns != from.dns) out += SetupChange("Private DNS: ${dnsLabel(to.dns)}", grantsMissing)
        if (to.systemSaver != from.systemSaver) {
            tv(if (to.systemSaver) "Use Buckler's screensaver; turn off Google's Backdrop" else "Use Google's Backdrop screensaver again")
        } else if (to.systemSaver && SetupState.backdropOn(context)) {
            tv("Turn off Google's Backdrop (Buckler's screensaver is in use)")
        }
        if (to.mediaAccess != from.mediaAccess) {
            tv(if (to.mediaAccess) "Let Now Playing show what's playing" else "Stop Now Playing reading what's playing")
        }
        toInstall(context, to, data).forEach { tv("Install ${it.label} ${it.version} (download checked against its pinned checksum)") }
        if (pack != null && !SetupApps.installed(context, pack.packageName)) {
            tv("Install ${pack.label} ${pack.version} and use it for app icons (checked download)")
        }
        if (out.any { it.needsTv } && !SetupState.compiled(context)) tv("Speed up Buckler's start (compile it ahead of time)")
        return out
    }

    /** Chosen catalog apps (and the chosen icon pack) not yet on the TV. */
    fun toInstall(context: Context, to: SetupChoices, data: ShieldData = ShieldData.load(context)): List<CatalogApp> =
        data.apps.filter { it.installable && it.id in to.apps && !SetupApps.installed(context, it.packageName) }

    /** How the Apps row's pins change for [to]'s swaps and suggestions, or null. */
    private fun dockChange(context: Context, to: SetupChoices, data: ShieldData): String? {
        val pins = HomeLayout.load(context).appsPackages()
        val next = SetupApps.applyDock(pins, data.apps.filter { it.id in to.apps && it.group != CatalogApp.ICON_PACK })
        if (next == pins) return null
        val added = next - pins.toSet()
        val removed = pins - next.toSet()
        // Apps not installed yet have no label on the TV: use the catalog's.
        fun names(packages: List<String>): String {
            val known = data.apps.associate { it.packageName to it.label }
            return packages.joinToString(", ") { known[it] ?: labels(context, listOf(it)) }
        }
        return listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "pin ${names(it)}" },
            removed.takeIf { it.isNotEmpty() }?.let { "unpin ${names(it)}" },
        ).joinToString("; ").replaceFirstChar { it.uppercase() } + " in the Apps row"
    }

    /**
     * Pins for chosen apps and the icon pack, for what is installed now; [applyTv] runs it again
     * after installing. Unpinning Google TV needs no install.
     */
    fun applyApps(context: Context, to: SetupChoices) {
        val data = ShieldData.load(context)
        val chosen = data.apps.filter {
            it.id in to.apps && it.group != CatalogApp.ICON_PACK && (!it.installable || SetupApps.installed(context, it.packageName))
        }
        val layout = HomeLayout.load(context)
        val pins = SetupApps.applyDock(layout.appsPackages(), chosen)
        if (pins != layout.appsPackages()) {
            HomeLayout.save(context, layout.withAppsPackages(pins))
        }
        if (to.iconPack.isBlank() || SetupApps.installed(context, to.iconPack)) {
            IconPackStore.setPack(context, to.iconPack.ifBlank { null })
        }
    }

    /** Apps to turn off and back on so the TV matches [to] (clean-up apps present here only). */
    fun appChanges(context: Context, to: SetupChoices): Pair<List<String>, List<String>> {
        val data = ShieldData.load(context)
        val all = SetupState.presentApps(context, CleanGroup.entries.flatMap { it.packages(data) }.distinct())
        val off = SetupState.disabledApps(context, data)
        return all.filter { it in to.offApps && it !in off } to all.filter { it in off && it !in to.offApps }
    }

    /** Buckler's own settings: look, shelves, screensaver choice. Takes effect at once. */
    fun applyLocal(context: Context, to: SetupChoices) {
        AppearanceStore.update(context) { it.copy(theme = to.theme, font = to.font) }
        val layout = HomeLayout.load(context)
        HomeLayout.save(
            context,
            layout.copy(
                layout = to.layout,
                tileSize = to.tileSize,
                // Only the on/off of each shelf changes: order, pins and titles stay.
                shelves = layout.shelves.map { s -> to.shelves[s.type]?.let { s.copy(enabled = it) } ?: s },
            ),
        )
        ScreensaverPrefs.setMode(context, to.saver)
        applyApps(context, to)
    }

    /**
     * The SHIELD changes over [adb]. If Buckler's install permission is missing, granting it
     * restarts Buckler: the choices are saved first and a detached task reopens the wizard after,
     * so the user lands back on its review instead of in the last app. Returns lines for the result.
     */
    fun applyTv(context: Context, adb: AdbSelf, to: SetupChoices, step: (String) -> Unit): List<String> {
        val ops = ShieldOps(context, adb)
        val done = mutableListOf<String>()
        if (ShieldOps.missingPermissions(context).isNotEmpty() || ShieldOps.missingAppops(context).isNotEmpty()) {
            step("Giving Buckler its permissions…")
            if (ShieldOps.restartsOnGrant(context)) {
                SetupState.saveForResume(context, to)
                val relaunch = adb.shell(
                    "nohup sh -c 'sleep 2; am start -n ${context.packageName}/.MainActivity --ez ${MainActivity.EXTRA_SETUP} true' " +
                        ">/dev/null 2>&1 &",
                )
                if (!relaunch.ok) done += "Buckler may not reopen by itself after its restart: open it from Home."
            }
            val failed = ops.grantBuckler()
            done += if (failed.isEmpty()) "Permissions given." else "Permissions not given: ${failed.joinToString()}"
        }
        if (!HomeStatus.isHome(context)) {
            step("Making Buckler Home…")
            done += if (ops.setHome()) "Buckler is Home." else "Home could not be changed."
        }
        val (off, on) = appChanges(context, to)
        if (off.isNotEmpty()) {
            step("Turning off ${off.size} apps…")
            val r = ops.disable(off, "setup wizard")
            done += "Turned off ${r.changed.size} apps" + if (r.failed.isEmpty()) "." else "; ${r.failed.size} could not be."
        }
        if (on.isNotEmpty()) {
            step("Turning ${on.size} apps back on…")
            done += "Turned back on ${ops.enable(on).size} apps."
        }
        if (off.isNotEmpty() || on.isNotEmpty()) SetupState.setIntendedOff(context, to.offApps)
        done += applyScreensaver(context, adb, ops, to, step)
        if (to.mediaAccess != SetupState.hasMediaAccess(context)) {
            val verb = if (to.mediaAccess) "allow_listener" else "disallow_listener"
            val ok = adb.shell("cmd notification $verb ${context.packageName}/${NowPlayingListener::class.java.name}").ok
            done += if (!ok) "Now Playing access could not be changed." else if (to.mediaAccess) "Now Playing can show what's playing." else "Now Playing access removed."
        }
        val data = ShieldData.load(context)
        val pack = data.apps.firstOrNull { it.packageName == to.iconPack && it.installable }
        (toInstall(context, to, data) + listOfNotNull(pack?.takeIf { !SetupApps.installed(context, it.packageName) }))
            .forEach { done += SetupApps.install(context, adb, it, step) }
        applyApps(context, to)
        done += applySystem(context, to)
        if (!SetupState.compiled(context)) {
            // Last: compiling Buckler itself may restart it once it is done.
            step("Speeding up Buckler's start…")
            if (adb.shell("cmd package compile -m speed -f ${context.packageName}").ok) {
                SetupState.setCompiled(context)
                done += "Buckler compiled ahead of time."
            }
        }
        return done
    }

    /** Buckler's dream as the TV's screensaver with Backdrop off, or Backdrop back. */
    private fun applyScreensaver(context: Context, adb: AdbSelf, ops: ShieldOps, to: SetupChoices, step: (String) -> Unit): List<String> {
        val resolver = context.contentResolver
        if (to.systemSaver) {
            val done = mutableListOf<String>()
            if (!SetupState.isSystemSaver(context)) {
                step("Setting Buckler's screensaver…")
                val dream = SetupState.dreamComponent(context)
                val ok = runCatching {
                    check(Settings.Secure.putString(resolver, "screensaver_components", dream))
                    check(Settings.Secure.putString(resolver, "screensaver_default_component", dream))
                    check(Settings.Secure.putInt(resolver, "screensaver_enabled", 1))
                }.isSuccess || adb.shell(
                    "settings put secure screensaver_components $dream && " +
                        "settings put secure screensaver_default_component $dream && " +
                        "settings put secure screensaver_enabled 1",
                ).ok
                done += if (ok) "Buckler's screensaver is the TV's screensaver." else "The screensaver could not be set."
            }
            if (SetupState.backdropOn(context)) {
                val r = ops.disable(listOf(SetupState.BACKDROP), "replaced by Buckler's screensaver")
                if (r.changed.isNotEmpty()) done += "Backdrop turned off."
            }
            return done
        }
        if (!SetupState.isSystemSaver(context)) return emptyList()
        step("Bringing back Backdrop…")
        adb.shell("pm enable ${SetupState.BACKDROP}")
        ShieldJournal.remove(context, SetupState.BACKDROP)
        val backdrop = context.packageManager.queryIntentServices(
            android.content.Intent("android.service.dreams.DreamService").setPackage(SetupState.BACKDROP), 0,
        ).firstOrNull()?.serviceInfo?.let { "${it.packageName}/${it.name}" }
        if (backdrop != null) {
            runCatching {
                Settings.Secure.putString(resolver, "screensaver_components", backdrop)
                Settings.Secure.putString(resolver, "screensaver_default_component", backdrop)
            }
        }
        return listOf(if (backdrop != null) "Backdrop is the screensaver again." else "Backdrop turned back on.")
    }

    /** Animation speed and Private DNS, written with Buckler's own grant, each only when it changes. */
    fun applySystem(context: Context, to: SetupChoices): List<String> {
        val resolver = context.contentResolver
        val done = mutableListOf<String>()
        val scales = listOf(
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        )
        if (scales.any { Settings.Global.getFloat(resolver, it, 1f) != to.animation }) {
            runCatching { scales.forEach { Settings.Global.putFloat(resolver, it, to.animation) } }
            if (Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == to.animation) {
                done += "Animations: ${animationLabel(to.animation)}."
            }
        }
        val now = DnsChoice(
            Settings.Global.getString(resolver, "private_dns_mode") ?: "opportunistic",
            Settings.Global.getString(resolver, "private_dns_specifier").orEmpty(),
        )
        val sameDns = now.mode == to.dns.mode && (to.dns.mode != "hostname" || now.host == to.dns.host)
        if (!sameDns) {
            val dns = runCatching {
                Settings.Global.putString(resolver, "private_dns_mode", to.dns.mode)
                if (to.dns.mode == "hostname") Settings.Global.putString(resolver, "private_dns_specifier", to.dns.host)
            }
            if (dns.isSuccess) done += "Private DNS: ${dnsLabel(to.dns)}."
        }
        return done
    }

    fun animationLabel(scale: Float): String = when {
        scale <= 0f -> "off"
        scale < 1f -> "fast (${scale}×)"
        else -> "normal"
    }

    fun dnsLabel(dns: DnsChoice): String = when (dns.mode) {
        "off" -> "off"
        "hostname" -> dns.host.ifBlank { "a host" }
        else -> "automatic"
    }

    fun shelfName(type: ShelfType): String = when (type) {
        ShelfType.WATCH_NEXT -> "Up Next"
        ShelfType.SUGGESTED -> "Suggested"
        ShelfType.RECENTS -> "Recent"
        ShelfType.LIBRARY -> "Library"
        ShelfType.APPS -> "Apps"
    }

    /** "Assistant, Smart Connect, … and 4 more", by app name where the TV knows it. */
    fun labels(context: Context, packages: List<String>): String {
        val pm = context.packageManager
        val names = packages.map { pkg ->
            runCatching { pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm).toString() }
                .getOrDefault(pkg.substringAfterLast('.'))
        }
        return if (names.size <= 4) names.joinToString(", ") else names.take(3).joinToString(", ") + " and ${names.size - 3} more"
    }
}

/** Whether the Home button opens Buckler. */
object HomeStatus {
    fun isHome(context: Context): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val home = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return home?.activityInfo?.packageName == context.packageName
    }
}
