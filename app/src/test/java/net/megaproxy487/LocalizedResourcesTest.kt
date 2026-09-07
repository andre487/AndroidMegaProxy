package net.megaproxy487

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedResourcesTest {
    @Test
    fun russianResourcesCoverEveryEnglishString() {
        val english = stringsIn(resourceFile("values/strings.xml"))
        val russian = stringsIn(resourceFile("values-ru/strings.xml"))

        assertTrue("Missing Russian strings: ${english.keys - russian.keys}", russian.keys.containsAll(english.keys))
    }

    @Test
    fun navigationAndTrafficLabelsHaveExpectedRussianTranslations() {
        val russian = stringsIn(resourceFile("values-ru/strings.xml"))

        assertEquals("Настройки", russian["settings"])
        assertEquals("Профили", russian["profiles"])
        assertEquals("Диагностический журнал", russian["diagnostic_log"])
        assertTrue(russian.getValue("traffic_units_iec").contains("KiB, MiB, GiB"))
        assertTrue(russian.getValue("traffic_units_si").contains("KB, MB, GB"))
    }

    @Test
    fun translationsPreserveFormatArguments() {
        val english = stringsIn(resourceFile("values/strings.xml"))
        val russian = stringsIn(resourceFile("values-ru/strings.xml"))
        val placeholder = Regex("%[0-9]+\\$[a-zA-Z]")
        english.forEach { (key, text) ->
            assertEquals(key,
                placeholder.findAll(text).map { it.value }.sorted().toList(),
                placeholder.findAll(russian.getValue(key)).map { it.value }.sorted().toList())
        }
    }

    private fun resourceFile(relativePath: String): File =
        sequenceOf(File("src/main/res", relativePath), File("app/src/main/res", relativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot find Android resource file: $relativePath")

    private fun stringsIn(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index)
                if (element.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                put(element.attributes.getNamedItem("name").nodeValue, element.textContent.trim())
            }
        }
    }
}
