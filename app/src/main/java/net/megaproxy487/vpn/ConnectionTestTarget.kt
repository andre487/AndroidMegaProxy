package net.megaproxy487.vpn

import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile

/** Configuration and trust destination must come from the same connection snapshot. */
internal data class ConnectionTestTarget(val profileId: String, val config: ProxyConfig)

internal fun connectionTestTarget(
    runtime: ConnectionTestTarget?,
    selected: () -> ConnectionTestTarget,
): ConnectionTestTarget = runtime ?: selected()

/** Refresh diagnostic metadata and approved keys without applying draft connection settings. */
internal fun ConnectionTestTarget.withStoredDiagnosticSettings(profile: ProxyProfile?): ConnectionTestTarget {
    if (profile?.id != profileId || profile.config.type != config.type) return this
    val stored = profile.config
    val sameJump = stored.jumpHost == config.jumpHost && stored.jumpPort == config.jumpPort
    val sameDestination = stored.host == config.host && stored.port == config.port &&
        (!config.type.hasJump || sameJump)
    return copy(config = config.copy(
        authProbeHost = if (sameDestination) stored.authProbeHost else config.authProbeHost,
        trustedHostKey = if (sameDestination) stored.trustedHostKey else config.trustedHostKey,
        jumpTrustedHostKey = if (sameJump) stored.jumpTrustedHostKey else config.jumpTrustedHostKey,
    ))
}
