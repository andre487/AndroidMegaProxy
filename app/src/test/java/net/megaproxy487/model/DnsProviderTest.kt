package net.megaproxy487.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProviderTest {
    @Test
    fun `built in providers have encrypted failover`() {
        listOf(DnsProvider.CLOUDFLARE, DnsProvider.GOOGLE, DnsProvider.QUAD9, DnsProvider.YANDEX).forEach { provider ->
            assertTrue(provider.url.startsWith("https://"))
            assertTrue(provider.fallbackUrls().size >= 2)
            assertTrue(provider.url !in provider.fallbackUrls())
        }
    }

    @Test
    fun `custom provider never leaks queries to implicit fallback`() {
        assertEquals(emptyList<String>(), DnsProvider.CUSTOM.fallbackUrls())
        assertEquals(emptyList<String>(), DnsProvider.YANDEX_SAFE.fallbackUrls())
        assertEquals(emptyList<String>(), DnsProvider.YANDEX_FAMILY.fallbackUrls())
    }

    @Test
    fun `Yandex modes are available`() {
        assertEquals(
            setOf(DnsProvider.YANDEX, DnsProvider.YANDEX_SAFE, DnsProvider.YANDEX_FAMILY),
            DnsProvider.entries.filter { it.name.startsWith("YANDEX") }.toSet(),
        )
    }
}
