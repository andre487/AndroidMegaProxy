package net.megaproxy487.data

import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi

object SuperProxyParser {
    const val HEADER = "# superproxy:proxylist:v1"

    fun matches(text: String): Boolean =
        text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) == HEADER

    fun parse(text: String): Result<ProxyListImportResult> = runCatching {
        requireUi(matches(text)) { UiException(R.string.error_super_invalid) }
        // Super Proxy's optional `fingerprint` query parameter is a certificate pin.
        // MegaProxy intentionally relies on Android's trust store and does not import pins.
        ProxyListParser.parse(text).getOrThrow()
    }
}
