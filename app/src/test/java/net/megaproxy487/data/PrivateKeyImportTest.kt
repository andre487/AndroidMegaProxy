package net.megaproxy487.data

import java.io.ByteArrayInputStream
import net.megaproxy487.UiException
import net.megaproxy487.R
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

        val failure = assertThrows(UiException::class.java) {
            ByteArrayInputStream(input.toByteArray()).readPrivateKeyText()
        }
        assertEquals(R.string.error_key_missing, failure.textId)
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
