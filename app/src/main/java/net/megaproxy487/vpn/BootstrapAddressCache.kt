package net.megaproxy487.vpn

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

class BootstrapAddressCache(context: Context) {
    private val preferences = context.getSharedPreferences("bootstrap_address_cache", Context.MODE_PRIVATE)

    fun get(host: String, now: Long = System.currentTimeMillis()): String? {
        val value = preferences.getString(key(host), null) ?: return null
        return runCatching {
            val json = JSONObject(value)
            val address = json.getString("address")
            val savedAt = json.getLong("savedAt")
            address.takeIf { now - savedAt in 0..MAX_AGE_MS && isNumericIpv4(it) }
        }.getOrNull()
    }

    fun put(host: String, address: String, now: Long = System.currentTimeMillis()) {
        if (!isNumericIpv4(address)) return
        preferences.edit().putString(
            key(host),
            JSONObject().put("address", address).put("savedAt", now).toString(),
        ).apply()
    }

    fun remove(host: String) {
        preferences.edit().remove(key(host)).apply()
    }

    private fun key(host: String): String = MessageDigest.getInstance("SHA-256")
        .digest(host.trim().lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    // Bootstrap DoH deliberately requests an A record. Keeping this parser IPv4-only
    // avoids both a compatibility dependency on API 29 and accidental DNS lookups.
    private fun isNumericIpv4(address: String): Boolean {
        val octets = address.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() &&
                octet.length <= 3 &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull() in 0..255
        }
    }

    private companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
