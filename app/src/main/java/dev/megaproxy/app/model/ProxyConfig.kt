package dev.megaproxy.app.model

data class ProxyConfig(
    val host: String = "",
    val port: Int = 443,
    val username: String = "",
    val password: String = "",
    val allowInvalidProxyCertificate: Boolean = false,
    val profile: TlsProfile = TlsProfile.CHROME_ANDROID,
    val customJa3: String = "",
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val customDohUrl: String = "",
    val selectedPackages: Set<String> = emptySet(),
    val allowIpv6: Boolean = false,
    val routeAllApps: Boolean = false,
    val bypassLocalNetworks: Boolean = true,
    val resolvedProxyIp: String = "",
) {
    fun connectionValidationError(): String? = when {
        host.isBlank() -> "Enter the proxy hostname"
        host.contains(Regex("[/:\\s]")) -> "Enter a hostname without a scheme or path"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        username.isBlank() -> "Enter the Basic Auth username"
        password.isBlank() -> "Enter the Basic Auth password"
        profile == TlsProfile.CUSTOM && Ja3Spec.parse(customJa3) == null ->
            "JA3 must contain five fields: version,ciphers,extensions,groups,points"
        dnsProvider == DnsProvider.CUSTOM && !customDohUrl.matches(Regex("https://[^/\\s]+/.+")) ->
            "The custom DoH URL must start with https://"
        else -> null
    }

    fun validationError(): String? = connectionValidationError()
        ?: if (!routeAllApps && selectedPackages.isEmpty()) "Select at least one application" else null
}

data class ProxyProfile(
    val id: String,
    val name: String = "",
    val colorIndex: Int,
    val countryCode: String = "",
    val config: ProxyConfig = ProxyConfig(),
) {
    val displayName: String
        get() = name.trim().ifEmpty { config.host.trim().ifEmpty { "New profile" } }

    val flagEmoji: String
        get() {
            val code = countryCode.uppercase()
            if (!code.matches(Regex("[A-Z]{2}"))) return ""
            return code.map { character ->
                String(Character.toChars(0x1F1E6 + character.code - 'A'.code))
            }.joinToString("")
        }

    val displayNameWithFlag: String
        get() = listOf(flagEmoji, displayName).filter(String::isNotEmpty).joinToString(" ")
}

object ProfileColors {
    // Material Design 500 palette, excluding colors with poor contrast in light themes.
    val argb = listOf(
        0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
        0xFF3F51B5, 0xFF2196F3, 0xFF009688, 0xFF4CAF50,
        0xFF8BC34A, 0xFFFF9800, 0xFFFF5722, 0xFF795548,
    )
}

enum class ProfileSort(val title: String) {
    NAME("Name"),
    HOST("Host"),
    COUNTRY("Country"),
}

enum class DnsProvider(val title: String, val url: String) {
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google", "https://dns.google/dns-query"),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query"),
    CUSTOM("Custom DoH URL", ""),
}

enum class TlsProfile(val title: String, val available: Boolean = true) {
    CHROME_ANDROID("Chrome Android 133 (uTLS)"),
    FIREFOX_ANDROID("Firefox Android 120 (uTLS)"),
    EDGE_ANDROID("Edge Android (awaiting a verified profile)", false),
    RANDOMIZED("Randomized compatible"),
    SAMSUNG_INTERNET("Samsung Internet (awaiting a verified profile)", false),
    YANDEX_BROWSER("Yandex Browser (awaiting a verified profile)", false),
    CUSTOM("Manual JA3"),
}

data class Ja3Spec(
    val tlsVersion: Int,
    val cipherSuites: List<Int>,
    val extensions: List<Int>,
    val supportedGroups: List<Int>,
    val ecPointFormats: List<Int>,
) {
    companion object {
        fun parse(value: String): Ja3Spec? = runCatching {
            val fields = value.trim().split(',')
            require(fields.size == 5)
            fun numbers(field: String): List<Int> = if (field.isBlank()) emptyList() else
                field.split('-').map { it.toInt().also { number -> require(number in 0..65535) } }
            Ja3Spec(fields[0].toInt(), numbers(fields[1]), numbers(fields[2]), numbers(fields[3]), numbers(fields[4]))
        }.getOrNull()
    }
}
