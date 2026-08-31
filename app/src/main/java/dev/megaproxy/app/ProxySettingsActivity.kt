package dev.megaproxy.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
    var error by remember { mutableStateOf<String?>(null) }
    var profileExpanded by remember { mutableStateOf(false) }
    var dnsExpanded by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Proxy & JA3") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(config.host, { config = config.copy(host = it) }, label = { Text("HTTPS proxy hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.port.toString(), { config = config.copy(port = it.toIntOrNull() ?: 0) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.username, { config = config.copy(username = it) }, label = { Text("Basic Auth username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.password, { config = config.copy(password = it) }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(profileExpanded, { profileExpanded = it }) {
                OutlinedTextField(config.profile.title, {}, readOnly = true, label = { Text("TLS / JA3 profile") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(profileExpanded, { profileExpanded = false }) {
                    TlsProfile.entries.forEach { profile -> DropdownMenuItem(text = { Text(profile.title) }, enabled = profile.available, onClick = { config = config.copy(profile = profile); profileExpanded = false }) }
                }
            }
            if (config.profile == TlsProfile.CUSTOM) OutlinedTextField(config.customJa3, { config = config.copy(customJa3 = it) }, label = { Text("JA3: version,ciphers,extensions,groups,points") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            ExposedDropdownMenuBox(dnsExpanded, { dnsExpanded = it }) {
                OutlinedTextField(config.dnsProvider.title, {}, readOnly = true, label = { Text("DNS over HTTPS") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dnsExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(dnsExpanded, { dnsExpanded = false }) {
                    DnsProvider.entries.forEach { provider -> DropdownMenuItem(text = { Text(provider.title) }, onClick = { config = config.copy(dnsProvider = provider); dnsExpanded = false }) }
                }
            }
            if (config.dnsProvider == DnsProvider.CUSTOM) OutlinedTextField(config.customDohUrl, { config = config.copy(customDohUrl = it) }, label = { Text("Custom DoH URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                error = config.connectionValidationError()
                if (error == null) { store.save(config); activity.finish() }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}
