package net.megaproxy487.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProfileColors
import net.megaproxy487.model.ProfileColorMatcher
import net.megaproxy487.model.ProfileSort
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.TlsProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    @Synchronized
    fun profiles(): List<ProxyProfile> {
        ensureMigrated()
        val decoded = decodeProfiles(prefs.getString(PROFILES, null)).ifEmpty { listOf(createInitialProfile()) }
        if (prefs.getInt(FLAG_COLOR_VERSION, 0) >= CURRENT_FLAG_COLOR_VERSION) return decoded
        val recolored = decoded.map { profile ->
            if (profile.countryCode.isEmpty()) profile
            else profile.copy(
                colorIndex = ProfileColorMatcher.colorIndexForFlag(profile.countryCode, profile.colorIndex),
            )
        }
        prefs.edit()
            .putString(PROFILES, encodeProfiles(recolored))
            .putInt(FLAG_COLOR_VERSION, CURRENT_FLAG_COLOR_VERSION)
            .apply()
        return recolored
    }

    fun activeProfile(): ProxyProfile = profile(activeProfileId()) ?: profiles().first()

    fun alwaysOnProfile(): ProxyProfile = profile(alwaysOnProfileId()) ?: profiles().first()

    fun profileSort(): ProfileSort = enumValue(prefs.getString(PROFILE_SORT, null), ProfileSort.NAME)

    fun isProfileSortAscending(): Boolean = prefs.getBoolean(PROFILE_SORT_ASCENDING, true)

    fun setProfileSort(sort: ProfileSort, ascending: Boolean) {
        prefs.edit()
            .putString(PROFILE_SORT, sort.name)
            .putBoolean(PROFILE_SORT_ASCENDING, ascending)
            .apply()
    }

    fun sortedProfiles(): List<ProxyProfile> {
        val comparator = when (profileSort()) {
            ProfileSort.NAME -> compareBy<ProxyProfile> { it.displayName.lowercase() }
            ProfileSort.HOST -> compareBy { it.config.host.lowercase() }
            ProfileSort.COUNTRY -> compareBy<ProxyProfile> { it.countryCode }.thenBy { it.displayName.lowercase() }
        }
        return profiles().sortedWith(if (isProfileSortAscending()) comparator else comparator.reversed())
    }

    fun profile(id: String): ProxyProfile? = profiles().firstOrNull { it.id == id }

    fun activeProfileId(): String {
        ensureMigrated()
        return prefs.getString(ACTIVE_PROFILE_ID, null) ?: profiles().first().id
    }

    fun alwaysOnProfileId(): String {
        ensureMigrated()
        return prefs.getString(ALWAYS_ON_PROFILE_ID, null) ?: activeProfileId()
    }

    fun setActiveProfile(id: String) {
        if (profile(id) != null) prefs.edit().putString(ACTIVE_PROFILE_ID, id).apply()
    }

    fun setAlwaysOnProfile(id: String) {
        if (profile(id) != null) prefs.edit().putString(ALWAYS_ON_PROFILE_ID, id).apply()
    }

    fun connectionProfile(): ProxyProfile =
        profile(prefs.getString(CONNECTION_PROFILE_ID, null).orEmpty()) ?: activeProfile()

    fun setConnectionProfile(id: String) {
        if (profile(id) != null) prefs.edit().putString(CONNECTION_PROFILE_ID, id).apply()
    }

    @Synchronized
    fun addProfile(): ProxyProfile {
        val existing = profiles()
        val profile = ProxyProfile(
            id = UUID.randomUUID().toString(),
            colorIndex = nextColorIndex(existing),
            config = ProxyConfig(port = 443),
        )
        writeProfiles(existing + profile)
        return profile
    }

    @Synchronized
    fun cloneProfile(id: String): ProxyProfile? {
        val existing = profiles()
        val source = existing.firstOrNull { it.id == id } ?: return null
        val clone = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.displayName} copy",
        )
        writeProfiles(existing + clone)
        return clone
    }

    @Synchronized
    fun importProfiles(imported: List<ImportedProxy>): List<ProxyProfile> {
        val existing = profiles()
        val allocated = existing.toMutableList()
        val added = imported.map { source ->
            val profile = ProxyProfile(
                id = UUID.randomUUID().toString(),
                name = source.name,
                colorIndex = ProfileColorMatcher.colorIndexForFlag(
                    source.countryCode,
                    nextColorIndex(allocated),
                ),
                countryCode = source.countryCode,
                config = source.config,
            )
            allocated += profile
            profile
        }
        writeProfiles(existing + added)
        return added
    }

    @Synchronized
    fun importConfiguration(configuration: PortableConfiguration): List<ProxyProfile> {
        val existing = profiles()
        val idMap = mutableMapOf<String, String>()
        val added = configuration.profiles.map { source ->
            val newId = UUID.randomUUID().toString()
            idMap[source.id] = newId
            source.copy(id = newId)
        }
        writeProfiles(existing + added)
        val editor = prefs.edit()
            .putString(PROFILE_SORT, configuration.sort.name)
            .putBoolean(PROFILE_SORT_ASCENDING, configuration.sortAscending)
            .putInt(DIAGNOSTIC_LOG_LIMIT_MB, configuration.diagnosticLogLimitMb)
        configuration.activeProfileId?.let(idMap::get)?.let { editor.putString(ACTIVE_PROFILE_ID, it) }
        configuration.alwaysOnProfileId?.let(idMap::get)?.let { editor.putString(ALWAYS_ON_PROFILE_ID, it) }
        editor.apply()
        return added
    }

    @Synchronized
    fun saveProfile(profile: ProxyProfile) {
        val current = profiles()
        val updated = current.map { if (it.id == profile.id) profile else it }
        if (updated != current) writeProfiles(updated)
    }

    @Synchronized
    fun deleteProfile(id: String): Boolean {
        val current = profiles()
        if (current.size <= 1 || current.none { it.id == id }) return false
        val remaining = current.filterNot { it.id == id }
        val replacement = remaining.first().id
        val editor = prefs.edit().putString(PROFILES, encodeProfiles(remaining))
        if (activeProfileId() == id) editor.putString(ACTIVE_PROFILE_ID, replacement)
        if (alwaysOnProfileId() == id) editor.putString(ALWAYS_ON_PROFILE_ID, replacement)
        editor.apply()
        return true
    }

    /** Compatibility accessor for callers that operate on the selected profile. */
    fun load(): ProxyConfig = activeProfile().config

    /** Compatibility writer for per-profile settings screens. */
    fun save(config: ProxyConfig) = saveProfile(activeProfile().copy(config = config))

    fun isConnectionDesired(): Boolean = prefs.getBoolean("connection_desired", false)

    fun setConnectionDesired(desired: Boolean) {
        prefs.edit().putBoolean("connection_desired", desired).apply()
    }

    fun diagnosticLogLimitMb(): Int = prefs.getInt(DIAGNOSTIC_LOG_LIMIT_MB, 3).coerceIn(1, 100)

    fun setDiagnosticLogLimitMb(value: Int) {
        prefs.edit().putInt(DIAGNOSTIC_LOG_LIMIT_MB, value.coerceIn(1, 100)).apply()
    }

    private fun ensureMigrated() {
        if (prefs.contains(PROFILES)) return
        synchronized(prefs) {
            if (prefs.contains(PROFILES)) return
            val profile = createInitialProfile()
            prefs.edit()
                .putString(PROFILES, encodeProfiles(listOf(profile)))
                .putString(ACTIVE_PROFILE_ID, profile.id)
                .putString(ALWAYS_ON_PROFILE_ID, profile.id)
                .apply()
        }
    }

    private fun createInitialProfile(): ProxyProfile = ProxyProfile(
        id = UUID.randomUUID().toString(),
        colorIndex = 0,
        config = legacyConfig(),
    )

    private fun legacyConfig() = ProxyConfig(
        host = prefs.getString("host", "").orEmpty(),
        port = prefs.getInt("port", 443),
        username = prefs.getString("username", "").orEmpty(),
        password = decrypt(prefs.getString("password", null)),
        allowInvalidProxyCertificate = false,
        profile = enumValue(prefs.getString("profile", null), TlsProfile.CHROME_ANDROID),
        customJa3 = prefs.getString("custom_ja3", "").orEmpty(),
        dnsProvider = enumValue(prefs.getString("dns_provider", null), DnsProvider.CLOUDFLARE),
        customDohUrl = prefs.getString("custom_doh_url", "").orEmpty(),
        selectedPackages = prefs.getStringSet("packages", emptySet())?.toSet().orEmpty(),
        allowIpv6 = prefs.getBoolean("allow_ipv6", false),
        routeAllApps = false,
        bypassLocalNetworks = true,
    )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value ?: default.name) }.getOrDefault(default)

    private fun nextColorIndex(profiles: List<ProxyProfile>): Int {
        val counts = IntArray(ProfileColors.argb.size)
        profiles.forEach { counts[Math.floorMod(it.colorIndex, counts.size)]++ }
        return counts.indices.minWithOrNull(compareBy<Int> { counts[it] }.thenBy { it }) ?: 0
    }

    private fun writeProfiles(profiles: List<ProxyProfile>) {
        prefs.edit().putString(PROFILES, encodeProfiles(profiles)).apply()
    }

    private fun encodeProfiles(profiles: List<ProxyProfile>) = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name.trim())
                put("color", profile.colorIndex)
                put("countryCode", profile.countryCode.uppercase())
                put("config", encodeConfig(profile.config))
            })
        }
    }.toString()

    private fun encodeConfig(config: ProxyConfig) = JSONObject().apply {
        put("host", config.host.trim())
        put("port", config.port)
        put("username", config.username)
        put("password", encrypt(config.password))
        put("allowInvalidProxyCertificate", config.allowInvalidProxyCertificate)
        put("fingerprint", config.profile.name)
        put("customJa3", config.customJa3.trim())
        put("dnsProvider", config.dnsProvider.name)
        put("customDohUrl", config.customDohUrl.trim())
        put("packages", JSONArray(config.selectedPackages.sorted()))
        put("allowIpv6", config.allowIpv6)
        put("routeAllApps", config.routeAllApps)
        put("bypassLocalNetworks", config.bypassLocalNetworks)
    }

    private fun decodeProfiles(value: String?): List<ProxyProfile> = runCatching {
        val array = JSONArray(value ?: return emptyList())
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ProxyProfile(
                id = item.getString("id"),
                name = item.optString("name"),
                colorIndex = item.optInt("color", index),
                countryCode = item.optString("countryCode"),
                config = decodeConfig(item.getJSONObject("config")),
            )
        }
    }.getOrDefault(emptyList())

    private fun decodeConfig(item: JSONObject) = ProxyConfig(
        host = item.optString("host"),
        port = item.optInt("port", 443),
        username = item.optString("username"),
        password = decrypt(item.optString("password").ifEmpty { null }),
        allowInvalidProxyCertificate = item.optBoolean("allowInvalidProxyCertificate", false),
        profile = enumValue(item.optString("fingerprint"), TlsProfile.CHROME_ANDROID),
        customJa3 = item.optString("customJa3"),
        dnsProvider = enumValue(item.optString("dnsProvider"), DnsProvider.CLOUDFLARE),
        customDohUrl = item.optString("customDohUrl"),
        selectedPackages = item.optJSONArray("packages")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.orEmpty(),
        allowIpv6 = item.optBoolean("allowIpv6", false),
        routeAllApps = item.optBoolean("routeAllApps", false),
        bypassLocalNetworks = item.optBoolean("bypassLocalNetworks", true),
    )

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
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
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
        const val PROFILES = "profiles_v2"
        const val ACTIVE_PROFILE_ID = "active_profile_id"
        const val ALWAYS_ON_PROFILE_ID = "always_on_profile_id"
        const val CONNECTION_PROFILE_ID = "connection_profile_id"
        const val FLAG_COLOR_VERSION = "flag_color_version"
        const val CURRENT_FLAG_COLOR_VERSION = 1
        const val PROFILE_SORT = "profile_sort"
        const val PROFILE_SORT_ASCENDING = "profile_sort_ascending"
        const val DIAGNOSTIC_LOG_LIMIT_MB = "diagnostic_log_limit_mb"
    }
}
