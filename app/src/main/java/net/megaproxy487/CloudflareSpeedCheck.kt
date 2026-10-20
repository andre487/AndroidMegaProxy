package net.megaproxy487

import android.net.Network
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mobile.Mobile
import mobile.SpeedNetwork
import net.megaproxy487.vpn.BridgeReporter

internal data class CloudflareRouteResult(
    val exitIp: String? = null,
    val countryCode: String? = null,
    val protocol: String? = null,
    val latencyMs: Long? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
)

internal suspend fun runCloudflareSpeedCheck(
    network: Network,
    megabytes: Int,
    preferredProtocol: String,
    onLog: suspend (String) -> Unit,
    onProgress: suspend (CloudflareRouteResult) -> Unit,
) = coroutineScope {
    require(megabytes in SPEED_TEST_MEGABYTES)
    require(preferredProtocol in SPEED_TEST_PROTOCOLS)
    val native = Mobile.newSpeedTest()
    val executor = Executors.newSingleThreadExecutor()
    val events = Channel<Pair<Boolean, String>>(Channel.UNLIMITED)
    val reader = launch {
        for ((isResult, message) in events) {
            if (isResult) onProgress(parseSpeedResult(message)) else onLog(message)
        }
    }
    fun reporter(isResult: Boolean) = BridgeReporter(
        enabled = { true },
        deliver = { events.trySend(isResult to it) },
        onFailure = { native.cancel() },
    )
    try {
        val failure = suspendCancellableCoroutine<Exception?> { continuation ->
            continuation.invokeOnCancellation { native.cancel() }
            executor.execute {
                val error = try {
                    native.run(object : SpeedNetwork {
                        override fun bind(fd: Long): Boolean {
                            if (fd !in 0..Int.MAX_VALUE.toLong()) return false
                            return try {
                                ParcelFileDescriptor.fromFd(fd.toInt()).use { network.bindSocket(it.fileDescriptor) }
                                true
                            } catch (_: Exception) { false }
                        }
                        override fun resolve(host: String): String =
                            network.getAllByName(host).mapNotNull { it.hostAddress }.joinToString("\n")
                    }, preferredProtocol, megabytes.toLong(), reporter(true), reporter(false))
                    null
                } catch (error: Exception) { error }
                continuation.resume(error)
            }
        }
        events.close()
        reader.join()
        failure?.let { throw it }
    } finally {
        native.cancel()
        events.close()
        withContext(NonCancellable) { reader.join() }
        executor.shutdown()
    }
}

internal fun parseSpeedResult(raw: String): CloudflareRouteResult {
    val json = org.json.JSONObject(raw)
    return CloudflareRouteResult(
        exitIp = if (json.has("exitIp")) json.getString("exitIp") else null,
        countryCode = if (json.has("countryCode")) json.getString("countryCode") else null,
        protocol = if (json.has("protocol")) json.getString("protocol") else null,
        latencyMs = if (json.has("latencyMs")) json.getLong("latencyMs") else null,
        downloadMbps = if (json.has("downloadMbps")) json.getDouble("downloadMbps") else null,
        uploadMbps = if (json.has("uploadMbps")) json.getDouble("uploadMbps") else null,
    )
}

internal val SPEED_TEST_MEGABYTES = listOf(10, 25, 50)
internal val SPEED_TEST_PROTOCOLS = listOf("h3", "h2", "h1")
