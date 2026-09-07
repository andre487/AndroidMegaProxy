package net.megaproxy487

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.megaproxy487.data.ConfigWrites

@Composable
internal fun SaveStatusBanner() {
    val status by ConfigWrites.status.collectAsState()
    if (status.pending > 0 || status.failed) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(uiStringResource(if (status.failed) R.string.save_failed else R.string.saving_changes),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.failed) TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), onClick = { ConfigWrites.retry() }, enabled = status.pending == 0) {
                Text(uiStringResource(R.string.retry_save))
            }
        }
    }
}

@Composable
internal fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String,
    error: String? = null, modifier: Modifier = Modifier) {
    // Never persist either the secret or its visible state in an Android saved-state bundle.
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValueChange, label = { FieldLabel(label) }, singleLine = true,
        modifier = modifier, isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { visible = !visible }) {
            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                uiStringResource(if (visible) R.string.hide_password else R.string.show_password))
        } })
}

@Composable
internal fun IntegerInputField(text: String, onTextChange: (String) -> Unit, range: IntRange,
    label: String, onValidValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    val valid = validIntegerInput(text, range) != null
    OutlinedTextField(text, { value ->
        onTextChange(value)
        validIntegerInput(value, range)?.let(onValidValue)
    }, label = { FieldLabel(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
        supportingText = if (!valid) { { Text(uiStringResource(R.string.integer_not_saved, range.first, range.last)) } } else null)
}
