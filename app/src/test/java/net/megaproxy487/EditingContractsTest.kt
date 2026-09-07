package net.megaproxy487

import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyType
import org.junit.Assert.*
import org.junit.Test

class EditingContractsTest {
    @Test fun selectingSameTypeKeepsCustomPorts() {
        val config = ProxyConfig(type = ProxyType.HTTPS_JUMP, port = 8443, jumpPort = 9443)
        assertEquals(config, config.withType(ProxyType.HTTPS_JUMP))
    }
    @Test fun changingTypeKeepsCustomPortsAndUpdatesDefaults() {
        assertEquals(8443, ProxyConfig(port = 8443).withType(ProxyType.SSH).port)
        val changed = ProxyConfig(type = ProxyType.HTTPS_JUMP).withType(ProxyType.SSH_JUMP)
        assertEquals(22, changed.port)
        assertEquals(22, changed.jumpPort)
        assertEquals(9443, ProxyConfig(type = ProxyType.HTTPS_JUMP, jumpPort = 9443).withType(ProxyType.SSH_JUMP).jumpPort)
    }
    @Test fun invalidIntegerDraftsHaveNoValueToPersist() {
        listOf("", "abc", "-1", "0", "65536", "99999999999999999").forEach {
            assertNull(it, validIntegerInput(it, 1..65535))
        }
        assertEquals(443, validIntegerInput("443", 1..65535))
        assertEquals(0, validIntegerInput("0", 0..3600))
    }
    @Test fun interruptedOrEmptyExportsCannotReachWriter() {
        assertEquals(R.string.export_expired, assertThrows(UiException::class.java) { requireExportContent(null) }.textId)
        assertEquals(R.string.export_empty, assertThrows(UiException::class.java) { requireExportContent("") }.textId)
        assertEquals("snapshot", requireExportContent("snapshot"))
    }
    @Test fun parserDistinguishesProxyListAndMalformedJsonBeforeCommit() {
        val parsed = parseProfileImport("https://user:secret@proxy.example:443", "text/plain", "proxies.txt")
        assertTrue(parsed is ParsedProfileImport.ProxyList)
        assertThrows(org.json.JSONException::class.java) { parseProfileImport("{broken", "application/json", "config.json") }
    }
}
