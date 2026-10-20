package net.megaproxy487

import java.io.InputStream
import net.megaproxy487.data.readBoundedText
import org.junit.Assert.*
import org.junit.Test

class BoundedResponsesTest {
    @Test fun updateMetadataRejectsExcessiveDepthWithoutOverflow() {
        val input = "{\"ignored\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}"
        for (parse in listOf<(String) -> Any?>({ parseGithubUpdate(it, "1.0.0", "universal") }, { parseFdroidUpdate(it, 1) })) {
            assertThrows(UiException::class.java) { parse(input) }
        }
        assertEquals(ProxyCheckVerdict.UNKNOWN_FORMAT, parseProxyCheckResponse(200, input).verdict)
    }

    @Test fun diagnosticReadsStopAtByteLimit() {
        val prefix = "ip=203.0.113.42\n".toByteArray()
        val size = 16 * 1024 * 1024
        var offset = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                if (offset == size) return -1
                return if (offset < prefix.size) prefix[offset++].toInt() else { offset++; 'x'.code }
            }
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (offset == size) return -1
                val count = minOf(length, size - offset)
                java.util.Arrays.fill(buffer, start, start + count, 'x'.code.toByte())
                for (i in 0 until minOf(count, (prefix.size - offset).coerceAtLeast(0))) buffer[start+i] = prefix[offset+i]
                offset += count
                return count
            }
        }
        assertThrows(UiException::class.java) { stream.use { it.readBoundedText(64 * 1024) } }
        assertTrue("Stop before buffering the oversized body", offset <= 64 * 1024 + 16 * 1024)
        assertEquals("ip=203.0.113.42\n", prefix.inputStream().use { it.readBoundedText(prefix.size) })
    }
}
