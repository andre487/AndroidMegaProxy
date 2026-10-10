package net.megaproxy487

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.withContext
import net.megaproxy487.data.*
import net.megaproxy487.uiStringResource as stringResource
import java.text.DateFormat
import java.util.Date

/** In-memory drafts survive rotation, without writing URLs/tokens into saved-state bundles. */
internal class SubscriptionDraft : ViewModel() {
    var loaded by mutableStateOf(false)
    var url by mutableStateOf("")
    var backups by mutableStateOf("")
    var authenticated by mutableStateOf(false)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var interval by mutableStateOf("60")
    var enabled by mutableStateOf(true)
    var invalid by mutableStateOf(false)

    fun load(settings: ConfigSubscription?) {
        if (loaded) return
        loaded = true
        url = settings?.url.orEmpty()
        backups = settings?.fallbackUrls?.joinToString("\n").orEmpty()
        authenticated = settings?.let { it.username != null || it.password != null } == true
        username = settings?.username.orEmpty()
        password = settings?.password.orEmpty()
        interval = (settings?.intervalMinutes ?: 60).toString()
        enabled = settings?.enabled ?: true
    }

    fun settings(): ConfigSubscription = ConfigSubscription(url.trim(),
        backups.lineSequence().map(String::trim).filter(String::isNotEmpty).toList(),
        username.takeIf { authenticated }, password.takeIf { authenticated },
        requireNotNull(interval.toIntOrNull()), enabled).validate()
}

@Composable
internal fun ConfigSubscriptionScreen(activity: Activity, onBack: () -> Unit, draft: SubscriptionDraft = viewModel()) {
    val revision by ConfigSubscriptions.revision.collectAsState()
    val busy by ConfigSubscriptions.busy.collectAsState()
    val operationError by ConfigSubscriptions.error.collectAsState()
    var state by remember { mutableStateOf<ConfigSubscriptionState?>(null) }
    var readError by remember { mutableStateOf(false) }
    var pendingReconnect by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        val result = withContext(ConfigIoDispatcher) { operationResult {
            val store = ConfigStore(activity)
            store.subscriptionState() to store.hasPendingReconnect()
        } }
        readError = result.isFailure
        result.onSuccess {
            state = it.first
            pendingReconnect = it.second
            draft.load(it.first?.settings)
        }
    }
    SettingsScaffold(onBack, stringResource(R.string.subscription)) {
            Text(stringResource(R.string.subscription_trust), style = MaterialTheme.typography.bodyMedium)
            if (readError || operationError) Text(stringResource(R.string.subscription_storage_error), color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state != null) {
                val enabledLabel = stringResource(R.string.subscription_enabled)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(enabledLabel, Modifier.weight(1f))
                    Switch(state!!.settings.enabled, { enabled ->
                        draft.enabled = enabled
                        ConfigSubscriptions.configure(activity, state!!.settings.copy(enabled = enabled))
                    }, modifier = Modifier.semantics { contentDescription = enabledLabel })
                }
                state!!.lastSuccess.takeIf { it > 0 }?.let { timestamp ->
                    Text(stringResource(R.string.subscription_last_success, DateFormat.getDateTimeInstance().format(Date(timestamp))))
                    Text(stringResource(R.string.subscription_source, state!!.sourceIndex + 1))
                }
                if (state!!.failed) Text(stringResource(R.string.subscription_failed), color = MaterialTheme.colorScheme.error)
                if (state!!.warnings) Text(stringResource(R.string.subscription_warnings))
                if (pendingReconnect) Text(stringResource(R.string.subscription_reconnect))
                Button(onClick = { ConfigSubscriptions.refresh(activity) }, enabled = !busy) {
                    Text(stringResource(R.string.subscription_refresh))
                }
            }
            if (!readError && draft.loaded) {
                OutlinedTextField(draft.url, { draft.url = it }, label = { FieldLabel(stringResource(R.string.subscription_url)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(draft.backups, { draft.backups = it }, label = { FieldLabel(stringResource(R.string.subscription_backups)) },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(draft.authenticated, { draft.authenticated = it }, enabled = !busy)
                    Text(stringResource(R.string.subscription_auth), Modifier.weight(1f))
                }
                if (draft.authenticated) {
                    OutlinedTextField(draft.username, { draft.username = it }, label = { FieldLabel(stringResource(R.string.basic_auth_username)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                    PasswordField(draft.password, { draft.password = it }, stringResource(R.string.password), modifier = Modifier.fillMaxWidth())
                }
                IntegerInputField(draft.interval, { draft.interval = it }, 1..10080,
                    stringResource(R.string.subscription_interval), {}, Modifier.fillMaxWidth())
                if (draft.invalid) Text(stringResource(R.string.subscription_invalid), color = MaterialTheme.colorScheme.error)
                Button(enabled = !busy, onClick = {
                    val settings = operationResult(draft::settings)
                    draft.invalid = settings.isFailure
                    settings.onSuccess { ConfigSubscriptions.configure(activity, it) }
                }) { Text(stringResource(R.string.subscription_save)) }
            }
            if (state != null || readError) TextButton(onClick = {
                ConfigSubscriptions.configure(activity, null)
                draft.loaded = false
            }, enabled = !busy) { Text(stringResource(R.string.subscription_remove)) }
    }
}
