package net.megaproxy487.data

import net.megaproxy487.model.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileMergeTest {
    @Test fun partialImportPreservesLocalOrderAndUnchangedProfiles() {
        val existing = List(1000) { ProxyProfile(id = "$it", name = "local-$it", colorIndex = 0) }
        val updates = existing.filterIndexed { index, _ -> index % 2 == 0 }
            .reversed().map { it.copy(name = "imported-${it.id}") }
        val added = listOf(ProxyProfile(id = "new", colorIndex = 1))
        val merged = mergeResolvedProfiles(existing, updates + added, added)
        assertEquals(existing.map { it.id } + "new", merged.map { it.id })
        existing.indices.forEach { index ->
            if (index % 2 == 0) assertEquals("imported-$index", merged[index].name)
            else assertSame(existing[index], merged[index])
        }
        assertSame(added.single(), merged.last())
    }
}
