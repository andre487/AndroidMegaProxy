package net.megaproxy487

import androidx.compose.foundation.shape.RoundedCornerShape

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import net.megaproxy487.uiStringResource as stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.readPrivateKeyText
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProfileColorMatcher
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyType
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.readAlwaysOnVpnStatus
import net.megaproxy487.ui.theme.MegaProxyTheme
import java.util.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import net.megaproxy487.data.ConfigWrites
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ProfileEditorState(initialProfile: net.megaproxy487.model.ProxyProfile) : ViewModel() {
    var profile by mutableStateOf(initialProfile)
    var persisted by mutableStateOf(false)
    var config by mutableStateOf(profile.config)
    var portText by mutableStateOf(config.port.toString())
    var jumpPortText by mutableStateOf(config.jumpPort.toString())
    var error by mutableStateOf<String?>(null)
    var importingKey by mutableStateOf(false)
    var countryExpanded by mutableStateOf(false)
    var dnsExpanded by mutableStateOf(false)
    var invalidCertificateIsJump by mutableStateOf(false)
    var showInvalidCertificateWarning by mutableStateOf(false)
    var typeExpanded by mutableStateOf(false)
    var unsafeHostKeyHop by mutableStateOf<String?>(null)
    var showReconnectPrompt by mutableStateOf(false)
    var showAlwaysOnNotice by mutableStateOf(false)
    var connectionChangeDeferred by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditorScreen(activity: Activity, profileId: String?, onBack: () -> Unit) {
    val store = remember { ConfigStore(activity) }
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val draftId = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val isNew = profileId == "new"
    val editorState = viewModel {
        val existing = if (isNew) store.profile(draftId) else store.profile(profileId.orEmpty()) ?: store.activeProfile()
        ProfileEditorState(existing ?: store.newProfileDraft(draftId)).also { it.persisted = existing != null }
    }
    val coroutineScope = editorState.viewModelScope
    var profile by editorState::profile
    var config by editorState::config
    var portText by editorState::portText
    var jumpPortText by editorState::jumpPortText
    var error by editorState::error
    var importingKey by editorState::importingKey
    var countryExpanded by editorState::countryExpanded
    var dnsExpanded by editorState::dnsExpanded
    var invalidCertificateIsJump by editorState::invalidCertificateIsJump
    var showInvalidCertificateWarning by editorState::showInvalidCertificateWarning
    var typeExpanded by editorState::typeExpanded
    var unsafeHostKeyHop by editorState::unsafeHostKeyHop
    var showReconnectPrompt by editorState::showReconnectPrompt
    var showAlwaysOnNotice by editorState::showAlwaysOnNotice
    var connectionChangeDeferred by editorState::connectionChangeDeferred
    val countries = remember {
        Locale.getISOCountries().map { code ->
            code to Locale.Builder().setRegion(code).build().getDisplayCountry(systemFormattingLocale())
        }.sortedBy { it.second.lowercase(systemFormattingLocale()) }
    }

    val writeStatus by ConfigWrites.status.collectAsState()
    var globalSettings by remember(store) { mutableStateOf(store.globalConnectionSettings()) }
    androidx.compose.runtime.LaunchedEffect(writeStatus) {
        if (writeStatus.pending == 0) {
            globalSettings = withContext(net.megaproxy487.data.ConfigIoDispatcher) { store.globalConnectionSettings() }
        }
    }
    val editedConnectionProfileId = net.megaproxy487.vpn.VpnRuntimeState.connectionProfileId.value
        .ifEmpty { store.connectionProfileId() }
    val alwaysOnActive = ProxyVpnService.isAlwaysOnMode || readAlwaysOnVpnStatus(activity).enabled
    fun saveProfile(affectsConnection: Boolean = false) {
        val snapshot = profile
        ConfigWrites.submit("profile:${snapshot.id}") {
            store.saveProfile(snapshot, createIfMissing = isNew)
            editorState.persisted = true
            if (affectsConnection && ProxyVpnService.isRunning && snapshot.id == store.connectionProfileId()) store.markPendingReconnect()
        }
    }
    fun acceptText(value: String, maxLength: Int, update: (String) -> Unit) {
        if (value.length <= maxLength) update(value)
    }
    fun updateConfig(updated: ProxyConfig) {
        if (updated == config) return
        config = updated
        profile = profile.copy(config = updated)
        saveProfile(affectsConnection = true)
        error = globalSettings.applyTo(updated).connectionValidationError()?.let { activity.uiText(it) }
        if (ProxyVpnService.isRunning && !connectionChangeDeferred && profile.id == editedConnectionProfileId) {
            if (alwaysOnActive) {
                connectionChangeDeferred = true
                showAlwaysOnNotice = true
            } else {
                showReconnectPrompt = true
            }
        }
    }
    fun importPrivateKey(uri: Uri, jump: Boolean) {
        if (importingKey) return
        importingKey = true
        coroutineScope.launch {
            try {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.use { it.readPrivateKeyText() }
                        ?: throw UiException(R.string.error_open_file)
                }
            }
            result.onSuccess { imported ->
                updateConfig(if (jump) config.copy(jumpPrivateKey = imported) else config.copy(privateKey = imported))
                error = null
            }.onFailure { failure ->
                error = failure.userMessage(activity, R.string.key_import_failed)
            }
            } finally { importingKey = false }
        }
    }
    val destinationKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importPrivateKey(it, jump = false) }
    }
    val jumpKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importPrivateKey(it, jump = true) }
    }

    val validationRes = globalSettings.applyTo(config).connectionValidationError()
    fun fieldError(vararg ids: Int): String? = validationRes?.takeIf { it in ids }?.let { activity.uiText(it) }
    val validPorts = validIntegerInput(portText, 1..65535) != null &&
        (!config.type.hasJump || validIntegerInput(jumpPortText, 1..65535) != null)
    val canReconnect = validationRes == null && validPorts && writeStatus.pending == 0 && !writeStatus.failed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle(profile.localizedName(activity)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SaveStatusBanner()
                    if (importingKey) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.takeIf { it != validationRes?.let { id -> activity.uiText(id) } }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (!validPorts || validationRes != null) {
                        Text(stringResource(R.string.correct_fields_before_connecting), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (showReconnectPrompt || showAlwaysOnNotice) {
                        Text(stringResource(if (showAlwaysOnNotice) R.string.always_on_changes_next_connection else R.string.reconnect_apply_changes),
                            style = MaterialTheme.typography.bodySmall)
                        WrappingActions {
                            TextButton(shape = RoundedCornerShape(12.dp), onClick = { showReconnectPrompt = false; showAlwaysOnNotice = false; connectionChangeDeferred = true }) {
                                Text(stringResource(R.string.next_connection))
                            }
                            if (!showAlwaysOnNotice) TextButton(shape = RoundedCornerShape(12.dp), enabled = canReconnect, onClick = {
                                showReconnectPrompt = false; connectionChangeDeferred = true
                                ProxyVpnService.reconnect(activity)
                            }) { Text(stringResource(R.string.reconnect_now)) }
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          item {
            OutlinedTextField(profile.name, { name -> acceptText(name, 256) {
                profile = profile.copy(name = name)
                saveProfile()
            } }, label = { FieldLabel(stringResource(R.string.profile_name_optional)) }, supportingText = { Text(stringResource(R.string.defaults_to_proxy_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          }
          item {
            ExposedDropdownMenuBox(countryExpanded, { countryExpanded = it }) {
                OutlinedTextField(
                    profile.countryCode.takeIf(String::isNotEmpty)?.let { code ->
                        "${profile.flagEmoji} ${countries.firstOrNull { it.first == code }?.second ?: code}"
                    } ?: activity.uiText(R.string.no_flag),
                    {}, readOnly = true, label = { FieldLabel(stringResource(R.string.country_flag)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(countryExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                DropdownMenu(countryExpanded, { countryExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.no_flag)) }, onClick = {
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
          }

          item {
            Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleMedium)
          }
          item {
            ExposedDropdownMenuBox(typeExpanded, { typeExpanded = it }) {
                OutlinedTextField(activity.uiText(config.type.titleRes), {}, readOnly = true, label = { FieldLabel(stringResource(R.string.profile_type)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                DropdownMenu(typeExpanded, { typeExpanded = false }) {
                    ProxyType.entries.forEach { type -> DropdownMenuItem(text = { Text(activity.uiText(type.titleRes)) }, onClick = {
                        updateConfig(config.withType(type))
                        portText = config.port.toString()
                        if (type.hasJump) jumpPortText = config.jumpPort.toString()
                        typeExpanded = false
                    }) }
                }
            }
          }
          item {
            OutlinedTextField(config.host, { value -> acceptText(value, 253) { updateConfig(config.copy(host = it)) } }, label = { FieldLabel(stringResource(if (config.type == ProxyType.HTTPS_JUMP) R.string.destination_https_proxy_hostname else if (config.type.isHttps) R.string.https_proxy_hostname else R.string.destination_ssh_hostname)) }, isError = fieldError(R.string.validation_proxy_host, R.string.validation_ssh_host, R.string.validation_host_format) != null, supportingText = fieldError(R.string.validation_proxy_host, R.string.validation_ssh_host, R.string.validation_host_format)?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
          }
          item {
            IntegerInputField(portText, { portText = it }, 1..65535, stringResource(R.string.port),
                    onValidValue = { updateConfig(config.copy(port = it)) }, modifier = Modifier.fillMaxWidth())
          }
          item {
            OutlinedTextField(config.username, { value -> acceptText(value, 4_096) { updateConfig(config.copy(username = it)) } }, label = { FieldLabel(stringResource(if (config.type.isHttps) R.string.basic_auth_username else R.string.ssh_username)) }, isError = fieldError(R.string.validation_basic_username, R.string.validation_ssh_username) != null, supportingText = fieldError(R.string.validation_basic_username, R.string.validation_ssh_username)?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
          }
          item {
            PasswordField(config.password, { value -> acceptText(value, 16_384) { updateConfig(config.copy(password = it)) } }, label = stringResource(if (config.type.isHttps) R.string.password else R.string.ssh_password_optional),
                    error = fieldError(R.string.validation_basic_password), modifier = Modifier.fillMaxWidth())
          }
          if (config.type.isHttps) item { SettingCheckboxRow(
                checked = config.allowInvalidProxyCertificate,
                title = stringResource(R.string.allow_proxy_certificate),
                description = stringResource(R.string.allow_proxy_certificate_description),
                onCheckedChange = { checked ->
                    if (checked) { invalidCertificateIsJump = false; showInvalidCertificateWarning = true }
                    else updateConfig(config.copy(allowInvalidProxyCertificate = false))
                },
            )
          }

            if (!config.type.isHttps) {
              item {
                OutlinedTextField(config.privateKey, { value -> acceptText(value, 64 * 1024) { updateConfig(config.copy(privateKey = it)) } }, label = { FieldLabel(stringResource(R.string.private_key_optional)) }, supportingText = { Text(stringResource(R.string.private_key_format_hint)) }, minLines = 3, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
              }
              item {
                OutlinedButton(shape = RoundedCornerShape(12.dp),
                    enabled = !importingKey, onClick = { destinationKeyPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.import_destination_key)) }
              }
                if (config.password.isBlank() && config.privateKey.isBlank()) {
                  item {
                    Text(
                        activity.uiText(R.string.ssh_no_credentials),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                  }
                }
              item {
                SettingCheckboxRow(config.acceptAnyHostKey, activity.uiText(R.string.ssh_accept_destination), activity.uiText(R.string.ssh_accept_destination_help)) { checked ->
                    if (checked) unsafeHostKeyHop = "destination" else updateConfig(config.copy(acceptAnyHostKey = false))
                }
              }
                if (config.trustedHostKey.isNotBlank()) item { Text(stringResource(R.string.trusted_destination_key, config.trustedHostKey), style = MaterialTheme.typography.bodySmall) }
            }

            if (config.type == ProxyType.HTTPS_JUMP) {
              item { Text(stringResource(R.string.https_jump_route), style = MaterialTheme.typography.bodySmall) }
              item { HorizontalDivider() }
              item { Text(stringResource(R.string.https_jump_proxy), style = MaterialTheme.typography.titleMedium) }
              item {
                OutlinedTextField(config.jumpHost, { value -> acceptText(value, 253) { updateConfig(config.copy(jumpHost = it)) } }, label = { FieldLabel(stringResource(R.string.https_jump_hostname)) }, isError = fieldError(R.string.validation_jump_host, R.string.validation_jump_host_format) != null, supportingText = fieldError(R.string.validation_jump_host, R.string.validation_jump_host_format)?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
              }
              item {
                IntegerInputField(jumpPortText, { jumpPortText = it }, 1..65535, stringResource(R.string.jump_port),
                    onValidValue = { updateConfig(config.copy(jumpPort = it)) }, modifier = Modifier.fillMaxWidth())
              }
              item {
                SettingCheckboxRow(config.sameJumpAuthentication, stringResource(R.string.https_jump_same_auth), stringResource(R.string.https_jump_same_auth_description)) {
                    updateConfig(config.copy(sameJumpAuthentication = it))
                }
              }
              if (!config.sameJumpAuthentication) {
                item {
                    OutlinedTextField(config.jumpUsername, { value -> acceptText(value, 4_096) { updateConfig(config.copy(jumpUsername = it)) } }, label = { FieldLabel(stringResource(R.string.https_jump_username)) }, isError = fieldError(R.string.validation_jump_ssh_username, R.string.validation_jump_basic_username) != null, supportingText = fieldError(R.string.validation_jump_ssh_username, R.string.validation_jump_basic_username)?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    PasswordField(config.jumpPassword, { value -> acceptText(value, 16_384) { updateConfig(config.copy(jumpPassword = it)) } }, label = stringResource(R.string.https_jump_password),
                    error = fieldError(R.string.validation_jump_basic_password), modifier = Modifier.fillMaxWidth())
                }
              }
              item {
                SettingCheckboxRow(config.jumpAllowInvalidProxyCertificate, stringResource(R.string.allow_jump_proxy_certificate), stringResource(R.string.allow_jump_proxy_certificate_description)) { checked ->
                    if (checked) { invalidCertificateIsJump = true; showInvalidCertificateWarning = true }
                    else updateConfig(config.copy(jumpAllowInvalidProxyCertificate = false))
                }
              }
            }

            if (config.type == ProxyType.SSH_JUMP) {
              item {
                HorizontalDivider()
              }
              item {
                Text(stringResource(R.string.jump_host), style = MaterialTheme.typography.titleMedium)
              }
              item {
                OutlinedTextField(config.jumpHost, { value -> acceptText(value, 253) { updateConfig(config.copy(jumpHost = it)) } }, label = { FieldLabel(stringResource(R.string.jump_ssh_hostname)) }, isError = fieldError(R.string.validation_jump_host, R.string.validation_jump_host_format) != null, supportingText = fieldError(R.string.validation_jump_host, R.string.validation_jump_host_format)?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
              }
              item {
                IntegerInputField(jumpPortText, { jumpPortText = it }, 1..65535, stringResource(R.string.jump_port),
                    onValidValue = { updateConfig(config.copy(jumpPort = it)) }, modifier = Modifier.fillMaxWidth())
              }
              item {
                SettingCheckboxRow(config.sameJumpAuthentication, activity.uiText(R.string.ssh_same_auth), activity.uiText(R.string.ssh_same_auth_help)) {
                    updateConfig(config.copy(sameJumpAuthentication = it))
                }
              }
                if (!config.sameJumpAuthentication) {
                  item {
                    OutlinedTextField(config.jumpUsername, { value -> acceptText(value, 4_096) { updateConfig(config.copy(jumpUsername = it)) } }, label = { FieldLabel(stringResource(R.string.jump_ssh_username)) }, isError = fieldError(R.string.validation_jump_ssh_username, R.string.validation_jump_basic_username) != null, supportingText = fieldError(R.string.validation_jump_ssh_username, R.string.validation_jump_basic_username)?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                  }
                  item {
                    PasswordField(config.jumpPassword, { value -> acceptText(value, 16_384) { updateConfig(config.copy(jumpPassword = it)) } }, label = stringResource(R.string.jump_ssh_password_optional),
                    error = fieldError(R.string.validation_jump_basic_password), modifier = Modifier.fillMaxWidth())
                  }
                  item {
                    OutlinedTextField(config.jumpPrivateKey, { value -> acceptText(value, 64 * 1024) { updateConfig(config.copy(jumpPrivateKey = it)) } }, label = { FieldLabel(stringResource(R.string.jump_private_key_optional)) }, minLines = 3, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                  }
                  item {
                    OutlinedButton(shape = RoundedCornerShape(12.dp),
                        enabled = !importingKey, onClick = { jumpKeyPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.import_jump_key)) }
                  }
                    if (config.jumpPassword.isBlank() && config.jumpPrivateKey.isBlank()) {
                      item {
                        Text(
                            activity.uiText(R.string.ssh_jump_no_credentials),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                      }
                    }
                }
              item {
                SettingCheckboxRow(config.jumpAcceptAnyHostKey, activity.uiText(R.string.ssh_accept_jump), activity.uiText(R.string.ssh_accept_jump_help)) { checked ->
                    if (checked) unsafeHostKeyHop = "jump" else updateConfig(config.copy(jumpAcceptAnyHostKey = false))
                }
              }
                if (config.jumpTrustedHostKey.isNotBlank()) item { Text(stringResource(R.string.trusted_jump_key, config.jumpTrustedHostKey), style = MaterialTheme.typography.bodySmall) }
            }

          item {
            SettingCheckboxRow(config.allowIpv6, activity.uiText(R.string.ipv6_destinations), activity.uiText(R.string.ipv6_destinations_help)) {
                updateConfig(config.copy(allowIpv6 = it))
            }
          }

          item {
            Text(stringResource(R.string.dns_over_https), style = MaterialTheme.typography.titleMedium)
          }
          item {
            ExposedDropdownMenuBox(dnsExpanded, { dnsExpanded = it }) {
                OutlinedTextField(activity.uiText(config.dnsProvider.titleRes), {}, readOnly = true, label = { FieldLabel(stringResource(R.string.dns_over_https)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dnsExpanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                DropdownMenu(dnsExpanded, { dnsExpanded = false }) {
                    DnsProvider.entries.forEach { provider -> DropdownMenuItem(text = { Text(activity.uiText(provider.titleRes)) }, onClick = { updateConfig(config.copy(dnsProvider = provider)); dnsExpanded = false }) }
                }
            }
          }
            if (config.dnsProvider == DnsProvider.CUSTOM) item { OutlinedTextField(config.customDohUrl, { value -> acceptText(value, 2_048) { updateConfig(config.copy(customDohUrl = it)) } }, label = { FieldLabel(stringResource(R.string.custom_doh_url)) }, isError = fieldError(R.string.validation_doh_url) != null, supportingText = fieldError(R.string.validation_doh_url)?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth()) }

          item {
            Text(stringResource(if (isNew && !editorState.persisted) R.string.draft_profile_hint else R.string.changes_saved_automatically), style = MaterialTheme.typography.bodySmall)
          }
        }
    }

    if (showInvalidCertificateWarning) {
        AlertDialog(
            onDismissRequest = { showInvalidCertificateWarning = false },
            title = { DialogTitle(stringResource(R.string.allow_untrusted_certificate_title)) },
            text = { ScrollableDialogText(stringResource(if (invalidCertificateIsJump) R.string.allow_untrusted_jump_certificate_message else R.string.allow_untrusted_certificate_message)) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = {
                updateConfig(if (invalidCertificateIsJump) config.copy(jumpAllowInvalidProxyCertificate = true) else config.copy(allowInvalidProxyCertificate = true))
                showInvalidCertificateWarning = false
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { showInvalidCertificateWarning = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    unsafeHostKeyHop?.let { hop ->
        AlertDialog(
            onDismissRequest = { unsafeHostKeyHop = null },
            title = { DialogTitle(stringResource(R.string.accept_any_ssh_key_title)) },
            text = { ScrollableDialogText(stringResource(R.string.accept_any_ssh_key_message, activity.sshHopLabel(hop))) },
            confirmButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { updateConfig(if (hop == "jump") config.copy(jumpAcceptAnyHostKey = true) else config.copy(acceptAnyHostKey = true)); unsafeHostKeyHop = null }) { Text(stringResource(R.string.accept_any_key)) } },
            dismissButton = { TextButton(shape = RoundedCornerShape(12.dp), onClick = { unsafeHostKeyHop = null }) { Text(stringResource(R.string.cancel)) } },
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
