package net.megaproxy487.data

import net.megaproxy487.model.ProxyConfig
import org.json.JSONObject

object FoxyProxyParser {
    fun parse(text: String): Result<ProxyListImportResult> = runCatching {
        val root = JSONObject(text)
        require(root.optString("schema") != "dev.megaproxy.config") {
            "This is a MegaProxy configuration, not a FoxyProxy configuration"
        }
        val data = root.optJSONArray("data")
            ?: error("The FoxyProxy configuration has no data array")
        require(data.length() > 0) { "The FoxyProxy proxy list is empty" }

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
        require(proxies.isNotEmpty()) {
            if (skippedNonHttps > 0) "The FoxyProxy configuration contains no HTTPS proxies"
            else "The FoxyProxy configuration contains no usable proxies"
        }
        ProxyListImportResult(proxies, skippedNonHttps)
    }

    private fun parseProxy(item: JSONObject, position: Int): ImportedProxy? {
        val host = item.optString("hostname").trim().ifEmpty {
            item.optString("address").trim()
        }
        require(host.isNotEmpty() && !host.contains(Regex("[/:\\s]"))) {
            "FoxyProxy entry $position has an invalid hostname"
        }
        val port = when (val value = item.opt("port")) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.takeIf { it in 1..65535 } ?: 443
        val countryCode = item.optString("cc").trim().uppercase()
            .takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
        return ImportedProxy(
            name = item.optString("title").trim(),
            countryCode = countryCode,
            config = ProxyConfig(
                host = host,
                port = port,
                username = item.optString("username"),
                password = item.optString("password"),
            ),
        )
    }
}
