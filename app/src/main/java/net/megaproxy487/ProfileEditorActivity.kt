package net.megaproxy487

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProfileColorMatcher
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyType
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.ui.theme.MegaProxyTheme
import java.util.Locale

class ProfileEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // This screen can display proxy passwords and SSH private keys. Keep them
        // out of screenshots, screen recording and the recent-apps thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MegaProxyTheme { ProfileEditorScreen(this) } }
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
    var jumpPortText by remember { mutableStateOf(config.jumpPort.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var countryExpanded by remember { mutableStateOf(false) }
    var dnsExpanded by remember { mutableStateOf(false) }
    var showInvalidCertificateWarning by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var unsafeHostKeyHop by remember { mutableStateOf<String?>(null) }
    var showReconnectPrompt by remember { mutableStateOf(false) }
    var showAlwaysOnNotice by remember { mutableStateOf(false) }
    var connectionChangeDeferred by remember { mutableStateOf(false) }
    val countries = remember {
        Locale.getISOCountries().map { code ->
            code to Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
        }.sortedBy { it.second.lowercase(Locale.getDefault()) }
    }

    fun saveProfile() = store.saveProfile(profile)
    fun acceptText(value: String, maxLength: Int, update: (String) -> Unit) {
        if (value.length <= maxLength) update(value)
    }
    fun updateConfig(updated: ProxyConfig) {
        config = updated
        profile = profile.copy(config = updated)
        saveProfile()
        error = store.globalConnectionSettings().applyTo(updated).connectionValidationError()
        if (ProxyVpnService.isRunning && !connectionChangeDeferred && profile.id == store.connectionProfile().id) {
            if (ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled) {
                connectionChangeDeferred = true
                showAlwaysOnNotice = true
            } else {
                showReconnectPrompt = true
            }
        }
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
            OutlinedTextField(profile.name, { name -> acceptText(name, 256) {
                profile = profile.copy(name = name)
                saveProfile()
            } }, label = { Text("Profile name (optional)") }, supportingText = { Text("Defaults to the proxy host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(countryExpanded, { countryExpanded = it }) {
                OutlinedTextField(
                    profile.countryCode.takeIf(String::isNotEmpty)?.let { code ->
                        "${profile.flagEmoji} ${countries.firstOrNull { it.first == code }?.second ?: code}"
                    } ?: "No flag",
                    {}, readOnly = true, label = { Text("Country flag") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(countryExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
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

            Text("Connection", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(typeExpanded, { typeExpanded = it }) {
                OutlinedTextField(config.type.title, {}, readOnly = true, label = { Text("Profile type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                DropdownMenu(typeExpanded, { typeExpanded = false }) {
                    ProxyType.entries.forEach { type -> DropdownMenuItem(text = { Text(type.title) }, onClick = {
                        updateConfig(config.copy(type = type, port = type.defaultPort))
                        portText = type.defaultPort.toString()
                        if (type == ProxyType.SSH_JUMP) jumpPortText = config.jumpPort.toString()
                        typeExpanded = false
                    }) }
                }
            }
            OutlinedTextField(config.host, { value -> acceptText(value, 253) { updateConfig(config.copy(host = it)) } }, label = { Text(if (config.type == ProxyType.HTTPS) "HTTPS proxy hostname" else "Destination SSH hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(portText, { value ->
                portText = value
                val port = value.toIntOrNull()
                if (port == null) error = "Port must be between 1 and 65535"
                else updateConfig(config.copy(port = port))
            }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.username, { value -> acceptText(value, 4_096) { updateConfig(config.copy(username = it)) } }, label = { Text(if (config.type == ProxyType.HTTPS) "Basic Auth username" else "SSH username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(config.password, { value -> acceptText(value, 16_384) { updateConfig(config.copy(password = it)) } }, label = { Text(if (config.type == ProxyType.HTTPS) "Password" else "SSH password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (config.type == ProxyType.HTTPS) SettingCheckboxRow(
                checked = config.allowInvalidProxyCertificate,
                title = "Allow self-signed proxy certificate",
                description = "Disables certificate verification only for the HTTPS proxy.",
                onCheckedChange = { checked ->
                    if (checked) showInvalidCertificateWarning = true
                    else updateConfig(config.copy(allowInvalidProxyCertificate = false))
                },
            )

            if (config.type != ProxyType.HTTPS) {
                OutlinedTextField(config.privateKey, { value -> acceptText(value, 64 * 1024) { updateConfig(config.copy(privateKey = it)) } }, label = { Text("Private key (optional)") }, supportingText = { Text("PEM or OpenSSH format. Passphrase-protected keys are not supported.") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (config.password.isBlank() && config.privateKey.isBlank()) {
                    Text(
                        "No SSH password or private key is configured. Connection will only work if the server permits authentication without credentials.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SettingCheckboxRow(config.acceptAnyHostKey, "Accept any destination host key", "Unsafe: disables SSH server identity verification.") { checked ->
                    if (checked) unsafeHostKeyHop = "destination" else updateConfig(config.copy(acceptAnyHostKey = false))
                }
                if (config.trustedHostKey.isNotBlank()) Text("Trusted destination key: ${config.trustedHostKey}", style = MaterialTheme.typography.bodySmall)
            }

            if (config.type == ProxyType.SSH_JUMP) {
                HorizontalDivider()
                Text("Jump host", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(config.jumpHost, { value -> acceptText(value, 253) { updateConfig(config.copy(jumpHost = it)) } }, label = { Text("Jump SSH hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(jumpPortText, { value ->
                    if (value.length <= 5 && value.all(Char::isDigit)) {
                        jumpPortText = value
                        value.toIntOrNull()?.let { updateConfig(config.copy(jumpPort = it)) }
                    }
                }, label = { Text("Jump port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SettingCheckboxRow(config.sameJumpAuthentication, "Use the same authentication", "Reuse destination username, password and private key.") {
                    updateConfig(config.copy(sameJumpAuthentication = it))
                }
                if (!config.sameJumpAuthentication) {
                    OutlinedTextField(config.jumpUsername, { value -> acceptText(value, 4_096) { updateConfig(config.copy(jumpUsername = it)) } }, label = { Text("Jump SSH username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(config.jumpPassword, { value -> acceptText(value, 16_384) { updateConfig(config.copy(jumpPassword = it)) } }, label = { Text("Jump SSH password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(config.jumpPrivateKey, { value -> acceptText(value, 64 * 1024) { updateConfig(config.copy(jumpPrivateKey = it)) } }, label = { Text("Jump private key (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    if (config.jumpPassword.isBlank() && config.jumpPrivateKey.isBlank()) {
                        Text(
                            "No jump password or private key is configured. Connection will only work if the jump server permits authentication without credentials.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                SettingCheckboxRow(config.jumpAcceptAnyHostKey, "Accept any jump host key", "Unsafe: disables jump host identity verification.") { checked ->
                    if (checked) unsafeHostKeyHop = "jump" else updateConfig(config.copy(jumpAcceptAnyHostKey = false))
                }
                if (config.jumpTrustedHostKey.isNotBlank()) Text("Trusted jump key: ${config.jumpTrustedHostKey}", style = MaterialTheme.typography.bodySmall)
            }

            SettingCheckboxRow(config.allowIpv6, "Enable IPv6 destinations", "Enable only if this proxy can reach IPv6 destinations.") {
                updateConfig(config.copy(allowIpv6 = it))
            }

            Text("DNS over HTTPS", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(dnsExpanded, { dnsExpanded = it }) {
                OutlinedTextField(config.dnsProvider.title, {}, readOnly = true, label = { Text("DNS over HTTPS") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dnsExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                DropdownMenu(dnsExpanded, { dnsExpanded = false }) {
                    DnsProvider.entries.forEach { provider -> DropdownMenuItem(text = { Text(provider.title) }, onClick = { updateConfig(config.copy(dnsProvider = provider)); dnsExpanded = false }) }
                }
            }
            if (config.dnsProvider == DnsProvider.CUSTOM) OutlinedTextField(config.customDohUrl, { value -> acceptText(value, 2_048) { updateConfig(config.copy(customDohUrl = it)) } }, label = { Text("Custom DoH URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (showReconnectPrompt) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Reconnect to apply these changes to the active VPN.", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                showReconnectPrompt = false
                                connectionChangeDeferred = true
                            }) { Text("Next connection") }
                            TextButton(onClick = {
                                showReconnectPrompt = false
                                connectionChangeDeferred = true
                                ProxyVpnService.reconnect(activity)
                            }) { Text("Reconnect now") }
                        }
                    }
                }
            }
            if (showAlwaysOnNotice) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Always-on is active. Changes apply on the next connection.", modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAlwaysOnNotice = false }) { Text("Dismiss") }
                    }
                }
            }
            Text("Changes are saved automatically.", style = MaterialTheme.typography.bodySmall)
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
    unsafeHostKeyHop?.let { hop ->
        AlertDialog(
            onDismissRequest = { unsafeHostKeyHop = null },
            title = { Text("Accept any SSH host key?") },
            text = { Text("This disables identity verification for the $hop SSH host and permits man-in-the-middle attacks. Credentials and tunneled traffic could be intercepted.") },
            confirmButton = { TextButton(onClick = { updateConfig(if (hop == "jump") config.copy(jumpAcceptAnyHostKey = true) else config.copy(acceptAnyHostKey = true)); unsafeHostKeyHop = null }) { Text("Accept any key") } },
            dismissButton = { TextButton(onClick = { unsafeHostKeyHop = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingCheckboxRow(
    checked: Boolean,
    title: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
            value = checked,
            onValueChange = onCheckedChange,
            role = Role.Checkbox,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Checkbox(checked, null)
    }
}
