package net.megaproxy487.data

import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi

import java.io.ByteArrayOutputStream
import java.io.InputStream

const val MAX_PRIVATE_KEY_FILE_BYTES = 64 * 1024

private val supportedPrivateKeyTypes = setOf(
    "OPENSSH PRIVATE KEY",
    "PRIVATE KEY",
    "RSA PRIVATE KEY",
    "EC PRIVATE KEY",
    "DSA PRIVATE KEY",
)

fun InputStream.readPrivateKeyText(): String {
    val output = ByteArrayOutputStream(8 * 1024)
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        requireUi(total <= MAX_PRIVATE_KEY_FILE_BYTES) { UiException(R.string.error_key_large) }
        output.write(buffer, 0, count)
    }

    val text = output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF").trim()
    requireUi(text.isNotEmpty()) { UiException(R.string.error_key_empty) }
    requireUi(!text.contains("BEGIN ENCRYPTED PRIVATE KEY") &&
        !text.contains(Regex("Proc-Type:\\s*4,ENCRYPTED", RegexOption.IGNORE_CASE))) {
        UiException(R.string.error_key_encrypted)
    }

    val begin = Regex("^-----BEGIN ([A-Z0-9 ]+)-----", RegexOption.MULTILINE).find(text)
        ?: throw UiException(R.string.error_key_missing)
    val type = begin.groupValues[1]
    requireUi(type in supportedPrivateKeyTypes) { UiException(R.string.error_key_format, type) }
    requireUi(text.contains("-----END $type-----")) { UiException(R.string.error_key_footer) }
    return "$text\n"
}
