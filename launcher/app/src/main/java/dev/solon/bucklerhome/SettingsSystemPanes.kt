package dev.solon.bucklerhome

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.service.autofill.AutofillService
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.TimeZone

/**
 * Settings → Network: Private DNS for the whole TV (the setup wizard's choices), then Android's own
 * screen for Wi-Fi, Ethernet and proxy.
 */
@Composable
fun NetworkPane() {
    val context = LocalContext.current
    var dns by remember { mutableStateOf(currentDns(context)) }
    SectionTitle("Network")
    GrantNote()
    val current = DNS.firstOrNull { it.first == dns } ?: (dns to SetupApply.dnsLabel(dns))
    SettingsChoice(
        "Private DNS",
        if (current in DNS) DNS else DNS + current,
        current,
        { it.second },
        note = "Encrypted DNS for the whole TV. Automatic uses it when the network's own DNS offers it.",
    ) { (picked, _) ->
        if (setDns(context, picked)) dns = picked else SystemSettings.explainGrant(context)
    }
    Spacer(modifier = Modifier.height(12.dp))
    SystemScreenLink("Wi-Fi, Ethernet and proxy", SystemScreen.NETWORK)
}

private fun currentDns(context: Context): DnsChoice {
    val mode = SystemSettings.get(context, SystemKeys.DNS_MODE)
    return DnsChoice(mode, if (mode == "hostname") SystemSettings.get(context, SystemKeys.DNS_HOST) else "")
}

/** The host first: Android looks it up again when the mode changes. */
private fun setDns(context: Context, dns: DnsChoice): Boolean =
    (dns.mode != "hostname" || SystemSettings.put(context, SystemKeys.DNS_HOST, dns.host)) &&
        SystemSettings.put(context, SystemKeys.DNS_MODE, dns.mode)

/** Settings → Display & sound: system sounds, surround formats and HDMI-CEC, then Android's own screens. */
@Composable
fun DisplaySoundPane() {
    val context = LocalContext.current
    SectionTitle("Display & sound")
    GrantNote()
    PickerTitle("Sound")
    SystemToggle("System sounds", SystemKeys.SOUND_EFFECTS) { on ->
        // As Android's own switch does: the clicks are loaded only while they are on.
        context.getSystemService(AudioManager::class.java)?.run { if (on) loadSoundEffects() else unloadSoundEffects() }
    }
    SystemChoice(
        "Surround sound",
        SystemKeys.SURROUND,
        listOf("0" to "Automatic", "2" to "Always", "1" to "Never"),
        note = "Automatic sends the formats the TV or receiver says it plays. Android's settings can pick them one by one.",
        other = { "Manual" },
    )
    Spacer(modifier = Modifier.height(18.dp))
    PickerTitle("HDMI-CEC")
    SettingsRow("How SHIELD and the TV control each other over HDMI.")
    SystemToggle("HDMI-CEC", SystemKeys.HDMI_CONTROL)
    SystemToggle("Turn the TV on with SHIELD", SystemKeys.HDMI_TV_ON)
    SystemToggle("Turn the TV off with SHIELD", SystemKeys.HDMI_TV_OFF)
    SystemToggle("Volume over HDMI-CEC", SystemKeys.HDMI_VOLUME)
    Spacer(modifier = Modifier.height(12.dp))
    SystemScreenLink("Resolution, HDR and more", SystemScreen.DISPLAY_SOUND)
    SystemScreenLink("Power", SystemScreen.POWER)
}

/** Buckler's 12/24-hour clock, which the whole TV follows ([DisplayPrefs.setUse24h]). */
@Composable
fun ClockChoice() {
    val context = LocalContext.current
    var use24h by remember { mutableStateOf(DisplayPrefs.load(context).use24h) }
    SettingsChoice("Clock", listOf(false, true), use24h, { if (it) "24-hour" else "12-hour" }) {
        DisplayPrefs.setUse24h(context, it)
        use24h = it
    }
}

