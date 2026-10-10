package net.megaproxy487.data

import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.GlobalConnectionSettings

/** Cumulative field edits keep retries complete without overwriting newer, untouched subscription values. */
internal class ConfigEdit<T>(private val apply: (T) -> T, private val committed: () -> Unit) {
    fun write(save: ((T) -> T) -> Unit) {
        save(apply)
        committed()
    }
}

internal class ConfigEdits<T> {
    private val fields = linkedMapOf<String, Pair<Long, (T) -> T>>()
    private val committed = mutableMapOf<String, Long>()
    private var revision = 0L
    @Synchronized
    fun record(key: String, before: Any?, after: Any?, edit: (T) -> T) {
        if (before != after) fields[key] = ++revision to edit
    }
    @Synchronized
    fun snapshot(): ConfigEdit<T> {
        val edits = fields.toMap()
        return ConfigEdit({ current -> synchronized(this) {
            edits.entries.fold(current) { value, (key, edit) ->
                if (edit.first > (committed[key] ?: 0L)) edit.second(value) else value
            }
        } }, { synchronized(this) {
            edits.forEach { (key, edit) ->
                committed[key] = maxOf(committed[key] ?: 0L, edit.first)
                if (fields[key]?.first == edit.first) fields.remove(key)
            }
        } })
    }
}

internal fun ConfigEdits<ProxyProfile>.profile(before: ProxyProfile, after: ProxyProfile): ConfigEdit<ProxyProfile> {
    record("name", before.name, after.name) { it.copy(name = after.name) }
    record("colorIndex", before.colorIndex, after.colorIndex) { it.copy(colorIndex = after.colorIndex) }
    record("countryCode", before.countryCode, after.countryCode) { it.copy(countryCode = after.countryCode) }
    record("type", before.config.type, after.config.type) { it.copy(config = it.config.copy(type = after.config.type)) }
    record("host", before.config.host, after.config.host) { it.copy(config = it.config.copy(host = after.config.host)) }
    record("port", before.config.port, after.config.port) { it.copy(config = it.config.copy(port = after.config.port)) }
    record("username", before.config.username, after.config.username) { it.copy(config = it.config.copy(username = after.config.username)) }
    record("password", before.config.password to before.config.unreadableSecrets["password"], after.config.password to after.config.unreadableSecrets["password"]) {
        it.copy(config = it.config.copy(password = after.config.password, unreadableSecrets = it.config.unreadableSecrets - "password"))
    }
    record("allowInvalidProxyCertificate", before.config.allowInvalidProxyCertificate, after.config.allowInvalidProxyCertificate) { it.copy(config = it.config.copy(allowInvalidProxyCertificate = after.config.allowInvalidProxyCertificate)) }
    record("preferHttp3", before.config.preferHttp3, after.config.preferHttp3) { it.copy(config = it.config.copy(preferHttp3 = after.config.preferHttp3)) }
    record("profile", before.config.profile, after.config.profile) { it.copy(config = it.config.copy(profile = after.config.profile)) }
    record("customJa3", before.config.customJa3, after.config.customJa3) { it.copy(config = it.config.copy(customJa3 = after.config.customJa3)) }
    record("dnsProvider", before.config.dnsProvider, after.config.dnsProvider) { it.copy(config = it.config.copy(dnsProvider = after.config.dnsProvider)) }
    record("customDohUrl", before.config.customDohUrl, after.config.customDohUrl) { it.copy(config = it.config.copy(customDohUrl = after.config.customDohUrl)) }
    record("selectedPackages", before.config.selectedPackages, after.config.selectedPackages) { it.copy(config = it.config.copy(selectedPackages = after.config.selectedPackages)) }
    record("allowIpv6", before.config.allowIpv6, after.config.allowIpv6) { it.copy(config = it.config.copy(allowIpv6 = after.config.allowIpv6)) }
    record("routeAllApps", before.config.routeAllApps, after.config.routeAllApps) { it.copy(config = it.config.copy(routeAllApps = after.config.routeAllApps)) }
    record("bypassLocalNetworks", before.config.bypassLocalNetworks, after.config.bypassLocalNetworks) { it.copy(config = it.config.copy(bypassLocalNetworks = after.config.bypassLocalNetworks)) }
    record("resolvedProxyIp", before.config.resolvedProxyIp, after.config.resolvedProxyIp) { it.copy(config = it.config.copy(resolvedProxyIp = after.config.resolvedProxyIp)) }
    record("privateKey", before.config.privateKey to before.config.unreadableSecrets["privateKey"], after.config.privateKey to after.config.unreadableSecrets["privateKey"]) {
        it.copy(config = it.config.copy(privateKey = after.config.privateKey, unreadableSecrets = it.config.unreadableSecrets - "privateKey"))
    }
    record("sshProfile", before.config.sshProfile, after.config.sshProfile) { it.copy(config = it.config.copy(sshProfile = after.config.sshProfile)) }
    record("trustedHostKey", before.config.trustedHostKey, after.config.trustedHostKey) { it.copy(config = it.config.copy(trustedHostKey = after.config.trustedHostKey)) }
    record("acceptAnyHostKey", before.config.acceptAnyHostKey, after.config.acceptAnyHostKey) { it.copy(config = it.config.copy(acceptAnyHostKey = after.config.acceptAnyHostKey)) }
    record("jumpHost", before.config.jumpHost, after.config.jumpHost) { it.copy(config = it.config.copy(jumpHost = after.config.jumpHost)) }
    record("jumpPort", before.config.jumpPort, after.config.jumpPort) { it.copy(config = it.config.copy(jumpPort = after.config.jumpPort)) }
    record("jumpUsername", before.config.jumpUsername, after.config.jumpUsername) { it.copy(config = it.config.copy(jumpUsername = after.config.jumpUsername)) }
    record("jumpPassword", before.config.jumpPassword to before.config.unreadableSecrets["jumpPassword"], after.config.jumpPassword to after.config.unreadableSecrets["jumpPassword"]) {
        it.copy(config = it.config.copy(jumpPassword = after.config.jumpPassword, unreadableSecrets = it.config.unreadableSecrets - "jumpPassword"))
    }
    record("jumpPrivateKey", before.config.jumpPrivateKey to before.config.unreadableSecrets["jumpPrivateKey"], after.config.jumpPrivateKey to after.config.unreadableSecrets["jumpPrivateKey"]) {
        it.copy(config = it.config.copy(jumpPrivateKey = after.config.jumpPrivateKey, unreadableSecrets = it.config.unreadableSecrets - "jumpPrivateKey"))
    }
    record("jumpTrustedHostKey", before.config.jumpTrustedHostKey, after.config.jumpTrustedHostKey) { it.copy(config = it.config.copy(jumpTrustedHostKey = after.config.jumpTrustedHostKey)) }
    record("jumpAllowInvalidProxyCertificate", before.config.jumpAllowInvalidProxyCertificate, after.config.jumpAllowInvalidProxyCertificate) { it.copy(config = it.config.copy(jumpAllowInvalidProxyCertificate = after.config.jumpAllowInvalidProxyCertificate)) }
    record("jumpAcceptAnyHostKey", before.config.jumpAcceptAnyHostKey, after.config.jumpAcceptAnyHostKey) { it.copy(config = it.config.copy(jumpAcceptAnyHostKey = after.config.jumpAcceptAnyHostKey)) }
    record("sameJumpAuthentication", before.config.sameJumpAuthentication, after.config.sameJumpAuthentication) { it.copy(config = it.config.copy(sameJumpAuthentication = after.config.sameJumpAuthentication)) }
    record("resolvedJumpIp", before.config.resolvedJumpIp, after.config.resolvedJumpIp) { it.copy(config = it.config.copy(resolvedJumpIp = after.config.resolvedJumpIp)) }
    record("sshAuthMode", before.config.sshAuthMode, after.config.sshAuthMode) { it.copy(config = it.config.copy(sshAuthMode = after.config.sshAuthMode)) }
    record("sshKeepaliveSeconds", before.config.sshKeepaliveSeconds, after.config.sshKeepaliveSeconds) { it.copy(config = it.config.copy(sshKeepaliveSeconds = after.config.sshKeepaliveSeconds)) }
    record("sshMaxChannels", before.config.sshMaxChannels, after.config.sshMaxChannels) { it.copy(config = it.config.copy(sshMaxChannels = after.config.sshMaxChannels)) }
    record("sshRotationMinutes", before.config.sshRotationMinutes, after.config.sshRotationMinutes) { it.copy(config = it.config.copy(sshRotationMinutes = after.config.sshRotationMinutes)) }
    record("sshRotationMb", before.config.sshRotationMb, after.config.sshRotationMb) { it.copy(config = it.config.copy(sshRotationMb = after.config.sshRotationMb)) }
    return snapshot()
}

