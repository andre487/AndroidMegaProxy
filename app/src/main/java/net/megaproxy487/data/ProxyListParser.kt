package net.megaproxy487.data

import net.megaproxy487.model.ProxyConfig
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ImportedProxy(val name: String, val countryCode: String, val config: ProxyConfig)
data class ProxyListImportResult(val proxies: List<ImportedProxy>, val skippedNonHttps: Int)

object ProxyListParser {
    fun parse(text: String): Result<ProxyListImportResult> = runCatching {
        val lines = text.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        require(lines.isNotEmpty()) { "The proxy list is empty" }
        var skippedNonHttps = 0
        val proxies = lines.mapIndexedNotNull { index, line ->
            val uri = runCatching { URI(line) }.getOrElse { error("Line ${index + 1} is not a valid URI") }
            if (!uri.scheme.equals("https", ignoreCase = true)) {
                skippedNonHttps++
                null
            } else {
                parseLine(uri, index + 1)
            }
        }
        ProxyListImportResult(proxies, skippedNonHttps)
    }

    private fun parseLine(uri: URI, lineNumber: Int): ImportedProxy {
        val host = uri.host?.takeIf(String::isNotBlank) ?: error("Line $lineNumber has no proxy host")
        val userInfo = uri.rawUserInfo ?: error("Line $lineNumber has no Basic Auth credentials")
        val separator = userInfo.indexOf(':')
        require(separator >= 0) { "Line $lineNumber has no Basic Auth password" }
        val username = decodeUriComponent(userInfo.substring(0, separator))
        val password = decodeUriComponent(userInfo.substring(separator + 1))
        val query = parseQuery(uri.rawQuery)
        return ImportedProxy(
            name = query["title"].orEmpty(),
            countryCode = query["cc"].orEmpty().uppercase()
                .takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(),
            config = ProxyConfig(
                host = host,
                port = if (uri.port == -1) 443 else uri.port,
                username = username,
                password = password,
            ),
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
        .split('&').filter(String::isNotEmpty).associate { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) decode(pair) to ""
            else decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun decodeUriComponent(value: String): String = decode(value.replace("+", "%2B"))
}
