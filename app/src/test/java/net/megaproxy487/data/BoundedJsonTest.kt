package net.megaproxy487.data

import net.megaproxy487.R
import net.megaproxy487.UiException
import org.junit.Assert.*
import org.junit.Test

class BoundedJsonTest {
    @Test fun deeplyNestedImportIsRejectedBeforeRecursiveParsing() {
        val text = "{\"data\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}"
        val error = assertThrows(UiException::class.java) { boundedJsonObject(text) }
        assertEquals(R.string.error_config_complex, error.textId)
        assertTrue(FoxyProxyParser.parse(text).exceptionOrNull() is UiException)
        assertThrows(UiException::class.java) { ConfigTransfer.importJson(text) }
    }

    @Test fun wideJsonIsRejectedBeforeAllocatingItsTree() {
        val text = "{\"ignored\":[" + "{},".repeat(150_000) + "{}]}"
        assertThrows(UiException::class.java) { boundedJsonObject(text) }
    }

    @Test fun directParserCallsAlsoHaveSizeLimit() {
        val text = " ".repeat(MAX_CONFIG_FILE_BYTES + 1)
        assertEquals(R.string.error_config_large,
            assertThrows(UiException::class.java) { boundedJsonObject(text) }.textId)
    }

    @Test fun delimitersAndEscapedQuotesInsideStringsDoNotCount() {
        val value = "[{}],:#/'\"\\".repeat(200)
        val text = org.json.JSONObject().put("value", value).toString()
        assertEquals(value, boundedJsonObject(text).getString("value"))
    }

    @Test fun lenientSyntaxCannotHideNestingFromGuard() {
        for (text in listOf("{'key':[]}", "{/* comment */\"key\":[]}", "{# comment\n\"key\":[]}")) {
            assertThrows(UiException::class.java) { boundedJsonObject(text) }
        }
    }
}
