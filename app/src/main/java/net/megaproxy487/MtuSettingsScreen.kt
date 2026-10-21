package net.megaproxy487

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigEdits
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigWrites
import net.megaproxy487.data.settings
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.model.VPN_MTU_RANGE
import net.megaproxy487.uiStringResource as stringResource
import net.megaproxy487.vpn.ProxyVpnService

@Composable
internal fun MtuSettingsScreen(activity: Activity, onBack: () -> Unit) {
    val store = remember { ConfigStore(activity) }
    val initial = readConfiguration(store) { store.globalConnectionSettings() } ?: return
    var settings by remember { mutableStateOf(initial) }
    val edits = remember { ConfigEdits<GlobalConnectionSettings>() }
    val presets = listOf(1280 to R.string.mtu_1280, 1400 to R.string.mtu_1400, 1500 to R.string.mtu_1500)
    var custom by rememberSaveable { mutableStateOf(settings.mtu !in presets.map { it.first }) }
    var input by rememberSaveable { mutableStateOf(settings.mtu.toString()) }
    val value = input.toIntOrNull()?.takeIf { it in VPN_MTU_RANGE }

    fun save(mtu: Int) {
        if (settings.mtu == mtu) return
        val updated = settings.copy(mtu = mtu)
        val edit = edits.settings(settings, updated)
        settings = updated
        ConfigWrites.submit("global") {
            edit.write { store.editGlobalSettings(it) }
            if (ProxyVpnService.isRunning) store.markPendingReconnect()
        }
    }

    SettingsScaffold(onBack, stringResource(R.string.mtu_title)) {
        Text(stringResource(R.string.mtu_explanation), style = MaterialTheme.typography.bodySmall)
        Column {
            presets.forEach { (mtu, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                        selected = !custom && settings.mtu == mtu,
                        role = Role.RadioButton,
                        onClick = { custom = false; save(mtu) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(!custom && settings.mtu == mtu, null)
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                    selected = custom, role = Role.RadioButton, onClick = { custom = true },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(custom, null)
                Text(stringResource(R.string.mtu_custom), Modifier.weight(1f))
            }
        }
        if (custom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { FieldLabel(stringResource(R.string.mtu_custom_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = value == null,
                supportingText = { Text(stringResource(R.string.mtu_invalid)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { value?.let(::save) }, enabled = value != null && value != settings.mtu) {
                Text(stringResource(R.string.apply_new_settings))
            }
        }
        Text(stringResource(R.string.mtu_reconnect), style = MaterialTheme.typography.bodySmall)
    }
}
