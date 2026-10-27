package net.megaproxy487

internal enum class SourceProtectionStatus { UNSUPPORTED, STOPPED, ACTIVE, DISABLED, RECONNECT }

internal fun sourceProtectionStatus(sdk: Int, configured: Boolean, running: Boolean, active: Boolean): SourceProtectionStatus = when {
    sdk < 29 -> SourceProtectionStatus.UNSUPPORTED
    !running -> SourceProtectionStatus.STOPPED
    configured != active -> SourceProtectionStatus.RECONNECT
    active -> SourceProtectionStatus.ACTIVE
    else -> SourceProtectionStatus.DISABLED
}
