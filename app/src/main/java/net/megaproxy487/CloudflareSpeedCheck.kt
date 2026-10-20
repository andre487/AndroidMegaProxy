package net.megaproxy487

import android.net.Network
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

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
    onProgress: suspend (CloudflareRouteResult) -> Unit,
): CloudflareRouteResult {
    require(megabytes in SPEED_TEST_MEGABYTES)
    val bytes = megabytes * 1_000_000
    val trace = requestText(network, CLOUDFLARE_TRACE_URL).let(::parseCloudflareTrace)
    var result = CloudflareRouteResult(
        exitIp = checkNotNull(trace["ip"]?.takeIf(::isIpAddress)) { "Cloudflare did not return an exit IP" },
        countryCode = trace["loc"].orEmpty(),
        protocol = listOfNotNull(trace["http"], trace["tls"]).joinToString(" · ").ifBlank { "—" },
    )
    onProgress(result)
    result = result.copy(latencyMs = List(3) { latency(network) }.sorted()[1])
    onProgress(result)
    val download = download(network, bytes)
    result = result.copy(downloadMbps = transferMbps(download.bytes, download.durationMs))
    onProgress(result)
    val upload = upload(network, bytes)
    result = result.copy(uploadMbps = transferMbps(upload.bytes, upload.durationMs))
    onProgress(result)
    return result
}

internal fun parseCloudflareTrace(body: String): Map<String, String> = body.lineSequence()
    .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.take(it) to line.drop(it + 1) } }
    .toMap()

internal fun transferMbps(bytes: Long, durationMs: Long): Double =
    if (bytes <= 0 || durationMs <= 0) 0.0 else bytes * 8_000.0 / durationMs / 1_000_000.0

private data class TimedTransfer(val bytes: Long, val durationMs: Long)

private fun connection(network: Network, url: String) =
    (network.openConnection(URL(url)) as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 120_000
        setRequestProperty("Accept-Encoding", "identity")
    }

private fun requestText(network: Network, url: String): String {
    val connection = connection(network, url)
    try {
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun latency(network: Network): Long {
    val connection = connection(network, "$CLOUDFLARE_DOWNLOAD_URL?bytes=0")
    try {
        val started = SystemClock.elapsedRealtime()
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        connection.inputStream.close()
        return SystemClock.elapsedRealtime() - started
    } finally {
        connection.disconnect()
    }
}

private fun download(network: Network, expectedBytes: Int): TimedTransfer {
    val connection = connection(network, "$CLOUDFLARE_DOWNLOAD_URL?bytes=$expectedBytes")
    try {
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        val started = SystemClock.elapsedRealtime()
        var received = 0L
        connection.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                received += count
            }
        }
        return TimedTransfer(received, SystemClock.elapsedRealtime() - started)
    } finally {
        connection.disconnect()
    }
}

private fun upload(network: Network, bytes: Int): TimedTransfer {
    val connection = connection(network, CLOUDFLARE_UPLOAD_URL).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/octet-stream")
        setFixedLengthStreamingMode(bytes)
    }
    try {
        val output = connection.outputStream
        val started = SystemClock.elapsedRealtime()
        output.use {
            val buffer = ByteArray(64 * 1024)
            var remaining = bytes
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size)
                it.write(buffer, 0, count)
                remaining -= count
            }
        }
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        connection.inputStream.close()
        return TimedTransfer(bytes.toLong(), SystemClock.elapsedRealtime() - started)
    } finally {
        connection.disconnect()
    }
}

private const val CLOUDFLARE_TRACE_URL = "https://speed.cloudflare.com/cdn-cgi/trace"
private const val CLOUDFLARE_DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
private const val CLOUDFLARE_UPLOAD_URL = "https://speed.cloudflare.com/__up"
internal val SPEED_TEST_MEGABYTES = listOf(10, 25, 50)