/** Device → Global's date and time: the network's, or set in Android's own screen. */
@Composable
fun DateTimeSection() {
    PickerTitle("Date & time")
    SystemToggle("Automatic date & time", SystemKeys.AUTO_TIME)
    SystemToggle("Automatic time zone", SystemKeys.AUTO_TIME_ZONE)
    TimeZoneLink()
    ClockChoice()
    SystemScreenLink("Date & time in Android", SystemScreen.DATE_TIME)
}

/** Device → Global's keyboard: which one types, which are on, and the autofill service. */
@Composable
fun KeyboardSection() {
    val context = LocalContext.current
    val keyboards = remember { context.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty() }
    val labels = remember { keyboards.associate { it.id to it.loadLabel(context.packageManager).toString() } }
    var tick by remember { mutableIntStateOf(0) }
    val on = remember(tick) { keyboardsOn(context) }
    val current = remember(tick) { SystemSettings.get(context, SystemKeys.KEYBOARD) }
    PickerTitle("Keyboard")
    val choices = keyboards.map { it.id }.filter { it in on }
    SettingsChoice("Keyboard", if (current in choices) choices else choices + current, current, { labels[it] ?: it }) {
        if (SystemSettings.put(context, SystemKeys.KEYBOARD, it)) tick++ else SystemSettings.explainGrant(context)
    }
    SettingsLink("Keyboards", value = "${on.size} on") { KeyboardsList(labels) }
    AutofillChoice()
    SystemScreenLink("Keyboard settings", SystemScreen.KEYBOARD)
}

/** The keyboards Android lists as on, by id ([SystemKeys.KEYBOARDS] without their subtypes). */
private fun keyboardsOn(context: Context): List<String> =
    SystemSettings.get(context, SystemKeys.KEYBOARDS).split(':').filter { it.isNotBlank() }.map { it.substringBefore(';') }

/** Every installed keyboard, ticked when on; the one typing stays on. */
@Composable
private fun KeyboardsList(labels: Map<String, String>) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val entries = remember(tick) { SystemSettings.get(context, SystemKeys.KEYBOARDS).split(':').filter { it.isNotBlank() } }
    val current = remember(tick) { SystemSettings.get(context, SystemKeys.KEYBOARD) }
    labels.forEach { (id, label) ->
        val on = entries.any { it.substringBefore(';') == id }
        SettingsCheck(label, on) {
            if (on && id == current) {
                Toast.makeText(context, "It is the keyboard in use: choose another first", Toast.LENGTH_SHORT).show()
                return@SettingsCheck
            }
            // An entry keeps its subtypes ("id;subtype"), so turning another one on or off leaves them be.
            val next = if (on) entries.filter { it.substringBefore(';') != id } else entries + id
            if (SystemSettings.put(context, SystemKeys.KEYBOARDS, next.joinToString(":"))) tick++ else SystemSettings.explainGrant(context)
        }
    }
}

