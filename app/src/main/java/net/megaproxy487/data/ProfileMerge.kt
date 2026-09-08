package net.megaproxy487.data

import net.megaproxy487.model.ProxyProfile

/** Preserve local ordering without scanning the replacement list for every profile. */
internal fun mergeResolvedProfiles(
    existing: List<ProxyProfile>,
    resolved: List<ProxyProfile>,
    added: List<ProxyProfile>,
): List<ProxyProfile> {
    val byId = resolved.associateBy(ProxyProfile::id)
    return existing.map { byId[it.id] ?: it } + added
}
