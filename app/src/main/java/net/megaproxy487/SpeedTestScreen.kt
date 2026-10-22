package net.megaproxy487

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.megaproxy487.uiStringResource as stringResource

private data class RouteSpeedResult(
    val values: CloudflareRouteResult = CloudflareRouteResult(),
    val pending: Boolean = false,
    val failed: Boolean = false,
    val unavailable: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedTestScreen(activity: Activity, onBack: () -> Unit) {
    var results by remember { mutableStateOf(List(2) { RouteSpeedResult() }) }
    var running by remember { mutableStateOf(false) }
    var preferredProtocol by remember { mutableStateOf(SPEED_TEST_PROTOCOLS.first()) }
    var megabytes by remember { mutableStateOf(SPEED_TEST_MEGABYTES.first()) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val store = remember(activity) { net.megaproxy487.data.ConfigStore(activity) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle(stringResource(R.string.speed)) },
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
                stringResource(R.string.cloudflare_speed_explanation, megabytes, megabytes * 4),
                style = MaterialTheme.typography.bodyMedium,
            )
            val settings = readConfiguration(store) { store.globalConnectionSettings() }
            if (settings != null && !settings.routeAllApps && activity.packageName !in settings.selectedPackages) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )) {
                    Text(stringResource(R.string.speed_split_tunnel_warning), Modifier.padding(12.dp))
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SPEED_TEST_MEGABYTES.forEach { size ->
                    Row(
                        Modifier.selectable(
                            selected = megabytes == size,
                            enabled = !running,
                            role = Role.RadioButton,
                            onClick = { megabytes = size },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = megabytes == size, onClick = null, enabled = !running)
                        Text(stringResource(R.string.cloudflare_speed_size, size))
                    }
                }
            }
            Text(stringResource(R.string.speed_protocol_preference), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SPEED_TEST_PROTOCOLS.forEach { protocol ->
                    Row(
                        Modifier.selectable(
                            selected = preferredProtocol == protocol, enabled = !running,
                            role = Role.RadioButton, onClick = { preferredProtocol = protocol },
                        ), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = preferredProtocol == protocol, onClick = null, enabled = !running)
                        Text(protocol)
                    }
                }
            }
            Button(
                enabled = !running,
                shape = RoundedCornerShape(12.dp),
                onClick = {
                    running = true
                    results = List(2) { RouteSpeedResult(pending = true) }
                    log = emptyList()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                runSpeedChecks(activity, megabytes, preferredProtocol,
                                    update = { index, result ->
                                        withContext(Dispatchers.Main) {
                                            results = results.toMutableList().also { it[index] = result }
                                        }
                                    },
                                    log = { entry ->
                                        withContext(Dispatchers.Main) { log = log + "${speedLogTime()}  $entry" }
                                    },
                                )
                            }
                        } finally { running = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (running) R.string.cloudflare_speed_running else R.string.cloudflare_speed_run))
            }
            SpeedResultsTable(results)
            SelectionContainer {
                Text(
                    log.joinToString("\n").ifEmpty {
                        stringResource(if (running) R.string.test_waiting else R.string.test_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SpeedResultsTable(results: List<RouteSpeedResult>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Three columns need more room when the system font is enlarged.
        if (maxWidth < 400.dp * LocalDensity.current.fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                results.forEachIndexed { index, result ->
                    SpeedResultsBlock(listOf(result), firstRoute = index)
                }
            }
        } else {
            SpeedResultsBlock(results)
        }
    }
}

@Composable
private fun SpeedResultsBlock(results: List<RouteSpeedResult>, firstRoute: Int = 0) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (results.size > 1) Text("", Modifier.weight(0.8f))
                results.indices.forEach { index ->
                    Text(
                        stringResource(if (firstRoute + index == 0) R.string.external_underlying_network else R.string.external_tunnel_network),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    )
                }
            }
            SpeedRow(R.string.cloudflare_speed_ip, results, firstRoute) { it.exitIp }
            SpeedRow(R.string.cloudflare_speed_country, results, firstRoute) { it.countryCode?.let(::formatCountryCode) }
            SpeedRow(R.string.cloudflare_speed_protocol, results, firstRoute) { it.protocol?.replace(" · ", "\n") }
            SpeedRow(R.string.cloudflare_speed_latency, results, firstRoute) { it.latencyMs?.let { ms -> stringResource(R.string.latency_milliseconds, ms) } }
            SpeedRow(R.string.cloudflare_speed_download, results, firstRoute) { it.downloadMbps?.let { speed -> stringResource(R.string.cloudflare_speed_value, speed) } }
            SpeedRow(R.string.cloudflare_speed_upload, results, firstRoute) { it.uploadMbps?.let { speed -> stringResource(R.string.cloudflare_speed_value, speed) } }
        }
    }
}

@Composable
private fun SpeedRow(label: Int, results: List<RouteSpeedResult>, firstRoute: Int, value: @Composable (CloudflareRouteResult) -> String?) {
    val title = stringResource(label)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
        results.forEachIndexed { index, result ->
            val route = stringResource(if (firstRoute + index == 0) R.string.external_underlying_network else R.string.external_tunnel_network)
            val progressDescription = "$title, $route: ${stringResource(R.string.cloudflare_speed_running)}"
            Box(Modifier.weight(1f)) {
                val text = value(result.values)
                when {
                    text != null -> if (label == R.string.cloudflare_speed_ip) {
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                    } else Text(text, style = MaterialTheme.typography.bodyMedium)
                    result.pending -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics { contentDescription = progressDescription }, strokeWidth = 2.dp,
                    )
                    result.failed -> Text(stringResource(R.string.cloudflare_speed_failed_short), style = MaterialTheme.typography.bodyMedium)
                    result.unavailable -> Text(stringResource(R.string.unavailable), style = MaterialTheme.typography.bodyMedium)
                    else -> Text("—", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private suspend fun runSpeedChecks(
    activity: Activity,
    megabytes: Int,
    preferredProtocol: String,
    update: suspend (Int, RouteSpeedResult) -> Unit,
    log: suspend (String) -> Unit,
) {
    listOf("underlying", "tunnel").forEachIndexed { index, route ->
        var state = RouteSpeedResult(pending = true)
        log("event=speed_test route=$route result=started bytes=${megabytes * 1_000_000}")
        val network = findRouteNetwork(activity, index == 1)
        if (network == null) {
            update(index, state.copy(pending = false, unavailable = true))
            log("event=speed_test route=$route result=unavailable")
        } else {
            try {
                runCloudflareSpeedCheck(network, megabytes, preferredProtocol,
                    onLog = { log("route=$route $it") },
                ) { values ->
                    state = state.copy(values = values)
                    update(index, state)
                }
                update(index, state.copy(pending = false))
                log("event=speed_test route=$route result=success")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update(index, state.copy(pending = false, failed = true))
                log("event=speed_test route=$route result=failed error=${error.javaClass.simpleName}")
            }
        }
    }
}

private fun speedLogTime(): String = java.time.LocalTime.now().format(
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS", systemFormattingLocale()),
)
