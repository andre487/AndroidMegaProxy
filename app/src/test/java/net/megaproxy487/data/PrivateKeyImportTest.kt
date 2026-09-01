package net.megaproxy487.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateKeyImportTest {
    @Test
    fun importsOpenSshKeyAndNormalizesTrailingNewline() {
        val input = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            example
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        assertEquals("$input\n", ByteArrayInputStream(input.toByteArray()).readPrivateKeyText())
    }

    @Test
    fun rejectsPublicKey() {
        val input = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA example"

        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(input.toByteArray()).readPrivateKeyText()
        }
    }

    @Test
    fun rejectsEncryptedPemKey() {
        val input = """
            -----BEGIN EC PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            example
            -----END EC PRIVATE KEY-----
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(input.toByteArray()).readPrivateKeyText()
        }
    }

    @Test
    fun rejectsOversizedKeyBeforeGrowingWithoutBound() {
        val input = ByteArray(MAX_PRIVATE_KEY_FILE_BYTES + 1) { 'A'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(input).readPrivateKeyText()
        }
    }
}
