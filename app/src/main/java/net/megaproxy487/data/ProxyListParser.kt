package net.megaproxy487.data

import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi

import net.megaproxy487.model.ProxyConfig
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ImportedProxy(val name: String, val countryCode: String, val config: ProxyConfig)
data class ProxyListImportResult(val proxies: List<ImportedProxy>, val skippedNonHttps: Int)

object ProxyListParser {
    fun parse(text: String): Result<ProxyListImportResult> = runCatching {
        val lines = text.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .take(MAX_IMPORTED_PROFILES + 1)
            .toList()
        requireUi(lines.isNotEmpty()) { UiException(R.string.error_proxy_empty) }
        requireUi(lines.size <= MAX_IMPORTED_PROFILES) { UiException(R.string.error_proxy_many, MAX_IMPORTED_PROFILES) }
        var skippedNonHttps = 0
        val proxies = lines.mapIndexedNotNull { index, line ->
            val uri = runCatching { URI(line) }.getOrElse { throw UiException(R.string.error_proxy_uri, index + 1) }
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
        requireUi(uri.toString().length <= 64 * 1024) { UiException(R.string.error_proxy_long, lineNumber) }
        val host = uri.host?.takeIf(String::isNotBlank) ?: throw UiException(R.string.error_proxy_host, lineNumber)
        val userInfo = uri.rawUserInfo ?: throw UiException(R.string.error_proxy_auth, lineNumber)
        val separator = userInfo.indexOf(':')
        requireUi(separator >= 0) { UiException(R.string.error_proxy_password, lineNumber) }
        val username = decodeUriComponent(userInfo.substring(0, separator))
        val password = decodeUriComponent(userInfo.substring(separator + 1))
        requireUi(username.length <= 4_096) { UiException(R.string.error_proxy_user_long, lineNumber) }
        requireUi(password.length <= 16_384) { UiException(R.string.error_proxy_password_long, lineNumber) }
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
        .splitToSequence('&').filter(String::isNotEmpty).take(101).toList().also {
            requireUi(it.size <= 100) { UiException(R.string.error_proxy_query) }
        }.associate { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) decode(pair) to ""
            else decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun decodeUriComponent(value: String): String = decode(value.replace("+", "%2B"))
}
