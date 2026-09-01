package net.megaproxy487

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProfileColorMatcher
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.TlsProfile
import java.util.Locale

class ProfileEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ProfileEditorScreen(this) } }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    val profileId = remember { activity.intent.getStringExtra(ProfileEditorActivity.EXTRA_PROFILE_ID) }
    var profile by remember { mutableStateOf(store.profile(profileId.orEmpty()) ?: store.activeProfile()) }
    var config by remember { mutableStateOf(profile.config) }
    var portText by remember { mutableStateOf(config.port.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var countryExpanded by remember { mutableStateOf(false) }
    var tlsExpanded by remember { mutableStateOf(false) }
    var dnsExpanded by remember { mutableStateOf(false) }
    var showInvalidCertificateWarning by remember { mutableStateOf(false) }
    val countries = remember {
        Locale.getISOCountries().map { code ->
            code to Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
        }.sortedBy { it.second.lowercase(Locale.getDefault()) }
    }

    fun saveProfile() = store.saveProfile(profile)
    fun updateConfig(updated: ProxyConfig) {
        config = updated
        profile = profile.copy(config = updated)
        saveProfile()
        error = updated.connectionValidationError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile.displayName) },
                navigationIcon = {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(profile.name, { name ->
                profile = profile.copy(name = name)
                saveProfile()
            }, label = { Text("Profile name (optional)") }, supportingText = { Text("Defaults to the proxy host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(countryExpanded, { countryExpanded = it }) {
                OutlinedTextField(
                    profile.countryCode.takeIf(String::isNotEmpty)?.let { code ->
                        "${profile.flagEmoji} ${countries.firstOrNull { it.first == code }?.second ?: code}"
                    } ?: "No flag",
                    {}, readOnly = true, label = { Text("Country flag") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(countryExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                DropdownMenu(countryExpanded, { countryExpanded = false }) {
                    DropdownMenuItem(text = { Text("No flag") }, onClick = {
                        profile = profile.copy(countryCode = "")
                        saveProfile()
                        countryExpanded = false
                    })
                    countries.forEach { (code, name) ->
                        val flag = net.megaproxy487.model.ProxyProfile("", colorIndex = 0, countryCode = code).flagEmoji
                        DropdownMenuItem(text = { Text("$flag $name") }, onClick = {
                            profile = profile.copy(
                                countryCode = code,
                                colorIndex = ProfileColorMatcher.colorIndexForFlag(code, profile.colorIndex),
                            )
                            saveProfile()
                            countryExpanded = false
                        })
                    }
                }
            }

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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Allow self-signed proxy certificate")
                    Text("Disables certificate verification only for the HTTPS proxy.", style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(config.allowInvalidProxyCertificate, { checked ->
                    if (checked) showInvalidCertificateWarning = true
                    else updateConfig(config.copy(allowInvalidProxyCertificate = false))
                })
            }

            Text("TLS fingerprint", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(tlsExpanded, { tlsExpanded = it }) {
                OutlinedTextField(config.profile.title, {}, readOnly = true, label = { Text("TLS / JA3 profile") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(tlsExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                DropdownMenu(tlsExpanded, { tlsExpanded = false }) {
                    TlsProfile.entries.forEach { tls -> DropdownMenuItem(text = { Text(tls.title) }, enabled = tls.available, onClick = { updateConfig(config.copy(profile = tls)); tlsExpanded = false }) }
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
            OutlinedButton(onClick = {
                activity.startActivity(Intent(activity, SplitTunnelActivity::class.java).apply {
                    putStringArrayListExtra(SplitTunnelActivity.EXTRA_PROFILE_IDS, arrayListOf(profile.id))
                })
            }, modifier = Modifier.fillMaxWidth()) { Text("Routing") }
        }
    }

    if (showInvalidCertificateWarning) {
        AlertDialog(
            onDismissRequest = { showInvalidCertificateWarning = false },
            title = { Text("Allow an untrusted proxy certificate?") },
            text = { Text("This disables certificate-chain and hostname verification between your phone and the HTTPS proxy. An attacker could impersonate the proxy and obtain its Basic Auth credentials. Destination HTTPS certificates remain verified.") },
            confirmButton = { TextButton(onClick = {
                updateConfig(config.copy(allowInvalidProxyCertificate = true))
                showInvalidCertificateWarning = false
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showInvalidCertificateWarning = false }) { Text("Cancel") } },
        )
    }
}
