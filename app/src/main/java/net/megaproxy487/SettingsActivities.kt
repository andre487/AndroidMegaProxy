package net.megaproxy487

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.Ja3Spec
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.readAlwaysOnVpnStatus

class ProxySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SettingsHomeScreen(this) } }
    }
}

class AlwaysOnSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AlwaysOnSettingsScreen(this) } }
    }
}

class TlsFingerprintActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TlsFingerprintScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(activity: Activity) {
    var showCrashConfirmation by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember { activity.getSystemService(PowerManager::class.java) }
    var batteryOptimizationDisabled by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(activity.packageName))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationDisabled = powerManager.isIgnoringBatteryOptimizations(activity.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsScaffold(activity, "Settings") {
        SettingsButton("Profiles") { activity.startActivity(Intent(activity, ProfilesActivity::class.java)) }
        SettingsButton("Always-on VPN") { activity.startActivity(Intent(activity, AlwaysOnSettingsActivity::class.java)) }
        SettingsButton("TLS fingerprint") { activity.startActivity(Intent(activity, TlsFingerprintActivity::class.java)) }
        SettingsButton("Split tunneling") { activity.startActivity(Intent(activity, SplitTunnelActivity::class.java)) }
        if (!batteryOptimizationDisabled) {
            SettingsButton("Battery settings") {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                })
            }
        }
        SettingsButton("Diagnostic log") { activity.startActivity(Intent(activity, DiagnosticLogActivity::class.java)) }
        SettingsButton("Crash") { showCrashConfirmation = true }
    }
    if (showCrashConfirmation) {
        AlertDialog(
            onDismissRequest = { showCrashConfirmation = false },
            title = { Text("Crash MegaProxy?") },
            text = { Text("The app will close immediately. Reopen it to test the crash report dialog.") },
            confirmButton = { TextButton(onClick = { throw IllegalStateException("Intentional crash requested from Settings") }) { Text("Crash") } },
            dismissButton = { TextButton(onClick = { showCrashConfirmation = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlwaysOnSettingsScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(store.alwaysOnProfile()) }
    var showDeferredNotice by remember { mutableStateOf(false) }
    SettingsScaffold(activity, "Always-on VPN") {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(
                selected.displayNameWithFlag, {}, readOnly = true,
                label = { Text("Profile for Always-on VPN") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            DropdownMenu(expanded, { expanded = false }) {
                store.sortedProfiles().forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.displayNameWithFlag) }, onClick = {
                        selected = profile
                        store.setAlwaysOnProfile(profile.id)
                        expanded = false
                        if (ProxyVpnService.isAlwaysOnMode && ProxyVpnService.isRunning) showDeferredNotice = true
                    })
                }
            }
        }
        Text("This profile is used when Android starts MegaProxy as an Always-on VPN.", style = MaterialTheme.typography.bodySmall)
        SettingsButton("Open Android Always-on VPN settings") {
            activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
    }
    if (showDeferredNotice) {
        AlertDialog(
            onDismissRequest = { showDeferredNotice = false },
            title = { Text("Profile saved") },
            text = { Text("The new Always-on profile will be used the next time the VPN connects.") },
            confirmButton = { TextButton(onClick = { showDeferredNotice = false }) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TlsFingerprintScreen(activity: Activity) {
    val store = remember { ConfigStore(activity) }
    var settings by remember { mutableStateOf(store.globalConnectionSettings()) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showReconnectPrompt by remember { mutableStateOf(false) }
    var showAlwaysOnNotice by remember { mutableStateOf(false) }
    var deferred by remember { mutableStateOf(false) }

    fun save(profile: TlsProfile = settings.tlsProfile, customJa3: String = settings.customJa3) {
        settings = settings.copy(tlsProfile = profile, customJa3 = customJa3)
        store.saveGlobalConnectionSettings(settings)
        error = if (profile == TlsProfile.CUSTOM && Ja3Spec.parse(customJa3) == null) {
            "JA3 must contain five fields: version,ciphers,extensions,groups,points"
        } else null
        if (ProxyVpnService.isRunning && !deferred) {
            if (ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled) {
                deferred = true
                showAlwaysOnNotice = true
            } else showReconnectPrompt = true
        }
    }

    SettingsScaffold(activity, "TLS fingerprint") {
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(
                settings.tlsProfile.title, {}, readOnly = true,
                label = { Text("TLS / JA3 profile") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            DropdownMenu(expanded, { expanded = false }) {
                TlsProfile.entries.filter { it.available }.forEach { profile ->
                    DropdownMenuItem(text = { Text(profile.title) }, onClick = { save(profile = profile); expanded = false })
                }
            }
        }
        Text("Default currently uses the Chrome Android fingerprint and may change in a future MegaProxy update.", style = MaterialTheme.typography.bodySmall)
        if (settings.tlsProfile == TlsProfile.CUSTOM) {
            OutlinedTextField(
                settings.customJa3,
                { save(customJa3 = it) },
                label = { Text("JA3: version,ciphers,extensions,groups,points") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Changes are saved automatically and apply to every profile.", style = MaterialTheme.typography.bodySmall)
    }
    if (showReconnectPrompt) {
        AlertDialog(
            onDismissRequest = { showReconnectPrompt = false; deferred = true },
            title = { Text("Apply TLS fingerprint change?") },
            text = { Text("Reconnect now to apply the new TLS fingerprint, or apply it the next time the VPN connects.") },
            confirmButton = { TextButton(onClick = { showReconnectPrompt = false; ProxyVpnService.reconnect(activity) }) { Text("Reconnect now") } },
            dismissButton = { TextButton(onClick = { showReconnectPrompt = false; deferred = true }) { Text("Next connection") } },
        )
    }
    if (showAlwaysOnNotice) {
        AlertDialog(
            onDismissRequest = { showAlwaysOnNotice = false },
            title = { Text("TLS fingerprint saved") },
            text = { Text("Always-on VPN is managed by Android. The new TLS fingerprint will be applied the next time the VPN connects.") },
            confirmButton = { TextButton(onClick = { showAlwaysOnNotice = false }) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(activity: Activity, title: String, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}
