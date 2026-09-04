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

private enum class VisibilityState(val labelResource: Int) {
    DETECTED(R.string.detected),
    SELECTED_APPS_ONLY(R.string.selected_apps_only),
    NOT_DETECTED(R.string.not_detected),
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
internal fun VisibilityScreen(activity: Activity, onBack: () -> Unit) {
    var report by remember { mutableStateOf(buildVisibilityReport(activity)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.visibility)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                stringResource(R.string.visibility_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { report = buildVisibilityReport(activity) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.run_checks_again))
            }
            VisibilitySection(stringResource(R.string.globally_observable_signals), report.global)
            if (report.splitTunneling) {
                Text(
                    stringResource(R.string.split_visibility_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            VisibilitySection(
                stringResource(if (report.splitTunneling) R.string.visible_to_selected_apps else R.string.vpn_network_signals),
                report.selectedApps,
            )
            Text(
                stringResource(R.string.visibility_disclaimer),
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
        Text(stringResource(state.labelResource), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
        if (detected && splitTunneling) context.getString(R.string.excluded_apps_underlying_network, yes) else if (detected) yes else no,
    )

    return VisibilityReport(
        global = listOf(
            check(context.getString(R.string.system_proxy_properties), systemProxyVisible, context.getString(R.string.system_proxy_yes), context.getString(R.string.system_proxy_no)),
            check(context.getString(R.string.vpn_interface_name), vpnInterfaces.isNotEmpty(), context.getString(R.string.vpn_interface_yes), context.getString(R.string.vpn_interface_no)),
            check(context.getString(R.string.unusual_vpn_mtu), unusualMtu.isNotEmpty(), context.getString(R.string.unusual_vpn_mtu_yes), context.getString(R.string.unusual_vpn_mtu_no)),
            VisibilityCheck(context.getString(R.string.local_mitm_certificate), VisibilityState.NOT_DETECTED, context.getString(R.string.local_mitm_certificate_detail)),
        ),
        selectedApps = listOf(
            vpnCheck(context.getString(R.string.vpn_transport), vpnTransportVisible, context.getString(R.string.vpn_transport_yes), context.getString(R.string.vpn_transport_no)),
            vpnCheck(context.getString(R.string.vpn_transport_information), vpnInfoVisible, context.getString(R.string.vpn_info_yes), context.getString(R.string.vpn_info_no)),
            vpnCheck(context.getString(R.string.not_vpn_capability), vpnWithoutNotVpn, context.getString(R.string.not_vpn_yes), context.getString(R.string.not_vpn_no)),
            vpnCheck(context.getString(R.string.vpn_link_proxy), linkProxyVisible, context.getString(R.string.vpn_link_proxy_yes), context.getString(R.string.vpn_link_proxy_no)),
            vpnCheck(context.getString(R.string.virtual_default_route), virtualDefaultRoute, context.getString(R.string.virtual_route_yes), context.getString(R.string.virtual_route_no)),
            vpnCheck(context.getString(R.string.virtual_dns_address), virtualDns, context.getString(R.string.virtual_dns_yes), context.getString(R.string.virtual_dns_no)),
        ),
        splitTunneling = splitTunneling,
    )
}
