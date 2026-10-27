package net.megaproxy487

import net.megaproxy487.model.SourceValidationLevel

internal enum class SourceProtectionStatus { UNSUPPORTED, STOPPED, ACTIVE, DISABLED, RECONNECT }

internal fun sourceProtectionStatus(sdk: Int, configured: SourceValidationLevel?, running: Boolean, active: SourceValidationLevel?): SourceProtectionStatus = when {
    sdk < 29 -> SourceProtectionStatus.UNSUPPORTED
    !running -> SourceProtectionStatus.STOPPED
    configured != active -> SourceProtectionStatus.RECONNECT
    active != null -> SourceProtectionStatus.ACTIVE
    else -> SourceProtectionStatus.DISABLED
}
