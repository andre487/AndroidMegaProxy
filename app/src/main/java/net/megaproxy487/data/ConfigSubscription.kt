package net.megaproxy487.data

import java.net.URI
import java.net.IDN
import net.megaproxy487.model.ProxyConfig
import org.json.JSONArray
import org.json.JSONObject

/** Portable delivery settings. Never place these URLs or credentials in logs or saved state. */
data class ConfigSubscription(
    val url: String,
    val fallbackUrls: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null,
    val intervalMinutes: Int = 60,
    val enabled: Boolean = true,
) {
    fun validate(): ConfigSubscription = apply {
        require(fallbackUrls.size <= 7)
        val sources = (listOf(url) + fallbackUrls).map(::normalizeSubscriptionUrl)
        require(sources.distinct().size == sources.size)
        require(intervalMinutes in 1..10080)
        require(username == null || username.length <= 1024 && ':' !in username && username.none(::control))
        require(password == null || password.length <= 1024 && password.none(::control))
    }

    fun toJson(includePassword: Boolean = true): JSONObject = JSONObject().apply {
        put("url", url)
        put("fallbackUrls", JSONArray(fallbackUrls))
        username?.let { put("username", it) }
        if (includePassword) password?.let { put("password", it) }
        put("intervalMinutes", intervalMinutes)
        put("enabled", enabled)
    }

    fun retainPassword(previous: ConfigSubscription?): ConfigSubscription =
        if (password == null && previous != null &&
            normalizeSubscriptionUrl(url) == normalizeSubscriptionUrl(previous.url) && username.orEmpty() == previous.username.orEmpty()
        ) copy(password = previous.password) else this

    companion object {
        fun fromJson(value: JSONObject): ConfigSubscription {
            fun string(key: String): String? = if (!value.has(key)) null else {
                require(value.get(key) is String)
                value.getString(key)
            }
            val interval = if (!value.has("intervalMinutes")) 60 else {
                val number = value.get("intervalMinutes")
                require(number is Number && number.toDouble() == number.toInt().toDouble())
                number.toInt()
            }
            val enabled = if (!value.has("enabled")) true else {
                require(value.get("enabled") is Boolean)
                value.getBoolean("enabled")
            }
            val fallback = if (!value.has("fallbackUrls")) emptyList() else {
                val array = value.getJSONArray("fallbackUrls")
                require(array.length() <= 7)
                List(array.length()) { index ->
                    require(array.get(index) is String)
                    array.getString(index)
                }
            }
            return ConfigSubscription(requireNotNull(string("url")), fallback,
                string("username"), string("password"), interval, enabled).validate()
        }
    }
}

private fun control(char: Char) = char.code < 32 || char.code in 127..159

internal fun normalizeSubscriptionUrl(value: String): String {
    require(value.length in 1..2048 && value.none { it.isWhitespace() || control(it) })
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535))
    val host = uri.host.lowercase()
    val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    return "https://$host$port${uri.normalize().rawPath.orEmpty().ifEmpty { "/" }}" +
        (uri.rawQuery?.let { "?$it" } ?: "")
}

/** Ownership/status are local; only settings join a portable export. */
data class ConfigSubscriptionState(
    val settings: ConfigSubscription,
    val ownedIds: Set<String> = emptySet(),
    val generation: String = java.util.UUID.randomUUID().toString(),
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val sourceIndex: Int = -1,
    val failed: Boolean = false,
    val warnings: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("settings", settings.toJson())
        .put("ownedIds", JSONArray(ownedIds.sorted())).put("generation", generation)
        .put("lastAttempt", lastAttempt).put("lastSuccess", lastSuccess).put("sourceIndex", sourceIndex)
        .put("failed", failed).put("warnings", warnings)

    companion object {
        fun fromJson(value: JSONObject): ConfigSubscriptionState = ConfigSubscriptionState(
            ConfigSubscription.fromJson(value.getJSONObject("settings")),
            value.getJSONArray("ownedIds").let { array -> List(array.length()) { array.getString(it) }.toSet() },
            value.getString("generation"), value.getLong("lastAttempt"), value.getLong("lastSuccess"),
            value.getInt("sourceIndex"), value.getBoolean("failed"), value.getBoolean("warnings"),
        )
    }
}

internal fun sameSubscriptionSource(a: ConfigSubscription, b: ConfigSubscription): Boolean =
    normalizeSubscriptionUrl(a.url) == normalizeSubscriptionUrl(b.url) && a.username.orEmpty() == b.username.orEmpty()

private fun sameProxyAccount(incoming: ProxyConfig, current: ProxyConfig, jump: Boolean): Boolean {
    fun host(value: String) = runCatching { IDN.toASCII(value.trim().removeSuffix(".")).lowercase() }
        .getOrDefault(value.trim().removeSuffix(".").lowercase())
    if (incoming.type != current.type) return false
    if (jump) return incoming.type.hasJump && !incoming.sameJumpAuthentication && !current.sameJumpAuthentication &&
        incoming.jumpUsername.isNotEmpty() && incoming.jumpUsername == current.jumpUsername &&
        host(incoming.jumpHost) == host(current.jumpHost) && incoming.jumpPort == current.jumpPort
    return incoming.username.isNotEmpty() && incoming.username == current.username &&
        host(incoming.host) == host(current.host) && incoming.port == current.port &&
        (!incoming.type.hasJump || !incoming.sameJumpAuthentication ||
            current.sameJumpAuthentication && host(incoming.jumpHost) == host(current.jumpHost) && incoming.jumpPort == current.jumpPort)
}

internal fun retainProfileSecrets(
    source: net.megaproxy487.model.ProxyProfile,
    current: net.megaproxy487.model.ProxyProfile?,
    presence: ProfileSecretPresence,
): net.megaproxy487.model.ProxyProfile {
    if (current == null) return source
    val sameDns = source.config.dnsProvider == current.config.dnsProvider &&
        (source.config.dnsProvider != net.megaproxy487.model.DnsProvider.CUSTOM ||
            source.config.customDohUrl.trim() == current.config.customDohUrl.trim())
    val sameAccount = sameProxyAccount(source.config, current.config, jump = false)
    val sameJumpAccount = sameProxyAccount(source.config, current.config, jump = true)
    return source.copy(config = source.config.copy(
        allowInsecureDns = sameDns && current.config.allowInsecureDns,
        allowInvalidDohCertificate = sameDns && current.config.allowInvalidDohCertificate,
        password = if (presence.password) source.config.password else current.config.password.takeIf { sameAccount }.orEmpty(),
        privateKey = if (presence.privateKey) source.config.privateKey else current.config.privateKey.takeIf { sameAccount }.orEmpty(),
        jumpPassword = if (presence.jumpPassword) source.config.jumpPassword else current.config.jumpPassword.takeIf { sameJumpAccount }.orEmpty(),
        jumpPrivateKey = if (presence.jumpPrivateKey) source.config.jumpPrivateKey else current.config.jumpPrivateKey.takeIf { sameJumpAccount }.orEmpty(),
        unreadableSecrets = current.config.unreadableSecrets.filterKeys { name -> when (name) {
            "password" -> !presence.password
            "privateKey" -> !presence.privateKey
            "jumpPassword" -> !presence.jumpPassword
            "jumpPrivateKey" -> !presence.jumpPrivateKey
            else -> false
        } },
    ))
}
