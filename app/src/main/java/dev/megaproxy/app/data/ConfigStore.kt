package dev.megaproxy.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.megaproxy.app.model.ProxyConfig
import dev.megaproxy.app.model.DnsProvider
import dev.megaproxy.app.model.TlsProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    fun load(): ProxyConfig = ProxyConfig(
        host = prefs.getString("host", "").orEmpty(),
        port = prefs.getInt("port", 443),
        username = prefs.getString("username", "").orEmpty(),
        password = decrypt(prefs.getString("password", null)),
        profile = runCatching {
            TlsProfile.valueOf(prefs.getString("profile", TlsProfile.CHROME_ANDROID.name)!!)
        }.getOrDefault(TlsProfile.CHROME_ANDROID),
        customJa3 = prefs.getString("custom_ja3", "").orEmpty(),
        dnsProvider = runCatching {
            DnsProvider.valueOf(prefs.getString("dns_provider", DnsProvider.CLOUDFLARE.name)!!)
        }.getOrDefault(DnsProvider.CLOUDFLARE),
        customDohUrl = prefs.getString("custom_doh_url", "").orEmpty(),
        selectedPackages = prefs.getStringSet("packages", emptySet())?.toSet().orEmpty(),
        allowIpv6 = prefs.getBoolean("allow_ipv6", false),
    )

    fun save(config: ProxyConfig) {
        prefs.edit()
            .putString("host", config.host.trim())
            .putInt("port", config.port)
            .putString("username", config.username)
            .putString("password", encrypt(config.password))
            .putString("profile", config.profile.name)
            .putString("custom_ja3", config.customJa3.trim())
            .putString("dns_provider", config.dnsProvider.name)
            .putString("custom_doh_url", config.customDohUrl.trim())
            .putStringSet("packages", config.selectedPackages)
            .putBoolean("allow_ipv6", config.allowIpv6)
            .apply()
    }

    fun isConnectionDesired(): Boolean = prefs.getBoolean("connection_desired", false)

    fun setConnectionDesired(desired: Boolean) {
        prefs.edit().putBoolean("connection_desired", desired).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packed: String?): String = runCatching {
        if (packed == null) return ""
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
    }.getOrDefault("")

    private companion object {
        const val KEY_ALIAS = "megaproxy.proxy.credentials.v1"
        const val IV_SIZE = 12
    }
}
