package net.megaproxy487.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProfileColors
import net.megaproxy487.model.ProfileColorMatcher
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.SshProfile
import net.megaproxy487.model.SshAuthMode
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.model.GlobalConnectionSettings
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
        val decoded = decodeProfiles(prefs.getString(PROFILES, null)).ifEmpty {
            val recovered = createInitialProfile()
            prefs.edit()
                .putString(PROFILES, encodeProfiles(listOf(recovered)))
                .putString(ACTIVE_PROFILE_ID, recovered.id)
                .putString(ALWAYS_ON_PROFILE_ID, recovered.id)
                .putString(CONNECTION_PROFILE_ID, recovered.id)
                .putBoolean(FAILOVER_ACTIVE, false)
                .remove(FAILOVER_NOTICE)
                .apply()
            listOf(recovered)
        }
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

    fun sortedProfiles(): List<ProxyProfile> = profiles()

    @Synchronized
    fun reorderProfiles(orderedIds: List<String>) {
        val current = profiles()
        val byId = current.associateBy(ProxyProfile::id)
        val reordered = orderedIds.mapNotNull(byId::get) + current.filter { it.id !in orderedIds }
        if (reordered.map(ProxyProfile::id) == current.map(ProxyProfile::id)) return
        writeProfiles(reordered)
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
        if (profile(id) != null) prefs.edit()
            .putString(ALWAYS_ON_PROFILE_ID, id)
            .putBoolean(FAILOVER_ACTIVE, false)
            .apply()
    }

    fun connectionProfile(): ProxyProfile =
        profile(prefs.getString(CONNECTION_PROFILE_ID, null).orEmpty()) ?: activeProfile()

    fun connectionConfig(): ProxyConfig = globalConnectionSettings().applyTo(connectionProfile().config)

    fun setConnectionProfile(id: String) {
        if (profile(id) != null) prefs.edit().putString(CONNECTION_PROFILE_ID, id).apply()
    }

    fun failoverNotice(): String? = prefs.getString(FAILOVER_NOTICE, null)

    fun isFailoverActive(): Boolean = prefs.getBoolean(FAILOVER_ACTIVE, false)

    fun setFailoverState(active: Boolean, notice: String?) {
        prefs.edit().putBoolean(FAILOVER_ACTIVE, active).apply {
            if (notice == null) remove(FAILOVER_NOTICE) else putString(FAILOVER_NOTICE, notice)
        }.apply()
    }

    fun hasPendingReconnect(): Boolean = pendingReconnectToken() != null

    fun pendingReconnectToken(): String? = prefs.getString(PENDING_RECONNECT, null)

    fun markPendingReconnect() {
        prefs.edit().putString(PENDING_RECONNECT, UUID.randomUUID().toString()).apply()
    }

    fun clearPendingReconnect(appliedToken: String?) {
        if (appliedToken != null && pendingReconnectToken() == appliedToken) {
            prefs.edit().remove(PENDING_RECONNECT).apply()
        }
    }

    @Synchronized
    fun globalConnectionSettings(): GlobalConnectionSettings {
        ensureMigrated()
        val stored = prefs.getString(GLOBAL_CONNECTION_SETTINGS, null)
        if (stored != null) {
            migrateGlobalIpv6ToProfiles(stored)
            val decoded = decodeGlobalConnectionSettings(stored)
            if (!runCatching { JSONObject(stored).has("sshProfile") }.getOrDefault(false)) {
                val upgraded = decoded.copy(sshProfile = activeProfile().config.sshProfile)
                saveGlobalConnectionSettings(upgraded)
                return upgraded
            }
            return decoded
        }
        val source = activeProfile().config
        val migrated = GlobalConnectionSettings(
            tlsProfile = source.profile.takeIf { it.available } ?: TlsProfile.DEFAULT,
            sshProfile = source.sshProfile,
            customJa3 = source.customJa3,
            selectedPackages = source.selectedPackages,
            routeAllApps = source.routeAllApps,
            bypassLocalNetworks = source.bypassLocalNetworks,
        )
        saveGlobalConnectionSettings(migrated)
        prefs.edit().putBoolean(IPV6_PROFILE_MIGRATED, true).apply()
        return migrated
    }

    @Synchronized
    private fun migrateGlobalIpv6ToProfiles(storedSettings: String) {
        if (prefs.getBoolean(IPV6_PROFILE_MIGRATED, false)) return
        val enabled = runCatching { JSONObject(storedSettings).optBoolean("allowIpv6", false) }.getOrDefault(false)
        writeProfiles(profiles().map { it.copy(config = it.config.copy(allowIpv6 = enabled)) })
        prefs.edit().putBoolean(IPV6_PROFILE_MIGRATED, true).apply()
    }

    fun saveGlobalConnectionSettings(settings: GlobalConnectionSettings) {
        val normalized = settings.copy(
            tlsProfile = settings.tlsProfile.takeIf { it.available } ?: TlsProfile.DEFAULT,
            sshKeepaliveSeconds = settings.sshKeepaliveSeconds.coerceIn(0, 3600),
            sshMaxChannels = settings.sshMaxChannels.coerceIn(1, 256),
            sshRotationMinutes = settings.sshRotationMinutes.coerceIn(0, 1440),
            sshRotationMb = settings.sshRotationMb.coerceIn(0, 10240),
            failoverProfileIds = settings.failoverProfileIds.distinct(),
        )
        prefs.edit().putString(GLOBAL_CONNECTION_SETTINGS, encodeGlobalConnectionSettings(normalized)).apply()
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
        val sourceIndex = existing.indexOfFirst { it.id == id }
        if (sourceIndex < 0) return null
        val source = existing[sourceIndex]
        val clone = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.displayName} copy",
        )
        writeProfiles(existing.toMutableList().apply { add(sourceIndex + 1, clone) })
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
            .putInt(DIAGNOSTIC_LOG_LIMIT_MB, configuration.diagnosticLogLimitMb)
        configuration.activeProfileId?.let(idMap::get)?.let { editor.putString(ACTIVE_PROFILE_ID, it) }
        configuration.alwaysOnProfileId?.let(idMap::get)?.let { editor.putString(ALWAYS_ON_PROFILE_ID, it) }
        editor.apply()
        configuration.globalConnectionSettings?.let { importedSettings ->
            saveGlobalConnectionSettings(importedSettings.copy(
                failoverProfileIds = importedSettings.failoverProfileIds.mapNotNull(idMap::get),
            ))
        }
        return added
    }

    @Synchronized
    fun saveProfile(profile: ProxyProfile) {
        val current = profiles()
        val updated = current.map { if (it.id == profile.id) profile else it }
        if (updated != current) writeProfiles(updated)
    }

    @Synchronized
    fun trustSshHostKey(profileId: String, hop: String, fingerprint: String): Boolean {
        if (!fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{20,}={0,2}"))) return false
        val current = profiles()
        var changed = false
        val updated = current.map { profile ->
            if (profile.id != profileId) profile else {
                changed = true
                profile.copy(config = if (hop == "jump") profile.config.copy(
                    jumpTrustedHostKey = fingerprint, jumpAcceptAnyHostKey = false,
                ) else profile.config.copy(
                    trustedHostKey = fingerprint, acceptAnyHostKey = false,
                ))
            }
        }
        if (changed) writeProfiles(updated)
        return changed
    }

    @Synchronized
    fun deleteProfile(id: String): Boolean {
        val current = profiles()
        if (current.size <= 1 || current.none { it.id == id }) return false
        val connectionWasDeleted = connectionProfile().id == id
        val remaining = current.filterNot { it.id == id }
        val replacement = remaining.first().id
        val editor = prefs.edit().putString(PROFILES, encodeProfiles(remaining))
        if (activeProfileId() == id) editor.putString(ACTIVE_PROFILE_ID, replacement)
        if (alwaysOnProfileId() == id) editor.putString(ALWAYS_ON_PROFILE_ID, replacement)
        if (connectionWasDeleted) editor.putString(CONNECTION_PROFILE_ID, replacement)
        editor.apply()
        val settings = globalConnectionSettings()
        if (id in settings.failoverProfileIds) {
            saveGlobalConnectionSettings(settings.copy(failoverProfileIds = settings.failoverProfileIds - id))
        }
        if (connectionWasDeleted && isFailoverActive()) setFailoverState(false, null)
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
        profile = enumValue(prefs.getString("profile", null), TlsProfile.DEFAULT),
        customJa3 = prefs.getString("custom_ja3", "").orEmpty(),
        dnsProvider = enumValue(prefs.getString("dns_provider", null), DnsProvider.CLOUDFLARE),
        customDohUrl = prefs.getString("custom_doh_url", "").orEmpty(),
        selectedPackages = prefs.getStringSet("packages", emptySet())?.toSet().orEmpty(),
        allowIpv6 = prefs.getBoolean("allow_ipv6", false),
        routeAllApps = true,
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
        put("type", config.type.name)
        put("host", config.host.trim())
        put("port", config.port)
        put("username", config.username)
        put("password", encrypt(config.password))
        put("privateKey", encrypt(config.privateKey))
        put("sshProfile", config.sshProfile.name)
        put("trustedHostKey", config.trustedHostKey)
        put("acceptAnyHostKey", config.acceptAnyHostKey)
        put("jumpHost", config.jumpHost.trim())
        put("jumpPort", config.jumpPort)
        put("jumpUsername", config.jumpUsername)
        put("jumpPassword", encrypt(config.jumpPassword))
        put("jumpPrivateKey", encrypt(config.jumpPrivateKey))
        put("jumpTrustedHostKey", config.jumpTrustedHostKey)
        put("jumpAcceptAnyHostKey", config.jumpAcceptAnyHostKey)
        put("sameJumpAuthentication", config.sameJumpAuthentication)
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
        type = enumValue(item.optString("type"), ProxyType.HTTPS),
        host = item.optString("host"),
        port = item.optInt("port", 443),
        username = item.optString("username"),
        password = decrypt(item.optString("password").ifEmpty { null }),
        privateKey = decrypt(item.optString("privateKey").ifEmpty { null }),
        sshProfile = enumValue(item.optString("sshProfile"), SshProfile.DEFAULT),
        trustedHostKey = item.optString("trustedHostKey"),
        acceptAnyHostKey = item.optBoolean("acceptAnyHostKey", false),
        jumpHost = item.optString("jumpHost"),
        jumpPort = item.optInt("jumpPort", 22),
        jumpUsername = item.optString("jumpUsername"),
        jumpPassword = decrypt(item.optString("jumpPassword").ifEmpty { null }),
        jumpPrivateKey = decrypt(item.optString("jumpPrivateKey").ifEmpty { null }),
        jumpTrustedHostKey = item.optString("jumpTrustedHostKey"),
        jumpAcceptAnyHostKey = item.optBoolean("jumpAcceptAnyHostKey", false),
        sameJumpAuthentication = item.optBoolean("sameJumpAuthentication", true),
        allowInvalidProxyCertificate = item.optBoolean("allowInvalidProxyCertificate", false),
        profile = enumValue(item.optString("fingerprint"), TlsProfile.DEFAULT),
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

    private fun encodeGlobalConnectionSettings(settings: GlobalConnectionSettings) = JSONObject().apply {
        put("fingerprint", settings.tlsProfile.name)
        put("sshProfile", settings.sshProfile.name)
        put("sshAuthMode", settings.sshAuthMode.name)
        put("sshKeepaliveSeconds", settings.sshKeepaliveSeconds)
        put("sshMaxChannels", settings.sshMaxChannels)
        put("sshRotationMinutes", settings.sshRotationMinutes)
        put("sshRotationMb", settings.sshRotationMb)
        put("failoverMode", settings.failoverMode.name)
        put("failoverProfileIds", JSONArray(settings.failoverProfileIds))
        put("customJa3", settings.customJa3.trim())
        put("packages", JSONArray(settings.selectedPackages.sorted()))
        put("routeAllApps", settings.routeAllApps)
        put("bypassLocalNetworks", settings.bypassLocalNetworks)
    }.toString()

    private fun decodeGlobalConnectionSettings(value: String): GlobalConnectionSettings = runCatching {
        val item = JSONObject(value)
        val parsedTls = enumValue(item.optString("fingerprint"), TlsProfile.DEFAULT)
        GlobalConnectionSettings(
            tlsProfile = parsedTls.takeIf { it.available } ?: TlsProfile.DEFAULT,
            sshProfile = enumValue(item.optString("sshProfile"), SshProfile.DEFAULT),
            sshAuthMode = enumValue(item.optString("sshAuthMode"), SshAuthMode.AUTO),
            sshKeepaliveSeconds = item.optInt("sshKeepaliveSeconds", 30).coerceIn(0, 3600),
            sshMaxChannels = item.optInt("sshMaxChannels", 32).coerceIn(1, 256),
            sshRotationMinutes = item.optInt("sshRotationMinutes", 0).coerceIn(0, 1440),
            sshRotationMb = item.optInt("sshRotationMb", 0).coerceIn(0, 10240),
            failoverMode = enumValue(item.optString("failoverMode"), FailoverMode.DISABLED),
            failoverProfileIds = item.optJSONArray("failoverProfileIds")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty(),
            customJa3 = item.optString("customJa3"),
            selectedPackages = item.optJSONArray("packages")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.orEmpty(),
            routeAllApps = item.optBoolean("routeAllApps", true),
            bypassLocalNetworks = item.optBoolean("bypassLocalNetworks", true),
        )
    }.getOrDefault(GlobalConnectionSettings())

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
        private const val FAILOVER_ACTIVE = "failover_active"
        private const val FAILOVER_NOTICE = "failover_notice"
        const val CONNECTION_PROFILE_ID = "connection_profile_id"
        const val FLAG_COLOR_VERSION = "flag_color_version"
        const val CURRENT_FLAG_COLOR_VERSION = 1
        const val DIAGNOSTIC_LOG_LIMIT_MB = "diagnostic_log_limit_mb"
        const val GLOBAL_CONNECTION_SETTINGS = "global_connection_settings_v1"
        private const val IPV6_PROFILE_MIGRATED = "ipv6_profile_migrated_v1"
        private const val PENDING_RECONNECT = "pending_reconnect"
    }
}
