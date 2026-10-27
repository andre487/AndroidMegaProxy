package net.megaproxy487

import net.megaproxy487.vpn.BridgeSourceValidator
import net.megaproxy487.model.SourceValidationLevel
import org.junit.Assert.*
import org.junit.Test

class SourceProtectionTest {
    @Test fun unknownOwnersExceptionsAndStaleSessionsFailClosed() {
        var uid = 123
        var current = true
        var calls = 0
        var throws = false
        val validator = BridgeSourceValidator({ current }, setOf(123)) { protocol, source, sourcePort, destination, destinationPort ->
            calls++
            assertEquals(17, protocol)
            assertEquals("10.77.0.1", source)
            assertEquals(1234, sourcePort)
            assertEquals("192.0.2.1", destination)
            assertEquals(53, destinationPort)
            if (throws) throw SecurityException("synthetic")
            uid
        }
        fun allowed() = validator.allow(17, "10.77.0.1", 1234, "192.0.2.1", 53)
        assertTrue(allowed())
        uid = 456
        assertFalse(allowed())
        uid = -1
        assertFalse(allowed())
        throws = true
        assertFalse(allowed())
        current = false
        val before = calls
        assertFalse(allowed())
        assertEquals(before, calls)
        assertFalse(validator.allow(17, "10.77.0.1", 1L shl 32, "192.0.2.1", 53))
    }

    @Test fun profileImportsCannotDisableLocalProtection() {
        val current = net.megaproxy487.model.GlobalConnectionSettings(strictSourceValidation = true, sourceValidationLevel = SourceValidationLevel.ADVANCED)
        for (fields in listOf<Set<String>?>(null, emptySet(), setOf("routing.routeAllApps"))) {
            val imported = net.megaproxy487.data.PortableConfiguration(emptyList(), null, null,
                globalConnectionSettings = net.megaproxy487.model.GlobalConnectionSettings(), globalSettingFields = fields)
            assertTrue(imported.mergeGlobalSettings(current).strictSourceValidation)
            assertEquals(SourceValidationLevel.ADVANCED, imported.mergeGlobalSettings(current).sourceValidationLevel)
        }
    }

    @Test fun changingActiveLevelRequiresReconnection() {
        assertEquals(SourceProtectionStatus.RECONNECT, sourceProtectionStatus(35, SourceValidationLevel.ADVANCED, true, SourceValidationLevel.STANDARD))
        assertEquals(SourceProtectionStatus.RECONNECT, sourceProtectionStatus(35, SourceValidationLevel.STANDARD, true, SourceValidationLevel.ADVANCED))
        assertEquals(SourceProtectionStatus.ACTIVE, sourceProtectionStatus(35, SourceValidationLevel.ADVANCED, true, SourceValidationLevel.ADVANCED))
    }

    @Test fun visibilityDescribesRunningSessionRatherThanJustPreference() {
        assertEquals(SourceProtectionStatus.UNSUPPORTED, sourceProtectionStatus(26, SourceValidationLevel.STANDARD, true, null))
        assertEquals(SourceProtectionStatus.STOPPED, sourceProtectionStatus(35, SourceValidationLevel.STANDARD, false, null))
        assertEquals(SourceProtectionStatus.DISABLED, sourceProtectionStatus(35, null, true, null))
        assertEquals(SourceProtectionStatus.RECONNECT, sourceProtectionStatus(35, SourceValidationLevel.STANDARD, true, null))
        assertEquals(SourceProtectionStatus.RECONNECT, sourceProtectionStatus(35, null, true, SourceValidationLevel.STANDARD))
        assertEquals(SourceProtectionStatus.ACTIVE, sourceProtectionStatus(35, SourceValidationLevel.STANDARD, true, SourceValidationLevel.STANDARD))
    }
}
