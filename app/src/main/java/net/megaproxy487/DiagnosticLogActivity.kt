package net.megaproxy487

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.PersistentDiagnosticLog

class DiagnosticLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DiagnosticLogScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticLogScreen(activity: Activity) {
    val viewerWindowBytes = 512 * 1024
    val store = remember { ConfigStore(activity) }
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var autoScroll by remember { mutableStateOf(true) }
    var limitText by remember { mutableStateOf(store.diagnosticLogLimitMb().toString()) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val isAtBottom by remember {
        derivedStateOf {
            lines.isEmpty() || listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == lines.lastIndex
        }
    }

    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        activity.contentResolver.openOutputStream(uri, "wt")?.buffered()?.use {
                            PersistentDiagnosticLog.copyTo(it)
                        } ?: error("Could not open the export file")
                    }
                }.onSuccess { message = "Diagnostic log exported." }
                    .onFailure { message = it.message ?: "Could not export the diagnostic log" }
            }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val content = withContext(Dispatchers.IO) { PersistentDiagnosticLog.readTail(viewerWindowBytes) }
                lines = content.lineSequence().filter(String::isNotEmpty).toList()
                delay(1_000)
            }
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) autoScroll = false
        }
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) autoScroll = true
    }
    LaunchedEffect(lines.size, autoScroll) {
        if (autoScroll && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic log") },
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
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = limitText,
                onValueChange = { value ->
                    if (value.all(Char::isDigit) && value.length <= 3) {
                        limitText = value
                        value.toIntOrNull()?.takeIf {
                            it in PersistentDiagnosticLog.MIN_LIMIT_MB..PersistentDiagnosticLog.MAX_LIMIT_MB
                        }?.let {
                            store.setDiagnosticLogLimitMb(it)
                            PersistentDiagnosticLog.setLimitMb(it)
                        }
                    }
                },
                label = { Text("Rotated log limit (MB)") },
                supportingText = { Text("Total size across both log segments; 1–100 MB.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportDocument.launch("MegaProxy-diagnostic.log") }, modifier = Modifier.weight(1f)) {
                    Text("Export")
                }
                OutlinedButton(onClick = { showClearConfirmation = true }, modifier = Modifier.weight(1f)) {
                    Text("Clear")
                }
            }
            Text(
                buildString {
                    append(if (autoScroll) "Auto-scroll on" else "Auto-scroll paused · scroll to the bottom to resume")
                    append(" · showing the latest 512 KB")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                if (lines.isEmpty()) item { Text("No diagnostic events yet.") }
                itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear diagnostic log?") },
            text = { Text("Both rotated log segments will be deleted.") },
            confirmButton = { TextButton(onClick = {
                showClearConfirmation = false
                PersistentDiagnosticLog.clear()
            }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}
