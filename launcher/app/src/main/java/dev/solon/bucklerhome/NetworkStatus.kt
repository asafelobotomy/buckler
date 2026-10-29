package dev.solon.bucklerhome

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LinkType { NONE, WIFI, ETHERNET, OTHER }

data class NetState(
    val link: LinkType = LinkType.NONE,
    val vpn: Boolean = false,
    /** ISO-style exit country while [vpn] is up, e.g. IE, UK, US. Null until the lookup returns. */
    val country: String? = null,
)

/** Reduced view of one Android network so [NetState.from] stays unit-testable. */
data class NetInfo(
    val wifi: Boolean = false,
    val ethernet: Boolean = false,
    val vpn: Boolean = false,
    val internet: Boolean = true,
)

fun netStateFrom(networks: List<NetInfo>): NetState {
    val physical = networks.filter { it.internet && !it.vpn }
    val link = when {
        physical.any { it.ethernet } -> LinkType.ETHERNET
        physical.any { it.wifi } -> LinkType.WIFI
        physical.isNotEmpty() -> LinkType.OTHER
        else -> LinkType.NONE
    }
    return NetState(link = link, vpn = networks.any { it.vpn })
}

/** Networks reported by [ConnectivityManager.NetworkCallback], which replaces [ConnectivityManager.getAllNetworks]. */
private class LiveNets {
    private val caps = LinkedHashMap<Network, NetworkCapabilities>()

    fun update(network: Network, capabilities: NetworkCapabilities) {
        caps[network] = capabilities
    }

    fun remove(network: Network) {
        caps.remove(network)
    }

    fun snapshot(): Pair<NetState, Network?> {
        var vpnNetwork: Network? = null
        val infos = caps.map { (network, cap) ->
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpnNetwork = network
            NetInfo(
                wifi = cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                ethernet = cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                vpn = cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                internet = cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            )
        }
        return netStateFrom(infos) to vpnNetwork
    }
}

/** Live Wi-Fi / Ethernet / VPN state for the header icons. */
@Composable
fun rememberNetState(): State<NetState> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { mutableStateOf(NetState()) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            return@DisposableEffect onDispose { }
        }
        val nets = LiveNets()
        var generation = 0
        var inFlight = false
        fun publish(refreshCountry: Boolean) {
            val (next, vpn) = nets.snapshot()
            if (!next.vpn) {
                generation++
                inFlight = false
                state.value = next.copy(country = null)
                return
            }
            val known = if (refreshCountry) null else state.value.country
            state.value = next.copy(country = known)
            if (known != null || vpn == null || (inFlight && !refreshCountry)) return
            inFlight = true
            val ticket = ++generation
            scope.launch {
                val code = withContext(Dispatchers.IO) { VpnCountry.lookup(vpn) }
                if (ticket != generation) return@launch
                inFlight = false
                if (state.value.vpn) state.value = state.value.copy(country = code)
            }
        }
        fun note(network: Network, caps: NetworkCapabilities?, refreshCountry: Boolean) {
            if (caps == null) return
            nets.update(network, caps)
            publish(refreshCountry)
        }
        val active = cm.activeNetwork
        if (active != null) note(active, cm.getNetworkCapabilities(active), refreshCountry = false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                note(network, caps, refreshCountry = vpn)
            }
            override fun onLost(network: Network) {
                nets.remove(network)
                publish(refreshCountry = false)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                note(network, caps, refreshCountry = false)
            }
        }
        // Not the default-network callback: that would hide the physical link while a VPN is up.
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return state
}

/** VPN apps worth opening directly, most common first. */
val VPN_APP_PACKAGES: List<String> = listOf(
    "com.wireguard.android",
    "com.tailscale.ipn",
    "net.openvpn.openvpn",
    "de.blinkt.openvpn",
    "ch.protonvpn.android",
    "com.mullvad.mullvadvpn",
    "com.nordvpn.android",
    "com.expressvpn.vpn",
    "com.privateinternetaccess.android",
    "com.cloudflare.onedotonedotonedotone",
    "com.surfshark.vpnclient.android",
)

fun openNetworkSettings(context: Context, link: LinkType) {
    val first = if (link == LinkType.ETHERNET) Settings.ACTION_WIRELESS_SETTINGS else Settings.ACTION_WIFI_SETTINGS
    launchFirstThatWorks(
        context,
        "Network settings",
        listOf(Intent(first), Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS)),
    )
}

/** Open the user's VPN app when one is installed, otherwise the system VPN settings. */
fun openVpn(context: Context) {
    val pm = context.packageManager
    val appIntent = VPN_APP_PACKAGES.firstNotNullOfOrNull { pkg ->
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }
    val candidates = listOfNotNull(appIntent) + listOf(
        Intent(Settings.ACTION_VPN_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    launchFirstThatWorks(context, "VPN", candidates)
}

/** Settings screens vary by TV vendor, so fall through the candidates until one starts. */
fun launchFirstThatWorks(context: Context, label: String, intents: List<Intent>) {
    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    Toast.makeText(context, "Could not open $label", Toast.LENGTH_SHORT).show()
}
