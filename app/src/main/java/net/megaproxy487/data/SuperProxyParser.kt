package net.megaproxy487.data

object SuperProxyParser {
    const val HEADER = "# superproxy:proxylist:v1"

    fun matches(text: String): Boolean =
        text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) == HEADER

    fun parse(text: String): Result<ProxyListImportResult> = runCatching {
        require(matches(text)) { "This is not a supported Super Proxy configuration file" }
        // Super Proxy's optional `fingerprint` query parameter is a certificate pin.
        // MegaProxy intentionally relies on Android's trust store and does not import pins.
        ProxyListParser.parse(text).getOrThrow()
    }
}
