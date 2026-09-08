package net.megaproxy487

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestSecurityTest {
    @Test fun onlyLauncherActivityAcceptsExternalIntents() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val android = "http://schemas.android.com/apk/res/android"
        val exported = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .filter { it.getAttributeNS(android, "exported") == "true" }
            .map { it.getAttributeNS(android, "name") }
        assertEquals(listOf(".MainActivity"), exported)
        val review = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".SshHostKeyReviewActivity" }
        assertEquals("false", review.getAttributeNS(android, "exported"))
    }
}