/** The autofill service (passwords and addresses in apps' forms), or none. */
@Composable
private fun AutofillChoice() {
    val context = LocalContext.current
    val services = remember {
        val pm = context.packageManager
        pm.queryIntentServices(Intent(AutofillService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .filter { it.serviceInfo.permission == Manifest.permission.BIND_AUTOFILL_SERVICE }
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToShortString() to it.loadLabel(pm).toString() }
    }
    // A service Android still names after its app was turned off or removed does nothing.
    SystemChoice("Autofill", SystemKeys.AUTOFILL, listOf("" to "None") + services, other = { "None (${it.substringBefore('/')} is gone)" })
}

/** Accessibility → Whole TV's captions: on or off, size and style; language and the rest in Android's screen. */
@Composable
fun CaptionsSection() {
    PickerTitle("Captions")
    SystemToggle("Captions", SystemKeys.CAPTIONS)
    SystemChoice(
        "Caption size",
        SystemKeys.CAPTION_SCALE,
        listOf("0.5" to "Small", "1.0" to "Normal", "1.5" to "Large", "2.0" to "Very large"),
        other = { "${it}×" },
    )
    SystemChoice(
        "Caption style",
        SystemKeys.CAPTION_PRESET,
        listOf("0" to "White on black", "1" to "Black on white", "2" to "Yellow on black", "3" to "Yellow on blue"),
        other = { "Custom" },
    )
    SystemScreenLink("Language and more", SystemScreen.CAPTIONS)
}

/** Choices for [SystemKeys.SLEEP_TIMEOUT], as SHIELD's Energy saver offers them. */
val SleepChoices = listOf(
    "900000" to "15 minutes",
    "1800000" to "30 minutes",
    "3600000" to "1 hour",
    "14400000" to "4 hours",
    "28800000" to "8 hours",
    "86400000" to "24 hours",
    "-1" to "Never",
)

/** Device → Global's power rows, over Network debugging: sleep now, and restart (pressed twice). */
@Composable
fun PowerSection(runner: ShieldRunner) {
    var armed by remember { mutableStateOf(false) }
    PickerTitle("Power")
    SettingsAction("Sleep") { runner.run("Going to sleep") { ops -> if (ops.sleep()) "Asleep." else "Could not sleep." } }
    SettingsAction(if (armed) "Press again to restart" else "Restart") {
        if (!armed) {
            armed = true
            return@SettingsAction
        }
        armed = false
        runner.run("Restarting") { ops ->
            ops.restart()
            "Restarting…"
        }
    }
    if (runner.used) SettingsRow(runner.status)
}

/** Time zone regions, in Android's own IDs (Europe/Berlin); Etc and the old aliases are left out. */
private val ZoneRegions = listOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

private fun zoneLabel(id: String) = id.substringAfter('/').replace('_', ' ')

/** Device → Global's time zone row: the zone now, opening its region list. */
@Composable
fun TimeZoneLink() {
    val zone = remember { TimeZone.getDefault().id }
    // Set through an Android 11 call; elsewhere Android's own screen does it.
    if (android.os.Build.VERSION.SDK_INT != android.os.Build.VERSION_CODES.R) return
    SettingsLink("Time zone", value = zoneLabel(zone)) { TimeZoneRegions() }
}

@Composable
private fun TimeZoneRegions() {
    val context = LocalContext.current
    val current = remember { TimeZone.getDefault().id }
    val zones = remember {
        TimeZone.getAvailableIDs().filter { '/' in it && it.substringBefore('/') in ZoneRegions }.sorted().groupBy { it.substringBefore('/') }
    }
    if (SystemSettings.get(context, SystemKeys.AUTO_TIME_ZONE) == "1") {
        SettingsRow("Automatic time zone is on. Choosing one here turns it off.")
    }
    SettingsRow("Set over Network debugging.")
    zones.forEach { (region, ids) ->
        SettingsLink(region, value = zoneLabel(current).takeIf { current in ids }) { TimeZoneList(ids, current) }
    }
}

/** One region's zones; choosing one sets it and comes back to the regions. */
@Composable
private fun TimeZoneList(ids: List<String>, current: String) {
    val context = LocalContext.current
    val runner = rememberShieldRunner()
    val nav = LocalPanelNav.current
    if (runner.used) SettingsRow(runner.status)
    ChoiceList(ids, current.takeIf { it in ids }, ::zoneLabel, note = null) { zone ->
        // A zone chosen by hand is ignored while the network's is in use.
        SystemSettings.put(context, SystemKeys.AUTO_TIME_ZONE, "0")
        runner.run("Setting the time zone") { ops ->
            if (ops.setTimeZone(zone)) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { nav?.back() }
                "Time zone: ${zoneLabel(zone)}."
            } else {
                "Could not set the time zone."
            }
        }
    }
}
