package net.megaproxy487.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LimitedInputTest {
    @Test
    fun `reads a normal configuration`() {
        assertEquals("config", ByteArrayInputStream("config".toByteArray()).readConfigText())
    }

    @Test
    fun `rejects an oversized configuration`() {
        val input = ByteArrayInputStream(ByteArray(MAX_CONFIG_FILE_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { input.readConfigText() }
    }
}
