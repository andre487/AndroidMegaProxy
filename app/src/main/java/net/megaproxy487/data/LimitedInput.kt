package net.megaproxy487.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

const val MAX_CONFIG_FILE_BYTES = 4 * 1024 * 1024
const val MAX_IMPORTED_PROFILES = 1_000
const val MAX_IMPORTED_PACKAGES = 10_000

fun InputStream.readConfigText(): String {
    val output = ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_CONFIG_FILE_BYTES) { "Configuration file is larger than 4 MB" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
