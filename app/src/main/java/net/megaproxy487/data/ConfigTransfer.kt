package net.megaproxy487.data

import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.SshProfile
import net.megaproxy487.model.SshAuthMode
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.model.GlobalConnectionSettings
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
    val diagnosticLogLimitMb: Int = 3,
    val globalConnectionSettings: GlobalConnectionSettings? = null,
    val skippedProfiles: Int = 0,
    val secretPresence: Map<String, ProfileSecretPresence> = emptyMap(),
)

data class ProfileSecretPresence(
    val password: Boolean = false,
    val privateKey: Boolean = false,
    val jumpPassword: Boolean = false,
    val jumpPrivateKey: Boolean = false,
)

object ConfigTransfer {
    const val SCHEMA_ID = "net.megaproxy487.config"
    const val LEGACY_SCHEMA_ID = "dev.megaproxy.config"
    const val SCHEMA_VERSION = 7

    fun isSupportedSchema(value: String): Boolean = value == SCHEMA_ID || value == LEGACY_SCHEMA_ID

    fun exportProxyList(profiles: List<ProxyProfile>, includePasswords: Boolean): String =
        profiles.filter { it.config.type == ProxyType.HTTPS }.joinToString("\n", postfix = "\n") { profile ->
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

    fun exportJson(store: ConfigStore, includePasswords: Boolean, includePrivateKeys: Boolean = false): String = JSONObject().apply {
        put("schema", SCHEMA_ID)
        put("version", SCHEMA_VERSION)
        put("passwordsIncluded", includePasswords)
        put("privateKeysIncluded", includePrivateKeys)
        put("activeProfileId", store.activeProfileId())
        put("alwaysOnProfileId", store.alwaysOnProfileId())
        put("diagnosticLogLimitMb", store.diagnosticLogLimitMb())
        val global = store.globalConnectionSettings()
        put("tls", JSONObject().apply {
            put("fingerprint", global.tlsProfile.name)
            put("customJa3", global.customJa3.trim())
        })
        put("ssh", JSONObject().apply {
            put("fingerprint", global.sshProfile.name)
            put("authMode", global.sshAuthMode.name)
            put("keepaliveSeconds", global.sshKeepaliveSeconds)
            put("maxChannels", global.sshMaxChannels)
            put("rotationMinutes", global.sshRotationMinutes)
            put("rotationMb", global.sshRotationMb)
        })
        put("failover", JSONObject().apply {
            put("mode", global.failoverMode.name)
            put("profileIds", JSONArray(global.failoverProfileIds))
        })
        put("routing", JSONObject().apply {
            put("routeAllApps", global.routeAllApps)
            put("selectedPackages", JSONArray(global.selectedPackages.sorted()))
            put("bypassLocalNetworks", global.bypassLocalNetworks)
        })
        put("profiles", JSONArray().apply {
            store.profiles().forEach { profile -> put(encodeProfile(profile, includePasswords, includePrivateKeys)) }
        })
    }.toString(2)

    fun importJson(text: String): PortableConfiguration {
        val root = JSONObject(text)
        require(isSupportedSchema(root.optString("schema"))) { "This is not a MegaProxy configuration file" }
        val version = root.optInt("version", 0)
        require(version in 1..SCHEMA_VERSION) { "Unsupported MegaProxy configuration version: $version" }
        val array = root.optJSONArray("profiles") ?: error("The configuration has no profiles")
        require(array.length() <= MAX_IMPORTED_PROFILES) { "The configuration contains more than $MAX_IMPORTED_PROFILES profiles" }
        if (version >= 7) require((0 until array.length()).all { index ->
            array.optJSONObject(index)?.optString("id")?.let { it.isNotBlank() && it.length <= 256 } == true
        }) { "Every profile in version 7 or newer must have a stable ID" }
        val decoded = (0 until array.length()).map { index ->
            runCatching { decodeProfile(array.getJSONObject(index), index) }
        }
        val decodedProfiles = decoded.mapNotNull(Result<ProxyProfile>::getOrNull)
        require(decodedProfiles.isNotEmpty()) { "The configuration contains no usable profiles" }
        require(decodedProfiles.map(ProxyProfile::id).distinct().size == decodedProfiles.size) {
            "The configuration contains duplicate profile IDs"
        }
        val legacyIpv6 = root.optJSONObject("routing")?.optBoolean("allowIpv6", false) ?: false
        val profiles = if (version in 2..5) decodedProfiles.map {
            it.copy(config = it.config.copy(allowIpv6 = legacyIpv6))
        } else decodedProfiles
        return PortableConfiguration(
            profiles = profiles,
            activeProfileId = root.optString("activeProfileId").ifBlank { null },
            alwaysOnProfileId = root.optString("alwaysOnProfileId").ifBlank { null },
            diagnosticLogLimitMb = root.optInt("diagnosticLogLimitMb", 3).coerceIn(1, 100),
            globalConnectionSettings = if (version >= 2) {
                decodeGlobalSettings(root).let { global ->
                    if (version < 4) global.copy(sshProfile = profiles.first().config.sshProfile) else global
                }
            } else null,
            skippedProfiles = decoded.count(Result<ProxyProfile>::isFailure),
            secretPresence = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { "import-$index" }
                val proxy = item.optJSONObject("proxy") ?: JSONObject()
                val jump = proxy.optJSONObject("jump")
                id to ProfileSecretPresence(
                    password = proxy.has("password"),
                    privateKey = proxy.has("privateKey"),
                    jumpPassword = jump?.has("password") == true,
                    jumpPrivateKey = jump?.has("privateKey") == true,
                )
            }.toMap(),
        )
    }

