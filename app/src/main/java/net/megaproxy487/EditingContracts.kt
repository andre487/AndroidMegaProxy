package net.megaproxy487

import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyType

internal fun ProxyConfig.withType(selected: ProxyType): ProxyConfig {
    if (selected == type) return this
    return copy(
        type = selected,
        port = if (port == type.defaultPort) selected.defaultPort else port,
        jumpPort = if (jumpPort == type.defaultPort) selected.defaultPort else jumpPort,
    )
}

internal fun validIntegerInput(text: String, range: IntRange): Int? =
    text.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in range }
