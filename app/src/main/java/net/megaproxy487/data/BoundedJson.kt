package net.megaproxy487.data

import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi
import org.json.JSONObject

/** Bound allocations and recursive parser depth before org.json builds its object tree. */
internal fun boundedJsonObject(text: String): JSONObject {
    requireUi(text.length <= MAX_CONFIG_FILE_BYTES) { UiException(R.string.error_config_large) }
    var depth = 0
    var tokens = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else {
            when (character) {
                '"' -> quoted = true
                '[', '{' -> {
                    depth++
                    tokens++
                }
                ']', '}' -> depth--
                ',', ':' -> tokens++
                // Android's lenient parser also accepts comments and single quotes.
                // Reject those extensions so they cannot bypass this JSON guard.
                '\'', '/', '#' -> throw UiException(R.string.error_invalid_input)
            }
            requireUi(depth in 0..32 && tokens <= 250_000) {
                UiException(R.string.error_config_complex)
            }
        }
    }
    return JSONObject(text)
}
