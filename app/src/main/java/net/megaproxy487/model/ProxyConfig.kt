package net.megaproxy487.model

data class ProxyConfig(
    val type: ProxyType = ProxyType.HTTPS,
    val host: String = "",
    val port: Int = 443,
    val username: String = "",
    val password: String = "",
    val allowInvalidProxyCertificate: Boolean = false,
    val profile: TlsProfile = TlsProfile.DEFAULT,
    val customJa3: String = "",
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val customDohUrl: String = "",
    val selectedPackages: Set<String> = emptySet(),
    val allowIpv6: Boolean = false,
    val routeAllApps: Boolean = true,
    val bypassLocalNetworks: Boolean = true,
    val resolvedProxyIp: String = "",
    val privateKey: String = "",
    val sshProfile: SshProfile = SshProfile.DEFAULT,
    val trustedHostKey: String = "",
    val acceptAnyHostKey: Boolean = false,
    val jumpHost: String = "",
    val jumpPort: Int = 22,
    val jumpUsername: String = "",
    val jumpPassword: String = "",
    val jumpPrivateKey: String = "",
    val jumpTrustedHostKey: String = "",
    val jumpAcceptAnyHostKey: Boolean = false,
    val sameJumpAuthentication: Boolean = true,
    val resolvedJumpIp: String = "",
    val sshAuthMode: SshAuthMode = SshAuthMode.AUTO,
    val sshKeepaliveSeconds: Int = 30,
    val sshMaxChannels: Int = 32,
    val sshRotationMinutes: Int = 0,
    val sshRotationMb: Int = 0,
) {
    fun connectionValidationError(): String? = when {
        host.isBlank() -> if (type == ProxyType.HTTPS) "Enter the proxy hostname" else "Enter the SSH hostname"
        host.contains(Regex("[/:\\s]")) -> "Enter a hostname without a scheme or path"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        type == ProxyType.HTTPS && username.isBlank() -> "Enter the Basic Auth username"
        type == ProxyType.HTTPS && password.isBlank() -> "Enter the Basic Auth password"
        type != ProxyType.HTTPS && username.isBlank() -> "Enter the SSH username"
        type == ProxyType.SSH_JUMP && jumpHost.isBlank() -> "Enter the jump host"
        type == ProxyType.SSH_JUMP && jumpHost.contains(Regex("[/:\\s]")) -> "Enter a jump hostname without a scheme or path"
        type == ProxyType.SSH_JUMP && jumpPort !in 1..65535 -> "Jump port must be between 1 and 65535"
        type == ProxyType.SSH_JUMP && !sameJumpAuthentication && jumpUsername.isBlank() -> "Enter the jump SSH username"
        type == ProxyType.HTTPS && profile == TlsProfile.CUSTOM && Ja3Spec.parse(customJa3) == null ->
            "JA3 must contain five fields: version,ciphers,extensions,groups,points"
        dnsProvider == DnsProvider.CUSTOM && !customDohUrl.matches(Regex("https://[^/\\s]+/.+")) ->
            "The custom DoH URL must start with https://"
        else -> null
    }

    fun validationError(): String? = connectionValidationError()
}

enum class ProxyType(val title: String, val defaultPort: Int) {
    HTTPS("HTTPS", 443),
    SSH("SSH", 22),
    SSH_JUMP("SSH with Jump", 22),
}

enum class SshProfile(val title: String) {
    DEFAULT("Default (currently OpenSSH/Termux)"),
    OPENSSH_TERMUX("OpenSSH / Termux"),
    CONNECTBOT("ConnectBot"),
    JUICESSH("JuiceSSH"),
    TERMIUS_ANDROID("Termius Android"),
}

enum class SshAuthMode(val title: String) {
    AUTO("Auto: key, then password"),
    PASSWORD_ONLY("Password only"),
    KEY_ONLY("Private key only"),
}

enum class FailoverMode(val title: String) {
    DISABLED("Disabled"),
    SELECTED("Selected profiles"),
    ALL("All profiles"),
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

enum class DnsProvider(val title: String, val url: String) {
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google", "https://dns.google/dns-query"),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query"),
    YANDEX("Yandex Basic", "https://common.dot.dns.yandex.net/dns-query"),
    YANDEX_SAFE("Yandex Safe", "https://safe.dot.dns.yandex.net/dns-query"),
    YANDEX_FAMILY("Yandex Family", "https://family.dot.dns.yandex.net/dns-query"),
    CUSTOM("Custom DoH URL", ""),

    ;

    fun fallbackUrls(): List<String> {
        // Never bypass an explicitly selected content-filtering policy.
        if (this == CUSTOM || this == YANDEX_SAFE || this == YANDEX_FAMILY) return emptyList()
        return entries.asSequence()
            .filter { it != this && it != CUSTOM && it != YANDEX_SAFE && it != YANDEX_FAMILY }
            .map(DnsProvider::url)
            .filter(String::isNotEmpty)
            .toList()
    }
}

enum class TlsProfile(val title: String, val available: Boolean = true) {
    DEFAULT("Default (currently Chrome Android)"),
    CHROME_ANDROID("Chrome Android 133 (uTLS)"),
    FIREFOX_ANDROID("Firefox Android 120 (uTLS)"),
    EDGE_ANDROID("Edge Android (awaiting a verified profile)", false),
    RANDOMIZED("Randomized compatible"),
    SAMSUNG_INTERNET("Samsung Internet (awaiting a verified profile)", false),
    YANDEX_BROWSER("Yandex Browser (awaiting a verified profile)", false),
    CUSTOM("Manual JA3"),
}

data class GlobalConnectionSettings(
    val tlsProfile: TlsProfile = TlsProfile.DEFAULT,
    val sshProfile: SshProfile = SshProfile.DEFAULT,
    val sshAuthMode: SshAuthMode = SshAuthMode.AUTO,
    val sshKeepaliveSeconds: Int = 30,
    val sshMaxChannels: Int = 32,
    val sshRotationMinutes: Int = 0,
    val sshRotationMb: Int = 0,
    val failoverMode: FailoverMode = FailoverMode.DISABLED,
    val failoverProfileIds: List<String> = emptyList(),
    val customJa3: String = "",
    val selectedPackages: Set<String> = emptySet(),
    val routeAllApps: Boolean = true,
    val bypassLocalNetworks: Boolean = true,
) {
    fun applyTo(config: ProxyConfig): ProxyConfig = config.copy(
        profile = if (tlsProfile == TlsProfile.DEFAULT) TlsProfile.CHROME_ANDROID else tlsProfile,
        customJa3 = customJa3,
        sshProfile = sshProfile,
        sshAuthMode = sshAuthMode,
        sshKeepaliveSeconds = sshKeepaliveSeconds,
        sshMaxChannels = sshMaxChannels,
        sshRotationMinutes = sshRotationMinutes,
        sshRotationMb = sshRotationMb,
        selectedPackages = selectedPackages,
        routeAllApps = routeAllApps,
        bypassLocalNetworks = bypassLocalNetworks,
    )
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
