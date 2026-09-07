package net.megaproxy487.data

import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi

import net.megaproxy487.model.ProxyConfig
import org.json.JSONObject

object FoxyProxyParser {
    fun parse(text: String): Result<ProxyListImportResult> = runCatching {
        val root = JSONObject(text)
        requireUi(!ConfigTransfer.isSupportedSchema(root.optString("schema"))) {
            UiException(R.string.error_foxy_wrong)
        }
        val data = root.optJSONArray("data")
            ?: throw UiException(R.string.error_foxy_data)
        requireUi(data.length() > 0) { UiException(R.string.error_foxy_empty) }
        requireUi(data.length() <= MAX_IMPORTED_PROFILES) {
            UiException(R.string.error_foxy_many, MAX_IMPORTED_PROFILES)
        }

        var skippedNonHttps = 0
        val proxies = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val type = item.optString("type").trim().lowercase()
                if (type !in setOf("https", "ssl")) {
                    skippedNonHttps++
                    continue
                }
                parseProxy(item, index + 1)?.let(::add)
            }
        }
        requireUi(proxies.isNotEmpty()) {
            if (skippedNonHttps > 0) UiException(R.string.error_foxy_no_https)
            else UiException(R.string.error_foxy_no_usable)
        }
        ProxyListImportResult(proxies, skippedNonHttps)
    }

    private fun parseProxy(item: JSONObject, position: Int): ImportedProxy? {
        val host = item.limitedString("hostname", 253).trim().ifEmpty {
            item.limitedString("address", 253).trim()
        }
        requireUi(host.isNotEmpty() && !host.contains(Regex("[/:\\s]"))) {
            UiException(R.string.error_foxy_host, position)
        }
        val port = when (val value = item.opt("port")) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.takeIf { it in 1..65535 } ?: 443
        val countryCode = item.optString("cc").trim().uppercase()
            .takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
        return ImportedProxy(
            name = item.limitedString("title", 256).trim(),
            countryCode = countryCode,
            config = ProxyConfig(
                host = host,
                port = port,
                username = item.limitedString("username", 4_096),
                password = item.limitedString("password", 16_384),
            ),
        )
    }

    private fun JSONObject.limitedString(name: String, maxLength: Int): String = optString(name).also {
        requireUi(it.length <= maxLength) { UiException(R.string.error_field_long, name) }
    }
}