    private fun decodeGlobalSettings(root: JSONObject): GlobalConnectionSettings {
        val tls = root.optJSONObject("tls") ?: JSONObject()
        val routing = root.optJSONObject("routing") ?: JSONObject()
        val ssh = root.optJSONObject("ssh") ?: JSONObject()
        val failover = root.optJSONObject("failover") ?: JSONObject()
        val parsedTls = enumValue(tls.optString("fingerprint"), TlsProfile.DEFAULT)
        val packages = routing.optJSONArray("selectedPackages")?.let { array ->
            require(array.length() <= MAX_IMPORTED_PACKAGES) { "The configuration contains too many application package names" }
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.length <= 256 }?.trim()
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
            }.toSet()
        }.orEmpty()
        return GlobalConnectionSettings(
            tlsProfile = parsedTls.takeIf { it.available } ?: TlsProfile.DEFAULT,
            sshProfile = enumValue(ssh.optString("fingerprint"), SshProfile.DEFAULT),
            sshAuthMode = enumValue(ssh.optString("authMode"), SshAuthMode.AUTO),
            sshKeepaliveSeconds = ssh.optInt("keepaliveSeconds", 30).coerceIn(0, 3600),
            sshMaxChannels = ssh.optInt("maxChannels", 32).coerceIn(1, 256),
            sshRotationMinutes = ssh.optInt("rotationMinutes", 0).coerceIn(0, 1440),
            sshRotationMb = ssh.optInt("rotationMb", 0).coerceIn(0, 10240),
            failoverMode = enumValue(failover.optString("mode"), FailoverMode.DISABLED),
            failoverProfileIds = failover.optJSONArray("profileIds")?.let { array ->
                require(array.length() <= MAX_IMPORTED_PROFILES) { "The configuration contains too many failover profile IDs" }
                (0 until array.length()).mapNotNull {
                    array.optString(it).takeIf { id -> id.isNotBlank() && id.length <= 256 }
                }
            }.orEmpty(),
            customJa3 = tls.limitedString("customJa3", MAX_CUSTOM_JA3_LENGTH),
            selectedPackages = packages,
            routeAllApps = routing.optBoolean("routeAllApps", true),
            bypassLocalNetworks = routing.optBoolean("bypassLocalNetworks", true),
        )
    }

    private fun encodeProfile(profile: ProxyProfile, includePasswords: Boolean, includePrivateKeys: Boolean) = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name.trim())
        put("color", profile.colorIndex)
        put("countryCode", profile.countryCode.uppercase())
        put("proxy", JSONObject().apply {
            put("type", profile.config.type.name)
            put("host", profile.config.host.trim())
            put("port", profile.config.port)
            put("username", profile.config.username)
            if (includePasswords) put("password", profile.config.password)
            if (includePrivateKeys) put("privateKey", profile.config.privateKey)
            put("allowInvalidProxyCertificate", profile.config.allowInvalidProxyCertificate)
            put("sshProfile", profile.config.sshProfile.name)
            put("trustedHostKey", profile.config.trustedHostKey)
            put("acceptAnyHostKey", profile.config.acceptAnyHostKey)
            if (profile.config.type == ProxyType.SSH_JUMP) put("jump", JSONObject().apply {
                put("host", profile.config.jumpHost)
                put("port", profile.config.jumpPort)
                put("sameAuthentication", profile.config.sameJumpAuthentication)
                if (!profile.config.sameJumpAuthentication) {
                    put("username", profile.config.jumpUsername)
                    if (includePasswords) put("password", profile.config.jumpPassword)
                    if (includePrivateKeys) put("privateKey", profile.config.jumpPrivateKey)
                }
                put("trustedHostKey", profile.config.jumpTrustedHostKey)
                put("acceptAnyHostKey", profile.config.jumpAcceptAnyHostKey)
            })
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
        val host = proxy.limitedString("host", 253).trim()
        require(host.isNotEmpty() && !host.contains(Regex("[/:\\s]")))
        val jump = proxy.optJSONObject("jump")
        val packages = routing.optJSONArray("selectedPackages")?.let { array ->
            require(array.length() <= MAX_IMPORTED_PACKAGES) { "A profile contains too many application package names" }
            (0 until array.length()).mapNotNull { packageIndex ->
                array.optString(packageIndex).takeIf { it.length <= 256 }?.trim()
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
            }.toSet()
        }.orEmpty()
        return ProxyProfile(
            id = item.limitedString("id", 256).ifBlank { "import-$index" },
            name = item.limitedString("name", 256).trim(),
            colorIndex = item.optInt("color", index),
            countryCode = item.limitedString("countryCode", 2).uppercase()
                .takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(),
            config = ProxyConfig(
                type = enumValue(proxy.optString("type"), ProxyType.HTTPS),
                host = host,
                port = proxy.optInt("port", 443).takeIf { it in 1..65535 } ?: 443,
                username = proxy.limitedString("username", 4_096),
                password = proxy.limitedString("password", 16_384),
                privateKey = proxy.limitedString("privateKey", 64 * 1024),
                sshProfile = enumValue(proxy.optString("sshProfile"), SshProfile.DEFAULT),
                trustedHostKey = proxy.limitedString("trustedHostKey", 256),
                acceptAnyHostKey = proxy.optBoolean("acceptAnyHostKey", false),
                jumpHost = jump?.limitedString("host", 253).orEmpty(),
                jumpPort = jump?.optInt("port", 22) ?: 22,
                sameJumpAuthentication = jump?.optBoolean("sameAuthentication", true) ?: true,
                jumpUsername = jump?.limitedString("username", 4_096).orEmpty(),
                jumpPassword = jump?.limitedString("password", 16_384).orEmpty(),
                jumpPrivateKey = jump?.limitedString("privateKey", 64 * 1024).orEmpty(),
                jumpTrustedHostKey = jump?.limitedString("trustedHostKey", 256).orEmpty(),
                jumpAcceptAnyHostKey = jump?.optBoolean("acceptAnyHostKey", false) ?: false,
                allowInvalidProxyCertificate = proxy.optBoolean("allowInvalidProxyCertificate", false),
                profile = enumValue(tls.optString("fingerprint"), TlsProfile.DEFAULT),
                customJa3 = tls.limitedString("customJa3", MAX_CUSTOM_JA3_LENGTH),
                dnsProvider = enumValue(dns.optString("provider"), DnsProvider.CLOUDFLARE),
                customDohUrl = dns.limitedString("customDohUrl", 2_048),
                selectedPackages = packages,
                allowIpv6 = routing.optBoolean("allowIpv6", false),
                routeAllApps = routing.optBoolean("routeAllApps", false),
                bypassLocalNetworks = routing.optBoolean("bypassLocalNetworks", true),
            ),
        )
    }

    private fun JSONObject.limitedString(name: String, maxLength: Int): String = optString(name).also {
        require(it.length <= maxLength) { "$name is too long" }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private const val MAX_CUSTOM_JA3_LENGTH = 8 * 1024

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
}
