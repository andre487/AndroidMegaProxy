package net.megaproxy487

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import net.megaproxy487.ui.theme.MegaProxyTheme
import net.megaproxy487.data.ConfigStore
import java.net.NetworkInterface

class VisibilityActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MegaProxyTheme { VisibilityScreen(this) } }
    }
}

private enum class VisibilityState(val label: String) {
    DETECTED("Detected"),
    SELECTED_APPS_ONLY("Selected apps only"),
    NOT_DETECTED("Not detected"),
}

private data class VisibilityCheck(
    val title: String,
    val state: VisibilityState,
    val detail: String,
)

private data class VisibilityReport(
    val global: List<VisibilityCheck>,
    val selectedApps: List<VisibilityCheck>,
    val splitTunneling: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisibilityScreen(activity: Activity) {
    var report by remember { mutableStateOf(buildVisibilityReport(activity)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.visibility)) },
                navigationIcon = {
                    IconButton(onClick = activity::finish) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Local checks show signals that another Android app may observe. No network request, location lookup, or external reputation service is used.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { report = buildVisibilityReport(activity) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.run_checks_again))
            }
            VisibilitySection("Globally observable signals", report.global)
            if (report.splitTunneling) {
                Text(
                    "The signals below belong to the VPN network. With split tunneling, Android exposes that network to selected apps; excluded apps use the underlying network instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            VisibilitySection(
                if (report.splitTunneling) "Visible to selected apps" else "VPN network signals",
                report.selectedApps,
            )
            Text(
                "A detected signal is not proof by itself. Android VPN APIs intentionally expose some VPN state, and legitimate security or filtering apps can produce the same signals.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun VisibilitySection(title: String, checks: List<VisibilityCheck>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    checks.forEach { check ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(check.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    VisibilityBadge(check.state)
                }
                Text(check.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VisibilityBadge(state: VisibilityState) {
    val background = when (state) {
        VisibilityState.DETECTED -> Color(0xFFB3261E)
        VisibilityState.SELECTED_APPS_ONLY -> Color(0xFFF9A825)
        VisibilityState.NOT_DETECTED -> Color(0xFF2E7D32)
    }
    val foreground = if (state == VisibilityState.SELECTED_APPS_ONLY) Color.Black else Color.White
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(12.dp)) {
        Text(state.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private fun buildVisibilityReport(context: Context): VisibilityReport {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val networks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
    val capabilities = networks.mapNotNull { network ->
        runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
    }
    val vpnCapabilities = capabilities.filter { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    val vpnTransportVisible = vpnCapabilities.isNotEmpty()
    val vpnInfoVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vpnCapabilities.any {
        runCatching { it.transportInfo?.javaClass?.simpleName == "VpnTransportInfo" }.getOrDefault(false)
    }
    val vpnWithoutNotVpn = vpnCapabilities.any { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }

    val linkProperties = networks.mapNotNull { network ->
        runCatching { connectivity.getLinkProperties(network) }.getOrNull()
    }
    val linkProxyVisible = linkProperties.any { it.httpProxy != null }
    val systemProxyVisible = listOf("http.proxyHost", "https.proxyHost", "socksProxyHost").any {
        System.getProperty(it).orEmpty().isNotBlank()
    }

    val interfaces = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }
    }.getOrDefault(emptyList())
    val vpnName = Regex("^(tun|tap|wg|ppp|ipsec)", RegexOption.IGNORE_CASE)
    val vpnInterfaces = interfaces.filter { vpnName.containsMatchIn(it.name) }
    val unusualMtu = vpnInterfaces.filter { it.mtu !in setOf(1280, 1500) }
    val virtualDefaultRoute = linkProperties.any { properties ->
        properties.routes.any { it.isDefaultRoute && vpnName.containsMatchIn(it.`interface`.orEmpty()) }
    }
    val virtualDns = linkProperties.any { properties ->
        properties.dnsServers.any { address ->
            address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
        }
    }
    val settings = ConfigStore(context).globalConnectionSettings()
    val splitTunneling = !settings.routeAllApps

    fun check(title: String, detected: Boolean, yes: String, no: String) = VisibilityCheck(
        title,
        if (detected) VisibilityState.DETECTED else VisibilityState.NOT_DETECTED,
        if (detected) yes else no,
    )
    fun vpnCheck(title: String, detected: Boolean, yes: String, no: String) = VisibilityCheck(
        title,
        when {
            !detected -> VisibilityState.NOT_DETECTED
            splitTunneling -> VisibilityState.SELECTED_APPS_ONLY
            else -> VisibilityState.DETECTED
        },
        if (detected && splitTunneling) "$yes Excluded apps use their underlying network." else if (detected) yes else no,
    )

    return VisibilityReport(
        global = listOf(
            check("System proxy properties", systemProxyVisible, "A process-wide proxy property is visible.", "No process-wide proxy property is visible."),
            check("VPN-like interface name", vpnInterfaces.isNotEmpty(), "An active interface has a tun, tap, wg, ppp, or ipsec-style name.", "No active interface has a common VPN-style name."),
            check("Unusual VPN MTU", unusualMtu.isNotEmpty(), "A VPN-style interface uses an MTU other than 1280 or 1500.", "No unusual MTU was found on VPN-style interfaces."),
            VisibilityCheck("Local MITM certificate", VisibilityState.NOT_DETECTED, "MegaProxy installs no CA certificate and performs no TLS interception."),
        ),
        selectedApps = listOf(
            vpnCheck("VPN transport", vpnTransportVisible, "Android exposes a network with TRANSPORT_VPN.", "No VPN transport is currently exposed."),
            vpnCheck("VPN transport information", vpnInfoVisible, "Android exposes VpnTransportInfo for the VPN network.", "VpnTransportInfo is not exposed on the currently visible networks."),
            vpnCheck("NOT_VPN capability", vpnWithoutNotVpn, "The VPN network does not have NET_CAPABILITY_NOT_VPN.", "No visible network is identified by this capability check as a VPN."),
            vpnCheck("VPN link proxy", linkProxyVisible, "A proxy setting is attached to the VPN network.", "No proxy setting is attached to the VPN network."),
            vpnCheck("Virtual default route", virtualDefaultRoute, "A visible default route uses a VPN-style interface.", "No visible default route uses a VPN-style interface."),
            vpnCheck("Virtual DNS address", virtualDns, "The VPN network exposes a private, link-local, or loopback DNS address.", "The VPN network does not expose a local DNS address."),
        ),
        splitTunneling = splitTunneling,
    )
}
