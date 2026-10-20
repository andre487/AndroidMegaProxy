package net.megaproxy487

import android.net.Network
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

internal data class CloudflareRouteResult(
    val exitIp: String,
    val countryCode: String,
    val protocol: String,
    val latencyMs: Long,
    val downloadMbps: Double,
)

internal fun runCloudflareSpeedCheck(network: Network): CloudflareRouteResult {
    val trace = request(network, CLOUDFLARE_TRACE_URL).body.toString(Charsets.UTF_8).let(::parseCloudflareTrace)
    val latency = List(3) { request(network, "$CLOUDFLARE_DOWNLOAD_URL?bytes=0").headersMs }.sorted()[1]
    val download = request(network, "$CLOUDFLARE_DOWNLOAD_URL?bytes=$DOWNLOAD_BYTES")
    return CloudflareRouteResult(
        exitIp = checkNotNull(trace["ip"]?.takeIf(::isIpAddress)) { "Cloudflare did not return an exit IP" },
        countryCode = trace["loc"].orEmpty(),
        protocol = listOfNotNull(trace["http"], trace["tls"]).joinToString(" · ").ifBlank { "—" },
        latencyMs = latency,
        downloadMbps = downloadMbps(download.body.size.toLong(), download.bodyMs),
    )
}

internal fun parseCloudflareTrace(body: String): Map<String, String> = body.lineSequence()
    .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.take(it) to line.drop(it + 1) } }
    .toMap()

internal fun downloadMbps(bytes: Long, durationMs: Long): Double =
    if (bytes <= 0 || durationMs <= 0) 0.0 else bytes * 8_000.0 / durationMs / 1_000_000.0

private data class TimedResponse(val body: ByteArray, val headersMs: Long, val bodyMs: Long)

private fun request(network: Network, url: String): TimedResponse {
    val connection = network.openConnection(URL(url)) as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        val requestStarted = SystemClock.elapsedRealtime()
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        val headersReceived = SystemClock.elapsedRealtime()
        val body = connection.inputStream.use { it.readBytes() }
        val finished = SystemClock.elapsedRealtime()
        return TimedResponse(body, headersReceived - requestStarted, finished - headersReceived)
    } finally {
        connection.disconnect()
    }
}

private const val CLOUDFLARE_TRACE_URL = "https://speed.cloudflare.com/cdn-cgi/trace"
private const val CLOUDFLARE_DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
private const val DOWNLOAD_BYTES = 10 * 1024 * 1024
