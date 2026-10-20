package net.megaproxy487

import android.net.Network
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

internal fun lookupExitIp(network: Network): String? = EXIT_IP_URLS.firstNotNullOfOrNull { url ->
    runCatching {
        val connection = network.openConnection(URL(url)) as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }.trim().takeIf(::isIpAddress)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

internal fun isIpAddress(value: String) = value.length in 7..45 &&
    value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' } &&
    runCatching { InetAddress.getByName(value) }.isSuccess

private val EXIT_IP_URLS = listOf(
    "https://ifconfig.me/ip",
    "https://api.ipify.org/",
    "https://icanhazip.com/",
)
