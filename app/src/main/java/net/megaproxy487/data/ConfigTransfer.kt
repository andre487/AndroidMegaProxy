package net.megaproxy487.data

import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProfileSort
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.TlsProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ConfigExportFormat(val extension: String, val mimeType: String) {
    PROXY_LIST("txt", "text/plain"),
    JSON("json", "application/json"),
}

data class PortableConfiguration(
    val profiles: List<ProxyProfile>,
    val activeProfileId: String?,
    val alwaysOnProfileId: String?,
    val sort: ProfileSort,
    val sortAscending: Boolean,
    val diagnosticLogLimitMb: Int = 3,
    val skippedProfiles: Int = 0,
)

object ConfigTransfer {
    const val SCHEMA_VERSION = 1

    fun exportProxyList(profiles: List<ProxyProfile>, includePasswords: Boolean): String =
        profiles.joinToString("\n", postfix = "\n") { profile ->
            val config = profile.config
            val password = if (includePasswords) config.password else ""
            val userInfo = "${encode(config.username)}:${encode(password)}"
            val query = buildList {
                if (profile.name.isNotBlank()) add("title=${encode(profile.name.trim())}")
                if (profile.countryCode.isNotBlank()) add("cc=${encode(profile.countryCode.uppercase())}")
            }.joinToString("&")
            buildString {
                append("https://").append(userInfo).append('@').append(config.host.trim())
                if (config.port != 443) append(':').append(config.port)
                if (query.isNotEmpty()) append('?').append(query)
            }
        }

    fun exportJson(store: ConfigStore, includePasswords: Boolean): String = JSONObject().apply {
        put("schema", "dev.megaproxy.config")
        put("version", SCHEMA_VERSION)
        put("passwordsIncluded", includePasswords)
        put("activeProfileId", store.activeProfileId())
        put("alwaysOnProfileId", store.alwaysOnProfileId())
        put("profileSort", store.profileSort().name)
        put("profileSortAscending", store.isProfileSortAscending())
        put("diagnosticLogLimitMb", store.diagnosticLogLimitMb())
        put("profiles", JSONArray().apply {
            store.profiles().forEach { profile -> put(encodeProfile(profile, includePasswords)) }
        })
    }.toString(2)

    fun importJson(text: String): PortableConfiguration {
        val root = JSONObject(text)
        require(root.optString("schema") == "dev.megaproxy.config") { "This is not a MegaProxy configuration file" }
        val version = root.optInt("version", 0)
        require(version in 1..SCHEMA_VERSION) { "Unsupported MegaProxy configuration version: $version" }
        val array = root.optJSONArray("profiles") ?: error("The configuration has no profiles")
        val decoded = (0 until array.length()).map { index ->
            runCatching { decodeProfile(array.getJSONObject(index), index) }
        }
        val profiles = decoded.mapNotNull(Result<ProxyProfile>::getOrNull)
        require(profiles.isNotEmpty()) { "The configuration contains no usable profiles" }
        return PortableConfiguration(
            profiles = profiles,
            activeProfileId = root.optString("activeProfileId").ifBlank { null },
            alwaysOnProfileId = root.optString("alwaysOnProfileId").ifBlank { null },
            sort = enumValue(root.optString("profileSort"), ProfileSort.NAME),
            sortAscending = root.optBoolean("profileSortAscending", true),
            diagnosticLogLimitMb = root.optInt("diagnosticLogLimitMb", 3).coerceIn(1, 100),
            skippedProfiles = decoded.count(Result<ProxyProfile>::isFailure),
        )
    }

    private fun encodeProfile(profile: ProxyProfile, includePasswords: Boolean) = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name.trim())
        put("color", profile.colorIndex)
        put("countryCode", profile.countryCode.uppercase())
        put("proxy", JSONObject().apply {
            put("host", profile.config.host.trim())
            put("port", profile.config.port)
            put("username", profile.config.username)
            if (includePasswords) put("password", profile.config.password)
            put("allowInvalidProxyCertificate", profile.config.allowInvalidProxyCertificate)
        })
        put("tls", JSONObject().apply {
            put("fingerprint", profile.config.profile.name)
            put("customJa3", profile.config.customJa3.trim())
        })
        put("dns", JSONObject().apply {
            put("provider", profile.config.dnsProvider.name)
            put("customDohUrl", profile.config.customDohUrl.trim())
        })
        put("routing", JSONObject().apply {
            put("routeAllApps", profile.config.routeAllApps)
            put("selectedPackages", JSONArray(profile.config.selectedPackages.sorted()))
            put("allowIpv6", profile.config.allowIpv6)
            put("bypassLocalNetworks", profile.config.bypassLocalNetworks)
        })
    }

    private fun decodeProfile(item: JSONObject, index: Int): ProxyProfile {
        val proxy = item.optJSONObject("proxy") ?: JSONObject()
        val tls = item.optJSONObject("tls") ?: JSONObject()
        val dns = item.optJSONObject("dns") ?: JSONObject()
        val routing = item.optJSONObject("routing") ?: JSONObject()
        val host = proxy.optString("host").trim()
        require(host.isNotEmpty() && !host.contains(Regex("[/:\\s]")))
        val packages = routing.optJSONArray("selectedPackages")?.let { array ->
            (0 until array.length()).mapNotNull { packageIndex ->
                array.optString(packageIndex).trim().takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
            }.toSet()
        }.orEmpty()
        return ProxyProfile(
            id = item.optString("id").ifBlank { "import-$index" },
            name = item.optString("name").trim(),
            colorIndex = item.optInt("color", index),
            countryCode = item.optString("countryCode").uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(),
            config = ProxyConfig(
                host = host,
                port = proxy.optInt("port", 443).takeIf { it in 1..65535 } ?: 443,
                username = proxy.optString("username"),
                password = proxy.optString("password"),
                allowInvalidProxyCertificate = proxy.optBoolean("allowInvalidProxyCertificate", false),
                profile = enumValue(tls.optString("fingerprint"), TlsProfile.CHROME_ANDROID),
                customJa3 = tls.optString("customJa3"),
                dnsProvider = enumValue(dns.optString("provider"), DnsProvider.CLOUDFLARE),
                customDohUrl = dns.optString("customDohUrl"),
                selectedPackages = packages,
                allowIpv6 = routing.optBoolean("allowIpv6", false),
                routeAllApps = routing.optBoolean("routeAllApps", false),
                bypassLocalNetworks = routing.optBoolean("bypassLocalNetworks", true),
            ),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}
