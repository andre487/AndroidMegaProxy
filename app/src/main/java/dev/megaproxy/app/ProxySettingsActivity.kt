package dev.megaproxy.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.megaproxy.app.data.ConfigStore
import dev.megaproxy.app.model.DnsProvider
import dev.megaproxy.app.model.ProxyConfig
import dev.megaproxy.app.model.TlsProfile

class ProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ProxySettingsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxySettingsScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    var config by remember { mutableStateOf(store.load()) }
    var portText by remember { mutableStateOf(config.port.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var profileExpanded by remember { mutableStateOf(false) }
    var dnsExpanded by remember { mutableStateOf(false) }

    fun updateConfig(updated: ProxyConfig) {
        config = updated
        store.save(updated)
        error = updated.connectionValidationError()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("HTTPS proxy", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(config.host, { updateConfig(config.copy(host = it)) }, label = { Text("HTTPS proxy hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(portText, { value ->
                portText = value
                val port = value.toIntOrNull()
                if (port == null) error = "Port must be between 1 and 65535"
                else updateConfig(config.copy(port = port))
            }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.username, { updateConfig(config.copy(username = it)) }, label = { Text("Basic Auth username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.password, { updateConfig(config.copy(password = it)) }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("TLS fingerprint", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(profileExpanded, { profileExpanded = it }) {
                OutlinedTextField(config.profile.title, {}, readOnly = true, label = { Text("TLS / JA3 profile") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(profileExpanded, { profileExpanded = false }) {
                    TlsProfile.entries.forEach { profile -> DropdownMenuItem(text = { Text(profile.title) }, enabled = profile.available, onClick = { updateConfig(config.copy(profile = profile)); profileExpanded = false }) }
                }
            }
            if (config.profile == TlsProfile.CUSTOM) OutlinedTextField(config.customJa3, { updateConfig(config.copy(customJa3 = it)) }, label = { Text("JA3: version,ciphers,extensions,groups,points") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("DNS over HTTPS", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(dnsExpanded, { dnsExpanded = it }) {
                OutlinedTextField(config.dnsProvider.title, {}, readOnly = true, label = { Text("DNS over HTTPS") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dnsExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(dnsExpanded, { dnsExpanded = false }) {
                    DnsProvider.entries.forEach { provider -> DropdownMenuItem(text = { Text(provider.title) }, onClick = { updateConfig(config.copy(dnsProvider = provider)); dnsExpanded = false }) }
                }
            }
            if (config.dnsProvider == DnsProvider.CUSTOM) OutlinedTextField(config.customDohUrl, { updateConfig(config.copy(customDohUrl = it)) }, label = { Text("Custom DoH URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Changes are saved automatically.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("VPN and routing", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { activity.startActivity(Intent(activity, SplitTunnelActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Split tunneling") }
            OutlinedButton(
                onClick = { activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Always-on VPN") }
            OutlinedButton(
                onClick = {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    })
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Battery settings") }
        }
    }
}
