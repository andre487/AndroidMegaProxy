package net.megaproxy487.data

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
        require(total <= MAX_PRIVATE_KEY_FILE_BYTES) { "Private key file is larger than 64 KiB" }
        output.write(buffer, 0, count)
    }

    val text = output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF").trim()
    require(text.isNotEmpty()) { "Private key file is empty" }
    require(!text.contains("BEGIN ENCRYPTED PRIVATE KEY") &&
        !text.contains(Regex("Proc-Type:\\s*4,ENCRYPTED", RegexOption.IGNORE_CASE))) {
        "Passphrase-protected private keys are not supported"
    }

    val begin = Regex("^-----BEGIN ([A-Z0-9 ]+)-----", RegexOption.MULTILINE).find(text)
        ?: throw IllegalArgumentException("File does not contain a PEM or OpenSSH private key")
    val type = begin.groupValues[1]
    require(type in supportedPrivateKeyTypes) { "Unsupported private key format: $type" }
    require(text.contains("-----END $type-----")) { "Private key footer is missing" }
    return "$text\n"
}
