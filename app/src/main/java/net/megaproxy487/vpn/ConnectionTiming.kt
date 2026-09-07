package net.megaproxy487.vpn

/** Wall time labels the session; monotonic time measures it across clock changes and sleep. */
data class ConnectionSession(val startedAtMillis: Long, val startedAtElapsedMillis: Long) {
    fun elapsedMillis(nowElapsedMillis: Long): Long = (nowElapsedMillis - startedAtElapsedMillis).coerceAtLeast(0)
}

fun connectionSessionForState(
    current: ConnectionSession?,
    connected: Boolean,
    wallTimeMillis: Long,
    elapsedRealtimeMillis: Long,
): ConnectionSession? = if (connected) current ?: ConnectionSession(wallTimeMillis, elapsedRealtimeMillis) else null

data class ConnectionDuration(val seconds: Long, val nextUpdateDelayMillis: Long)

fun connectionDuration(elapsedMillis: Long): ConnectionDuration {
    val elapsed = elapsedMillis.coerceAtLeast(0)
    val stepMillis = when {
        elapsed < 60_000 -> 10_000L
        elapsed < 600_000 -> 30_000L
        elapsed < 86_400_000 -> 60_000L
        else -> 3_600_000L
    }
    return ConnectionDuration(
        seconds = elapsed / stepMillis * (stepMillis / 1_000),
        nextUpdateDelayMillis = stepMillis - elapsed % stepMillis,
    )
}
