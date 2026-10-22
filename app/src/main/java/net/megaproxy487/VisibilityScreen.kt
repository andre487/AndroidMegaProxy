package net.megaproxy487

import net.megaproxy487.data.readBoundedText

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.megaproxy487.uiStringResource as stringResource
import net.megaproxy487.ui.theme.MegaProxyTheme
import net.megaproxy487.data.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import net.megaproxy487.data.boundedJsonObject

private enum class VisibilityState(val labelResource: Int) {
    DETECTED(R.string.detected),
    SELECTED_APPS_ONLY(R.string.selected_apps_only),
    NOT_DETECTED(R.string.not_detected),
    UNKNOWN(R.string.undetermined),
    UNAVAILABLE(R.string.unavailable),
    LIMIT_REACHED(R.string.limit_reached),
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
internal fun VisibilityScreen(activity: Activity, onBack: () -> Unit, runNetworkChecks: Boolean = true) {
    var revision by remember { mutableStateOf(0) }
    val report = readConfiguration(revision) { buildVisibilityReport(activity) } ?: return
    var proxyCheckChecks by remember { mutableStateOf<List<VisibilityCheck>>(emptyList()) }
    LaunchedEffect(revision) {
        if (!runNetworkChecks) return@LaunchedEffect
        proxyCheckChecks = withContext(Dispatchers.IO) { runProxyCheckChecks(activity) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.visibility)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        ScreenContent(Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.visibility_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(shape = RoundedCornerShape(12.dp), onClick = { revision++ }, modifier = Modifier.fillMaxWidth()) {
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
                VisibilitySection(stringResource(R.string.proxycheck_reputation), proxyCheckChecks)
                Text(
                    stringResource(R.string.visibility_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun VisibilitySection(title: String, checks: List<VisibilityCheck>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    checks.forEach { check ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AdaptiveLabelRow(
                    label = { Text(check.title, style = MaterialTheme.typography.titleSmall, modifier = it) },
                    trailing = { VisibilityBadge(check.state) },
                )
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
        VisibilityState.UNKNOWN -> Color(0xFFF57C00)
        VisibilityState.UNAVAILABLE -> MaterialTheme.colorScheme.outline
        VisibilityState.LIMIT_REACHED -> Color(0xFFF57C00)
    }
    val foreground = if (state == VisibilityState.SELECTED_APPS_ONLY) Color.Black else Color.White
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(12.dp)) {
        Text(stringResource(state.labelResource), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private suspend fun runProxyCheckChecks(context: Context): List<VisibilityCheck> = coroutineScope {
    val underlying = async { findRouteNetwork(context, false)?.let { queryProxyCheck(context, it) } }
    val tunnel = async { findRouteNetwork(context, true)?.let { queryProxyCheck(context, it) } }
    listOf(
        proxyCheck(context, R.string.external_underlying_network, underlying.await()),
        proxyCheck(context, R.string.external_tunnel_network, tunnel.await()),
    )
}

internal enum class ProxyCheckVerdict { DETECTED, NOT_DETECTED, LIMIT_REACHED, UNKNOWN_FORMAT }
internal data class ProxyCheckResponse(val address: String?, val verdict: ProxyCheckVerdict)

private fun queryProxyCheck(context: Context, network: android.net.Network): Result<ProxyCheckResponse> = runCatching {
    val address = checkNotNull(lookupExitIp(network)) { "Exit IP unavailable" }
    cachedProxyCheck(context, address)?.let { return@runCatching ProxyCheckResponse(address, it) }
    val connection = network.openConnection(URL("$PROXYCHECK_LOOKUP_URL$address?p=0&tag=0")) as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.use { it.readBoundedText(64 * 1024) }.orEmpty()
        parseProxyCheckResponse(status, body).also { response ->
            val cacheAddress = response.address ?: address
            if (response.verdict in setOf(ProxyCheckVerdict.DETECTED, ProxyCheckVerdict.NOT_DETECTED)) {
                cacheProxyCheck(context, cacheAddress, response.verdict)
            }
        }
    } finally {
        connection.disconnect()
    }
}

internal fun parseProxyCheckResponse(httpStatus: Int, body: String): ProxyCheckResponse {
    val response = runCatching { boundedJsonObject(body) }.getOrNull()
        ?: return ProxyCheckResponse(null, ProxyCheckVerdict.UNKNOWN_FORMAT)
    val message = response.optString("message").lowercase()
    if (httpStatus == 429 && ("exhausted" in message || "daily" in message || "allowance" in message)) {
        return ProxyCheckResponse(null, ProxyCheckVerdict.LIMIT_REACHED)
    }
    if (httpStatus !in 200..299 || response.optString("status") !in setOf("ok", "warning")) {
        return ProxyCheckResponse(null, ProxyCheckVerdict.UNKNOWN_FORMAT)
    }
    val result = response.keys().asSequence().mapNotNull { key ->
        response.optJSONObject(key)?.optJSONObject("detections")?.let { key to it }
    }.firstOrNull() ?: return ProxyCheckResponse(null, ProxyCheckVerdict.UNKNOWN_FORMAT)
    val (address, detections) = result
    val fields = listOf("vpn", "proxy", "tor", "anonymous")
    if (fields.none(detections::has)) return ProxyCheckResponse(address, ProxyCheckVerdict.UNKNOWN_FORMAT)
    val verdict = if (fields.any { detections.optBoolean(it) }) ProxyCheckVerdict.DETECTED else ProxyCheckVerdict.NOT_DETECTED
    return ProxyCheckResponse(address, verdict)
}

private fun cachedProxyCheck(context: Context, address: String, now: Long = System.currentTimeMillis()): ProxyCheckVerdict? {
    val parts = context.getSharedPreferences(PROXYCHECK_CACHE, Context.MODE_PRIVATE)
        .getString(address, null)?.split('|', limit = 2) ?: return null
    val savedAt = parts.getOrNull(0)?.toLongOrNull() ?: return null
    if (!isFreshProxyCheckCache(savedAt, now)) return null
    return parts.getOrNull(1)?.let { runCatching { ProxyCheckVerdict.valueOf(it) }.getOrNull() }
}

private fun cacheProxyCheck(context: Context, address: String, verdict: ProxyCheckVerdict) {
    context.getSharedPreferences(PROXYCHECK_CACHE, Context.MODE_PRIVATE).edit()
        .putString(address, "${System.currentTimeMillis()}|${verdict.name}").apply()
}

internal fun isFreshProxyCheckCache(savedAt: Long, now: Long) = now >= savedAt && now - savedAt < PROXYCHECK_CACHE_TTL_MS

private fun proxyCheck(context: Context, title: Int, result: Result<ProxyCheckResponse>?): VisibilityCheck = when {
    result == null -> VisibilityCheck(context.uiText(title), VisibilityState.UNAVAILABLE, context.uiText(R.string.external_network_unavailable))
    result.isFailure -> VisibilityCheck(context.uiText(title), VisibilityState.UNAVAILABLE, context.uiText(R.string.proxycheck_failed))
    result.getOrThrow().verdict == ProxyCheckVerdict.DETECTED -> VisibilityCheck(context.uiText(title), VisibilityState.DETECTED, context.uiText(R.string.proxycheck_detected))
    result.getOrThrow().verdict == ProxyCheckVerdict.NOT_DETECTED -> VisibilityCheck(context.uiText(title), VisibilityState.NOT_DETECTED, context.uiText(R.string.proxycheck_not_detected))
    result.getOrThrow().verdict == ProxyCheckVerdict.LIMIT_REACHED -> VisibilityCheck(context.uiText(title), VisibilityState.LIMIT_REACHED, context.uiText(R.string.proxycheck_limit_reached))
    else -> VisibilityCheck(context.uiText(title), VisibilityState.UNKNOWN, context.uiText(R.string.proxycheck_unknown_format))
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
        if (detected && splitTunneling) context.uiText(R.string.excluded_apps_underlying_network, yes) else if (detected) yes else no,
    )

    return VisibilityReport(
        global = listOf(
            check(context.uiText(R.string.system_proxy_properties), systemProxyVisible, context.uiText(R.string.system_proxy_yes), context.uiText(R.string.system_proxy_no)),
            check(context.uiText(R.string.vpn_interface_name), vpnInterfaces.isNotEmpty(), context.uiText(R.string.vpn_interface_yes), context.uiText(R.string.vpn_interface_no)),
            check(context.uiText(R.string.unusual_vpn_mtu), unusualMtu.isNotEmpty(), context.uiText(R.string.unusual_vpn_mtu_yes), context.uiText(R.string.unusual_vpn_mtu_no)),
            VisibilityCheck(context.uiText(R.string.local_mitm_certificate), VisibilityState.NOT_DETECTED, context.uiText(R.string.local_mitm_certificate_detail)),
        ),
        selectedApps = listOf(
            vpnCheck(context.uiText(R.string.vpn_transport), vpnTransportVisible, context.uiText(R.string.vpn_transport_yes), context.uiText(R.string.vpn_transport_no)),
            vpnCheck(context.uiText(R.string.vpn_transport_information), vpnInfoVisible, context.uiText(R.string.vpn_info_yes), context.uiText(R.string.vpn_info_no)),
            vpnCheck(context.uiText(R.string.not_vpn_capability), vpnWithoutNotVpn, context.uiText(R.string.not_vpn_yes), context.uiText(R.string.not_vpn_no)),
            vpnCheck(context.uiText(R.string.vpn_link_proxy), linkProxyVisible, context.uiText(R.string.vpn_link_proxy_yes), context.uiText(R.string.vpn_link_proxy_no)),
            vpnCheck(context.uiText(R.string.virtual_default_route), virtualDefaultRoute, context.uiText(R.string.virtual_route_yes), context.uiText(R.string.virtual_route_no)),
            vpnCheck(context.uiText(R.string.virtual_dns_address), virtualDns, context.uiText(R.string.virtual_dns_yes), context.uiText(R.string.virtual_dns_no)),
        ),
        splitTunneling = splitTunneling,
    )
}

private const val PROXYCHECK_LOOKUP_URL = "https://proxycheck.io/v3/"
private const val PROXYCHECK_CACHE = "proxycheck_visibility_cache"
private const val PROXYCHECK_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
