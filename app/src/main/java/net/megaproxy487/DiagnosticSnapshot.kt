package net.megaproxy487

import java.io.IOException

/** A temporary storage failure keeps the last snapshot visible and allows the next poll to retry. */
internal fun readDiagnosticSnapshot(read: () -> String): List<String>? = try {
    read().lineSequence().filter(String::isNotEmpty).toList()
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
