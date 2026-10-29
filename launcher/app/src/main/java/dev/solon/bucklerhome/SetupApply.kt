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
        return out
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
                adb.shell(
                    "nohup sh -c 'sleep 2; am start -n ${context.packageName}/.MainActivity --ez ${MainActivity.EXTRA_SETUP} true' " +
                        ">/dev/null 2>&1 &",
                )
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
        done += applySystem(context, to)
        return done
    }

    /** Animation speed and Private DNS, written with Buckler's own grant. */
    fun applySystem(context: Context, to: SetupChoices): List<String> {
        val resolver = context.contentResolver
        val done = mutableListOf<String>()
        val scale = runCatching {
            listOf(
                Settings.Global.WINDOW_ANIMATION_SCALE,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            ).forEach { Settings.Global.putFloat(resolver, it, to.animation) }
        }
        if (scale.isSuccess && Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == to.animation) {
            done += "Animations: ${animationLabel(to.animation)}."
        }
        val dns = runCatching {
            Settings.Global.putString(resolver, "private_dns_mode", to.dns.mode)
            if (to.dns.mode == "hostname") Settings.Global.putString(resolver, "private_dns_specifier", to.dns.host)
        }
        if (dns.isSuccess) done += "Private DNS: ${dnsLabel(to.dns)}."
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