internal fun ConfigEdits<GlobalConnectionSettings>.settings(before: GlobalConnectionSettings, after: GlobalConnectionSettings): ConfigEdit<GlobalConnectionSettings> {
    record("tlsProfile", before.tlsProfile, after.tlsProfile) { it.copy(tlsProfile = after.tlsProfile) }
    record("sshProfile", before.sshProfile, after.sshProfile) { it.copy(sshProfile = after.sshProfile) }
    record("sshAuthMode", before.sshAuthMode, after.sshAuthMode) { it.copy(sshAuthMode = after.sshAuthMode) }
    record("sshKeepaliveSeconds", before.sshKeepaliveSeconds, after.sshKeepaliveSeconds) { it.copy(sshKeepaliveSeconds = after.sshKeepaliveSeconds) }
    record("sshMaxChannels", before.sshMaxChannels, after.sshMaxChannels) { it.copy(sshMaxChannels = after.sshMaxChannels) }
    record("sshRotationMinutes", before.sshRotationMinutes, after.sshRotationMinutes) { it.copy(sshRotationMinutes = after.sshRotationMinutes) }
    record("sshRotationMb", before.sshRotationMb, after.sshRotationMb) { it.copy(sshRotationMb = after.sshRotationMb) }
    record("failoverMode", before.failoverMode, after.failoverMode) { it.copy(failoverMode = after.failoverMode) }
    record("failoverProfileIds", before.failoverProfileIds, after.failoverProfileIds) { it.copy(failoverProfileIds = after.failoverProfileIds) }
    record("customJa3", before.customJa3, after.customJa3) { it.copy(customJa3 = after.customJa3) }
    record("selectedPackages", before.selectedPackages, after.selectedPackages) { it.copy(selectedPackages = after.selectedPackages) }
    record("routeAllApps", before.routeAllApps, after.routeAllApps) { it.copy(routeAllApps = after.routeAllApps) }
    record("bypassLocalNetworks", before.bypassLocalNetworks, after.bypassLocalNetworks) { it.copy(bypassLocalNetworks = after.bypassLocalNetworks) }
    return snapshot()
}
