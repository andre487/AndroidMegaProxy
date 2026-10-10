package net.megaproxy487

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.megaproxy487.uiStringResource as stringResource

internal class UpdatesViewModel @JvmOverloads constructor(
    application: Application,
    private val updates: AppUpdates = AppUpdates(application),
    private val checkUpdate: suspend (UpdateSource) -> AppUpdate? = updates::check,
    private val downloadUpdate: suspend (AppUpdate) -> File = updates::download,
) : AndroidViewModel(application) {
    var backgroundTime by mutableStateOf(UpdatePreferences(application).lastBackgroundTime)
        private set
    var backgroundResult by mutableStateOf(UpdatePreferences(application).lastBackgroundResult)
        private set
    var backgroundSource by mutableStateOf(UpdatePreferences(application).lastBackgroundSource)
        private set
    var automatic by mutableStateOf(UpdatePreferences(application).automatic)
        private set
    var source by mutableStateOf(updates.source())
        private set
    var busy by mutableStateOf(false)
        private set
    var update by mutableStateOf<AppUpdate?>(null)
        private set
    var apk by mutableStateOf<File?>(null)
        private set
    var message by mutableStateOf<Int?>(null)
        private set

    fun select(value: UpdateSource) {
        if (busy) return
        updates.select(value)
        UpdateNotifications.cancel(getApplication())
        UpdateNotifications.schedule(getApplication())
        source = value
        update = null
        apk = null
        message = null
    }

    private var handledCheckRequest = 0

    fun checkFromNotification(request: Int) {
        if (request <= 0 || request == handledCheckRequest) return
        handledCheckRequest = request
        check()
    }

    fun check() {
        val selected = source ?: return
        if (busy) return
        update = null
        apk = null
        runOperation {
            update = checkUpdate(selected)
            UpdatePreferences(getApplication()).detected(selected, update)
            if (update == null) message = R.string.update_current
        }
    }

    fun download() {
        val candidate = update ?: return
        if (busy || candidate.source != UpdateSource.GITHUB) return
        runOperation { apk = downloadUpdate(candidate) }
    }

    fun automatic(value: Boolean) {
        UpdatePreferences(getApplication()).automatic = value
        automatic = value
        UpdateNotifications.schedule(getApplication())
    }

    fun refresh() {
        val prefs = UpdatePreferences(getApplication())
        automatic = prefs.automatic
        backgroundTime = prefs.lastBackgroundTime
        backgroundResult = prefs.lastBackgroundResult
        backgroundSource = prefs.lastBackgroundSource
    }

    fun reportOpenError() { message = R.string.update_open_error }

    private fun runOperation(operation: suspend () -> Unit) {
        busy = true
        message = null
        viewModelScope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = (error as? UiException)?.textId ?: R.string.update_failed }
            finally { busy = false }
        }
    }
}

@Composable
internal fun UpdatesScreen(activity: Activity, onBack: () -> Unit, model: UpdatesViewModel = viewModel(), checkRequest: Int = 0) {
    val lifecycle = LocalLifecycleOwner.current
    var notificationsEnabled by remember { mutableStateOf(UpdateNotifications.enabled(activity)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.refresh()
                notificationsEnabled = UpdateNotifications.enabled(activity)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(checkRequest) {
        model.checkFromNotification(checkRequest)
    }
    var consent by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(onBack, stringResource(R.string.updates)) {
        Text(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.update_manual_description))
        if (model.backgroundTime == 0L) Text(stringResource(R.string.update_background_never))
        else {
            val time = formatConnectionStartedAt(model.backgroundTime, systemFormattingLocale())
            Text(stringResource(R.string.update_background_time, time))
            val label = if (model.backgroundSource == UpdateSource.FDROID.name) R.string.update_fdroid else R.string.update_github
            Text(stringResource(R.string.update_selected_source, stringResource(label)))
            val result = when (model.backgroundResult) {
                "running" -> R.string.update_background_running
                "current" -> R.string.update_current
                "available" -> R.string.update_background_available
                "network_error" -> R.string.update_background_retry
                "interrupted" -> R.string.update_background_interrupted
                else -> R.string.update_background_error
            }
            Text(stringResource(result))
        }
        Text(stringResource(R.string.update_automatic))
        Switch(checked = model.automatic, onCheckedChange = model::automatic,
            modifier = Modifier.semantics { contentDescription = activity.uiText(R.string.update_automatic) })
        if (!notificationsEnabled) {
            Text(stringResource(R.string.update_notifications_disabled))
            TextButton(onClick = {
                try {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                } catch (_: Exception) { model.reportOpenError() }
            }) { Text(stringResource(R.string.update_allow_notifications)) }
        }
        Text(stringResource(R.string.update_source), style = MaterialTheme.typography.titleMedium)
        if (model.source == null) Text(stringResource(R.string.update_choose_source))
        UpdateSource.entries.forEach { source ->
            val label = stringResource(if (source == UpdateSource.FDROID) R.string.update_fdroid else R.string.update_github)
            TextButton(
                onClick = { model.select(source) }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (model.source == source) stringResource(R.string.update_selected_source, label) else label)
            }
        }
        Button(onClick = model::check, enabled = model.source != null && !model.busy) {
            Text(stringResource(R.string.update_check))
        }
        if (model.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_working))
        }
        model.message?.let { Text(stringResource(it)) }
        model.update?.let { update ->
            Text(stringResource(R.string.update_available, update.version), style = MaterialTheme.typography.titleMedium)
            if (update.source == UpdateSource.FDROID) {
                Button(onClick = {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_APP_URL)))
                    } catch (_: Exception) { model.reportOpenError() }
                }) { Text(stringResource(R.string.update_in_fdroid)) }
            } else if (model.apk == null) {
                Button(onClick = { consent = true }, enabled = !model.busy) {
                    Text(stringResource(R.string.update_download))
                }
            } else {
                Text(stringResource(R.string.update_ready))
                Text(stringResource(R.string.update_install_permission))
                Button(onClick = {
                    try {
                        if (!activity.packageManager.canRequestPackageInstalls()) {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")))
                        } else {
                            val apk = requireNotNull(model.apk)
                            require(apk.isFile)
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
                            activity.startActivity(Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    } catch (_: Exception) { model.reportOpenError() }
                }, enabled = !model.busy) { Text(stringResource(R.string.update_install)) }
            }
        }
    }
    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { DialogTitle(stringResource(R.string.update_download)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.update_github_consent))
            } },
            confirmButton = { TextButton(onClick = { consent = false; model.download() }) {
                Text(stringResource(R.string.update_download))
            } },
            dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
