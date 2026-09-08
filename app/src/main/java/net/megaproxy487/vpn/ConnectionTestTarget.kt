package net.megaproxy487.vpn

import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile

/** Configuration and trust destination must come from the same connection snapshot. */
internal data class ConnectionTestTarget(val profileId: String, val config: ProxyConfig)

internal fun connectionTestTarget(
    runtime: ConnectionTestTarget?,
    selected: () -> ConnectionTestTarget,
): ConnectionTestTarget = runtime ?: selected()

/** A fresh diagnostic session must observe a just-approved key without applying draft settings. */
internal fun ConnectionTestTarget.withStoredTrust(profile: ProxyProfile?): ConnectionTestTarget {
    if (profile?.id != profileId || profile.config.type != config.type) return this
    val stored = profile.config
    val sameJump = stored.jumpHost == config.jumpHost && stored.jumpPort == config.jumpPort
    val sameDestination = stored.host == config.host && stored.port == config.port &&
        (!config.type.hasJump || sameJump)
    return copy(config = config.copy(
        trustedHostKey = if (sameDestination) stored.trustedHostKey else config.trustedHostKey,
        jumpTrustedHostKey = if (sameJump) stored.jumpTrustedHostKey else config.jumpTrustedHostKey,
    ))
}
