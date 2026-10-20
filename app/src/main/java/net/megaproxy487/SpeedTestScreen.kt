package net.megaproxy487

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.megaproxy487.uiStringResource as stringResource

private data class RouteSpeedResult(val result: Result<CloudflareRouteResult>?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeedTestScreen(activity: Activity, onBack: () -> Unit) {
    var results by remember { mutableStateOf<List<RouteSpeedResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var megabytes by remember { mutableStateOf(SPEED_TEST_MEGABYTES.first()) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
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
            Button(
                enabled = !running,
                shape = RoundedCornerShape(12.dp),
                onClick = {
                    running = true
                    results = emptyList()
                    log = emptyList()
                    scope.launch {
                        results = withContext(Dispatchers.IO) {
                            runSpeedChecks(activity, megabytes) { entry ->
                                withContext(Dispatchers.Main) { log = log + "${speedLogTime()}  $entry" }
                            }
                        }
                        running = false
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
    val empty = results.isEmpty()
    val underlying = results.firstOrNull()?.result
    val tunnel = results.lastOrNull()?.result
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SpeedRow("", stringResource(R.string.external_underlying_network), stringResource(R.string.external_tunnel_network), header = true)
            SpeedRow(stringResource(R.string.cloudflare_speed_ip), speedValue(underlying, empty) { it.exitIp }, speedValue(tunnel, empty) { it.exitIp })
            SpeedRow(
                stringResource(R.string.cloudflare_speed_country),
                speedValue(underlying, empty) { it.countryCode.takeIf(String::isNotBlank)?.let { code -> formatCountry(code, systemFormattingLocale()) } ?: "—" },
                speedValue(tunnel, empty) { it.countryCode.takeIf(String::isNotBlank)?.let { code -> formatCountry(code, systemFormattingLocale()) } ?: "—" },
            )
            SpeedRow(stringResource(R.string.cloudflare_speed_protocol), speedValue(underlying, empty) { it.protocol }, speedValue(tunnel, empty) { it.protocol })
            SpeedRow(stringResource(R.string.cloudflare_speed_latency), speedValue(underlying, empty) { stringResource(R.string.latency_milliseconds, it.latencyMs) }, speedValue(tunnel, empty) { stringResource(R.string.latency_milliseconds, it.latencyMs) })
            SpeedRow(stringResource(R.string.cloudflare_speed_download), speedValue(underlying, empty) { stringResource(R.string.cloudflare_speed_value, it.downloadMbps) }, speedValue(tunnel, empty) { stringResource(R.string.cloudflare_speed_value, it.downloadMbps) })
            SpeedRow(stringResource(R.string.cloudflare_speed_upload), speedValue(underlying, empty) { stringResource(R.string.cloudflare_speed_value, it.uploadMbps) }, speedValue(tunnel, empty) { stringResource(R.string.cloudflare_speed_value, it.uploadMbps) })
        }
    }
}

@Composable
private fun speedValue(result: Result<CloudflareRouteResult>?, empty: Boolean, value: @Composable (CloudflareRouteResult) -> String): String = when {
    empty -> "—"
    result == null -> stringResource(R.string.unavailable)
    result.isFailure -> stringResource(R.string.cloudflare_speed_failed_short)
    else -> value(result.getOrThrow())
}

@Composable
private fun SpeedRow(label: String, underlying: String, tunnel: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(0.8f), fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
        Text(underlying, Modifier.weight(1f), fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
        Text(tunnel, Modifier.weight(1f), fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
    }
}

private suspend fun runSpeedChecks(activity: Activity, megabytes: Int, log: suspend (String) -> Unit): List<RouteSpeedResult> {
    suspend fun check(route: String, vpn: Boolean): RouteSpeedResult {
        log("event=speed_test route=$route result=started bytes=${megabytes * 1_000_000}")
        val network = findRouteNetwork(activity, vpn)
        if (network == null) {
            log("event=speed_test route=$route result=unavailable")
            return RouteSpeedResult(null)
        }
        val result = runCatching { runCloudflareSpeedCheck(network, megabytes) }
        log("event=speed_test route=$route result=${if (result.isSuccess) "success" else "failed"}" +
            (result.exceptionOrNull()?.let { " error=${it.javaClass.simpleName}" } ?: ""))
        return RouteSpeedResult(result)
    }
    return listOf(
        check("underlying", false),
        check("tunnel", true),
    )
}

private fun speedLogTime(): String = java.time.LocalTime.now().format(
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS", systemFormattingLocale()),
)
