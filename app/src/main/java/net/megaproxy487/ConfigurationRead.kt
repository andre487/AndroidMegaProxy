package net.megaproxy487

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.operationResult

/** Cold storage/Keystore reads must not block composition or expose editable defaults. */
@Composable
internal fun <T : Any> readConfiguration(key: Any, read: () -> T): T? {
    val result by produceState<Result<T>?>(null, key) {
        value = withContext(ConfigIoDispatcher) { operationResult(read) }
    }
    if (result == null) Text(stringResource(R.string.loading_configuration))
    else if (result!!.isFailure) Text(stringResource(R.string.error_config_storage))
    return result?.getOrNull()
}
