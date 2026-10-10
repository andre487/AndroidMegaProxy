package net.megaproxy487.data

import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi
import net.megaproxy487.uiText
import net.megaproxy487.localizedName

import android.content.Context
import android.content.SharedPreferences
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

data class ConfigurationImportResult(
    val added: List<ProxyProfile>,
    val updated: List<ProxyProfile>,
    val unchanged: List<ProxyProfile>,
    val missing: List<ProxyProfile>,
)

class ConfigStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    fun profiles(): List<ProxyProfile> = synchronized(storageLock) {
        ensureMigrated()
        val decoded = decodeProfiles(prefs.getString(PROFILES, null)).ifEmpty {
            // A parser failure must not destroy the only remaining recovery data.
            listOf(ProxyProfile(
                id = prefs.getString(ACTIVE_PROFILE_ID, null) ?: "unavailable-storage",
                name = context.uiText(R.string.profile_storage_unavailable),
                colorIndex = 0,
                config = ProxyConfig(storageUnavailable = true),
            ))
        }
        if (decoded.any { it.config.storageUnavailable }) return@synchronized decoded
        if (prefs.getInt(FLAG_COLOR_VERSION, 0) >= CURRENT_FLAG_COLOR_VERSION) return@synchronized decoded
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
        return@synchronized recolored
    }

    fun activeProfile(): ProxyProfile = profile(activeProfileId()) ?: profiles().first()

    fun alwaysOnProfile(): ProxyProfile = profile(alwaysOnProfileId()) ?: profiles().first()

    fun sortedProfiles(): List<ProxyProfile> = profiles()

    fun reorderProfiles(orderedIds: List<String>) = synchronized(storageLock) {
        val current = profiles()
        val byId = current.associateBy(ProxyProfile::id)
        val reordered = orderedIds.mapNotNull(byId::get) + current.filter { it.id !in orderedIds }
        if (reordered.map(ProxyProfile::id) == current.map(ProxyProfile::id)) return@synchronized
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
        if (profile(id) != null) prefs.edit().putString(ACTIVE_PROFILE_ID, id).commitOrThrow()
    }

    fun setAlwaysOnProfile(id: String) {
        if (profile(id) != null) prefs.edit()
            .putString(ALWAYS_ON_PROFILE_ID, id)
            .putBoolean(FAILOVER_ACTIVE, false)
            .commitOrThrow()
    }

    fun connectionProfile(): ProxyProfile =
        profile(prefs.getString(CONNECTION_PROFILE_ID, null).orEmpty()) ?: activeProfile()

    /** Identifier-only lookup that never decrypts or parses profile payloads. */
    fun connectionProfileId(): String =
        prefs.getString(CONNECTION_PROFILE_ID, null).orEmpty().ifEmpty(::activeProfileId)

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

    fun markPendingReconnect() = synchronized(storageLock) {
        prefs.edit().putString(PENDING_RECONNECT, UUID.randomUUID().toString()).apply()
    }

    fun clearPendingReconnect(appliedToken: String?) = synchronized(storageLock) {
        if (appliedToken != null && pendingReconnectToken() == appliedToken) {
            prefs.edit().remove(PENDING_RECONNECT).apply()
        }
    }

    fun globalConnectionSettings(): GlobalConnectionSettings = synchronized(storageLock) {
        ensureMigrated()
        val stored = prefs.getString(GLOBAL_CONNECTION_SETTINGS, null)
        if (stored != null) {
            migrateGlobalIpv6ToProfiles(stored)
            val decoded = decodeGlobalConnectionSettings(stored)
            if (!operationResult { JSONObject(stored).has("sshProfile") }.getOrDefault(false)) {
                val upgraded = decoded.copy(sshProfile = activeProfile().config.sshProfile)
                saveGlobalConnectionSettings(upgraded)
                return@synchronized upgraded
            }
            return@synchronized decoded
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
        return@synchronized migrated
    }

    private fun migrateGlobalIpv6ToProfiles(storedSettings: String) = synchronized(storageLock) {
        if (prefs.getBoolean(IPV6_PROFILE_MIGRATED, false)) return@synchronized
        val current = profiles()
        if (current.any { it.config.storageUnavailable }) return@synchronized
        val enabled = operationResult { JSONObject(storedSettings).optBoolean("allowIpv6", false) }.getOrDefault(false)
        writeProfiles(current.map { it.copy(config = it.config.copy(allowIpv6 = enabled)) })
        prefs.edit().putBoolean(IPV6_PROFILE_MIGRATED, true).apply()
    }

    fun saveGlobalConnectionSettings(settings: GlobalConnectionSettings) = synchronized(storageLock) {
        val normalized = settings.copy(
            tlsProfile = settings.tlsProfile.takeIf { it.available } ?: TlsProfile.DEFAULT,
            sshKeepaliveSeconds = settings.sshKeepaliveSeconds.coerceIn(0, 3600),
            sshMaxChannels = settings.sshMaxChannels.coerceIn(1, 256),
            sshRotationMinutes = settings.sshRotationMinutes.coerceIn(0, 1440),
            sshRotationMb = settings.sshRotationMb.coerceIn(0, 10240),
            failoverProfileIds = settings.failoverProfileIds.distinct(),
        )
        prefs.edit().putString(GLOBAL_CONNECTION_SETTINGS, encodeGlobalConnectionSettings(normalized)).commitOrThrow()
    }

    fun newProfileDraft(id: String = UUID.randomUUID().toString()): ProxyProfile = synchronized(storageLock) {
        val existing = profiles()
        val profile = ProxyProfile(
            id = id,
            colorIndex = nextColorIndex(existing),
            config = ProxyConfig(port = 443),
        )
        return@synchronized profile
    }

    fun addProfile(): ProxyProfile = synchronized(storageLock) {
        newProfileDraft().also { writeProfiles(profiles() + it) }
    }

    fun cloneProfile(id: String): ProxyProfile? = synchronized(storageLock) {
        val existing = profiles()
        val sourceIndex = existing.indexOfFirst { it.id == id }
        if (sourceIndex < 0) return@synchronized null
        val source = existing[sourceIndex]
        val clone = source.copy(
            id = UUID.randomUUID().toString(),
            name = context.uiText(R.string.profile_copy, source.localizedName(context)),
        )
        writeProfiles(existing.toMutableList().apply { add(sourceIndex + 1, clone) })
        return@synchronized clone
    }

    fun importProfiles(imported: List<ImportedProxy>): List<ProxyProfile> = synchronized(storageLock) {
        val existing = profiles()
        val allocated = existing.toMutableList()
        val changed = mutableListOf<ProxyProfile>()
        imported.forEach { source ->
            val matchIndex = allocated.indexOfFirst { it.importIdentity() == source.importIdentity() }
            if (matchIndex >= 0) {
                val current = allocated[matchIndex]
                val updated = current.copy(
                    name = source.name.ifBlank { current.name },
                    countryCode = source.countryCode.ifBlank { current.countryCode },
                    config = current.config.copy(
                        type = source.config.type,
                        host = source.config.host,
                        port = source.config.port,
                        username = source.config.username,
                        password = source.config.password.ifEmpty { current.config.password },
                    ),
                )
                allocated[matchIndex] = updated
                if (updated != current) changed += updated
            } else {
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
                changed += profile
            }
        }
        if (allocated != existing) writeProfiles(allocated)
        return@synchronized changed
    }

    fun importConfiguration(configuration: PortableConfiguration): ConfigurationImportResult = synchronized(storageLock) {
        val existing = profiles().filterNot { it.config.storageUnavailable }
        val existingById = existing.associateBy(ProxyProfile::id)
        val importedById = configuration.profiles.associateBy(ProxyProfile::id)
        val added = mutableListOf<ProxyProfile>()
        val updated = mutableListOf<ProxyProfile>()
        val unchanged = mutableListOf<ProxyProfile>()
        val merged = configuration.profiles.map { source ->
            val current = existingById[source.id]
            val resolved = retainProfileSecrets(source, current, configuration.secretPresence[source.id] ?: ProfileSecretPresence())
            when {
                current == null -> added += resolved
                current == resolved -> unchanged += resolved
                else -> updated += resolved
            }
            resolved
        }
        val missing = existing.filter { it.id !in importedById }
        requireUi(merged.isNotEmpty()) { UiException(R.string.error_config_no_usable) }
        val editor = prefs.edit().putString(PROFILES, encodeProfiles(mergeResolvedProfiles(existing, merged, added)))
            .putInt(DIAGNOSTIC_LOG_LIMIT_MB, configuration.diagnosticLogLimitMb)
        if (existing.isEmpty()) {
            val first = merged.first().id
            editor.putString(ACTIVE_PROFILE_ID, first)
                .putString(ALWAYS_ON_PROFILE_ID, first)
                .putString(CONNECTION_PROFILE_ID, first)
                .putBoolean(FAILOVER_ACTIVE, false)
                .remove(FAILOVER_NOTICE)
        }
        configuration.activeProfileId?.takeIf(importedById::containsKey)?.let { editor.putString(ACTIVE_PROFILE_ID, it) }
        configuration.alwaysOnProfileId?.takeIf(importedById::containsKey)?.let { editor.putString(ALWAYS_ON_PROFILE_ID, it) }
        configuration.globalConnectionSettings?.let { importedSettings ->
            editor.putBoolean(IPV6_PROFILE_MIGRATED, true)
                .putString(GLOBAL_CONNECTION_SETTINGS, encodeGlobalConnectionSettings(importedSettings.copy(
                    failoverProfileIds = importedSettings.failoverProfileIds.filter(importedById::containsKey),
                )))
        }
        if (configuration.subscriptionPresent) {
            val previous = subscriptionState()
            val settings = configuration.subscription?.retainPassword(previous?.settings)
            val owned = if (settings == null) emptySet() else importedById.keys +
                (previous?.takeIf { sameSubscriptionSource(it.settings, settings) }?.ownedIds ?: emptySet())
            putSubscription(editor, settings?.let { ConfigSubscriptionState(it, owned) })
        }
        commitTransaction(editor)
        net.megaproxy487.ConfigSubscriptions.schedule(context)
        return@synchronized ConfigurationImportResult(added, updated, unchanged, missing)
    }

    /** A lost Keystore key must never turn a saved subscription into an unauthenticated request. */
    fun subscriptionState(): ConfigSubscriptionState? = synchronized(storageLock) {
        val packed = prefs.getString(SUBSCRIPTION, null) ?: return@synchronized null
        val unreadable = mutableMapOf<String, String>()
        val plain = decryptStored(packed, "subscription", unreadable)
        requireUi(unreadable.isEmpty()) { UiException(R.string.error_config_storage) }
        ConfigSubscriptionState.fromJson(JSONObject(plain))
    }

    fun saveSubscription(settings: ConfigSubscription?) = synchronized(storageLock) {
        settings?.validate()
        // Explicit removal also recovers an unreadable subscription, without touching profiles.
        val previous = if (settings == null) null else subscriptionState()
        val resolved = settings
        val same = resolved != null && previous != null && sameSubscriptionSource(previous.settings, resolved)
        val state = resolved?.let { if (same) previous!!.copy(settings = it, generation = UUID.randomUUID().toString())
            else ConfigSubscriptionState(it) }
        val editor = prefs.edit()
        putSubscription(editor, state)
        commitTransaction(editor)
        net.megaproxy487.ConfigSubscriptions.schedule(context)
    }

    fun recordSubscriptionFailure(generation: String, now: Long) = synchronized(storageLock) {
        val state = subscriptionState()?.takeIf { it.generation == generation } ?: return@synchronized
        val editor = prefs.edit()
        putSubscription(editor, state.copy(lastAttempt = now, failed = true))
        commitTransaction(editor)
    }

    /** No native side effects: existing tunnels keep working until the user reconnects. */
    fun applySubscriptionSnapshot(
        generation: String, configuration: PortableConfiguration, sourceIndex: Int, now: Long,
        connectionRunning: Boolean,
    ): Boolean = synchronized(storageLock) {
        val state = subscriptionState()?.takeIf { it.generation == generation } ?: return@synchronized false
        val existing = profiles()
        requireUi(existing.none { it.config.storageUnavailable }) { UiException(R.string.error_config_storage) }
        requireUi(configuration.profiles.isNotEmpty()) { UiException(R.string.error_config_no_usable) }
        val local = existing.filter { it.id !in state.ownedIds }
        requireUi(configuration.profiles.none { source -> local.any { it.id == source.id } }) {
            UiException(R.string.subscription_id_collision)
        }
        val byId = existing.associateBy(ProxyProfile::id)
        val incoming = configuration.profiles.map { source -> retainProfileSecrets(source, byId[source.id],
            configuration.secretPresence[source.id] ?: ProfileSecretPresence()) }
        val merged = local + incoming
        val ids = merged.map(ProxyProfile::id).toSet()
        requireUi(ids.size == merged.size) { UiException(R.string.error_config_duplicate_ids) }
        val oldGlobal = globalConnectionSettings()
        val oldConnection = oldGlobal.applyTo(connectionProfile().config)
        val nextGlobal = (configuration.globalConnectionSettings ?: oldGlobal).let {
            it.copy(failoverProfileIds = it.failoverProfileIds.filter(ids::contains))
        }
        val preferred = configuration.activeProfileId?.takeIf { id -> incoming.any { it.id == id } } ?: incoming.first().id
        fun surviving(id: String) = id.takeIf(ids::contains) ?: preferred
        val nextActive = surviving(activeProfileId())
        val nextAlwaysOn = surviving(alwaysOnProfileId())
        val nextConnection = surviving(connectionProfileId())
        val newConnection = nextGlobal.applyTo(merged.first { it.id == nextConnection }.config)
        val editor = prefs.edit().putString(PROFILES, encodeProfiles(merged))
            .putString(ACTIVE_PROFILE_ID, nextActive).putString(ALWAYS_ON_PROFILE_ID, nextAlwaysOn)
            .putString(CONNECTION_PROFILE_ID, nextConnection)
            .putString(GLOBAL_CONNECTION_SETTINGS, encodeGlobalConnectionSettings(nextGlobal))
            .putBoolean(IPV6_PROFILE_MIGRATED, true)
        if (configuration.globalConnectionSettings != null) editor.putInt(DIAGNOSTIC_LOG_LIMIT_MB, configuration.diagnosticLogLimitMb)
        if ((connectionProfileId() !in ids || alwaysOnProfileId() !in ids) && isFailoverActive()) {
            editor.putBoolean(FAILOVER_ACTIVE, false).remove(FAILOVER_NOTICE)
        }
        if (connectionRunning && (oldConnection != newConnection || connectionProfileId() != nextConnection)) {
            editor.putString(PENDING_RECONNECT, UUID.randomUUID().toString())
        }
        putSubscription(editor, state.copy(ownedIds = incoming.map(ProxyProfile::id).toSet(),
            lastAttempt = now, lastSuccess = now, sourceIndex = sourceIndex, failed = false,
            warnings = configuration.skippedProfiles > 0 || configuration.notice.browserFields || configuration.notice.unknownFields))
        commitTransaction(editor)
        (state.ownedIds - ids).forEach { ConfigWrites.discard("profile:$it") }
        true
    }

    private fun putSubscription(editor: SharedPreferences.Editor, state: ConfigSubscriptionState?) {
        if (state == null) editor.remove(SUBSCRIPTION)
        else editor.putString(SUBSCRIPTION, encrypt(state.toJson().toString()))
    }

    /** SharedPreferences changes its memory before reporting a disk failure; restore both. */
    private fun commitTransaction(editor: SharedPreferences.Editor) {
        val before = prefs.all
        if (operationResult { editor.commit() }.getOrDefault(false)) return
        val rollback = prefs.edit().clear()
        before.forEach { (key, value) -> when (value) {
            is String -> rollback.putString(key, value)
            is Int -> rollback.putInt(key, value)
            is Long -> rollback.putLong(key, value)
            is Boolean -> rollback.putBoolean(key, value)
            is Float -> rollback.putFloat(key, value)
            is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        rollback.commit()
        throw UiException(R.string.error_config_storage)
    }

    private fun ProxyProfile.importIdentity(): String = config.importIdentity()

    private fun ImportedProxy.importIdentity(): String = config.importIdentity()

    private fun ProxyConfig.importIdentity(): String = listOf(
        type.name, host.trim().lowercase(), port.toString(), username,
        jumpHost.trim().lowercase(), jumpPort.toString(), jumpUsername,
    ).joinToString("\u0000")

    fun saveProfile(profile: ProxyProfile, createIfMissing: Boolean = false) = synchronized(storageLock) {
        val current = profiles()
        val updated = if (createIfMissing && current.none { it.id == profile.id }) current + profile
            else current.map { if (it.id == profile.id) profile else it }
        // A failed commit may already have updated SharedPreferences in memory. Retry the disk write too.
        writeProfiles(updated)
    }

    internal fun editProfile(id: String, draft: ProxyProfile?, edit: (ProxyProfile) -> ProxyProfile) = synchronized(storageLock) {
        val latest = profile(id) ?: draft ?: return@synchronized
        saveProfile(edit(latest), createIfMissing = draft != null)
    }

    internal fun editGlobalSettings(edit: (GlobalConnectionSettings) -> GlobalConnectionSettings) = synchronized(storageLock) {
        val updated = edit(globalConnectionSettings())
        val ids = profiles().map(ProxyProfile::id).toSet()
        saveGlobalConnectionSettings(updated.copy(failoverProfileIds = updated.failoverProfileIds.filter { it in ids }))
    }

    fun trustSshHostKey(profileId: String, hop: String, fingerprint: String): Boolean = synchronized(storageLock) {
        if (!fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{20,}={0,2}"))) return@synchronized false
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
        return@synchronized changed
    }

    fun deleteProfile(id: String): Boolean = synchronized(storageLock) {
        val current = profiles()
        if (current.size <= 1 || current.none { it.id == id }) return@synchronized false
        val connectionWasDeleted = connectionProfile().id == id
        val remaining = current.filterNot { it.id == id }
        val replacement = remaining.first().id
        val editor = prefs.edit().putString(PROFILES, encodeProfiles(remaining))
        if (activeProfileId() == id) editor.putString(ACTIVE_PROFILE_ID, replacement)
        if (alwaysOnProfileId() == id) editor.putString(ALWAYS_ON_PROFILE_ID, replacement)
        if (connectionWasDeleted) editor.putString(CONNECTION_PROFILE_ID, replacement)
        editor.commitOrThrow()
        ConfigWrites.discard("profile:$id")
        val settings = globalConnectionSettings()
        if (id in settings.failoverProfileIds) {
            saveGlobalConnectionSettings(settings.copy(failoverProfileIds = settings.failoverProfileIds - id))
        }
        if (connectionWasDeleted && isFailoverActive()) setFailoverState(false, null)
        return@synchronized true
    }

    fun deleteProfiles(ids: Set<String>): Boolean = synchronized(storageLock) {
        if (ids.isEmpty()) return@synchronized false
        val current = profiles()
        val remaining = current.filter { it.id !in ids }
        if (remaining.isEmpty() || remaining.size == current.size) return@synchronized false
        val removedIds = current.map(ProxyProfile::id).toSet() - remaining.map(ProxyProfile::id).toSet()
        val replacement = remaining.first().id
        val connectionWasDeleted = connectionProfileId() in removedIds
        val editor = prefs.edit().putString(PROFILES, encodeProfiles(remaining))
        if (activeProfileId() in removedIds) editor.putString(ACTIVE_PROFILE_ID, replacement)
        if (alwaysOnProfileId() in removedIds) editor.putString(ALWAYS_ON_PROFILE_ID, replacement)
        if (connectionWasDeleted) editor.putString(CONNECTION_PROFILE_ID, replacement)
        editor.commitOrThrow()
        removedIds.forEach { ConfigWrites.discard("profile:$it") }
        val settings = globalConnectionSettings()
        saveGlobalConnectionSettings(settings.copy(
            failoverProfileIds = settings.failoverProfileIds.filterNot(removedIds::contains),
        ))
        if (connectionWasDeleted && isFailoverActive()) setFailoverState(false, null)
        return@synchronized connectionWasDeleted
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
        prefs.edit().putInt(DIAGNOSTIC_LOG_LIMIT_MB, value.coerceIn(1, 100)).commitOrThrow()
    }

    private fun ensureMigrated() {
        if (prefs.contains(PROFILES)) return
        synchronized(storageLock) {
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

    private fun legacyConfig(): ProxyConfig {
        val unreadable = mutableMapOf<String, String>()
        return ProxyConfig(
            host = prefs.getString("host", "").orEmpty(),
            port = prefs.getInt("port", 443),
            username = prefs.getString("username", "").orEmpty(),
            password = decryptStored(prefs.getString("password", null), "password", unreadable),
            allowInvalidProxyCertificate = false,
            profile = enumValue(prefs.getString("profile", null), TlsProfile.DEFAULT),
            customJa3 = prefs.getString("custom_ja3", "").orEmpty(),
            dnsProvider = enumValue(prefs.getString("dns_provider", null), DnsProvider.CLOUDFLARE),
            customDohUrl = prefs.getString("custom_doh_url", "").orEmpty(),
            selectedPackages = prefs.getStringSet("packages", emptySet())?.toSet().orEmpty(),
            allowIpv6 = prefs.getBoolean("allow_ipv6", false),
            routeAllApps = true,
            bypassLocalNetworks = true,
            unreadableSecrets = unreadable.toMap(),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
        operationResult { enumValueOf<T>(value ?: default.name) }.getOrDefault(default)

    private fun nextColorIndex(profiles: List<ProxyProfile>): Int {
        val counts = IntArray(ProfileColors.argb.size)
        profiles.forEach { counts[Math.floorMod(it.colorIndex, counts.size)]++ }
        return counts.indices.minWithOrNull(compareBy<Int> { counts[it] }.thenBy { it }) ?: 0
    }

    private fun writeProfiles(profiles: List<ProxyProfile>) {
        prefs.edit().putString(PROFILES, encodeProfiles(profiles)).commitOrThrow()
    }

    private fun encodeProfiles(profiles: List<ProxyProfile>) = JSONArray().apply {
        requireUi(profiles.none { it.config.storageUnavailable }) {
            UiException(R.string.error_config_storage)
        }
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
        fun secret(name: String, value: String): String =
            if (value.isEmpty()) config.unreadableSecrets[name] ?: encrypt(value) else encrypt(value)
        put("type", config.type.name)
        put("host", config.host.trim())
        put("port", config.port)
        put("username", config.username)
        put("password", secret("password", config.password))
        put("privateKey", secret("privateKey", config.privateKey))
        put("sshProfile", config.sshProfile.name)
        put("trustedHostKey", config.trustedHostKey)
        put("acceptAnyHostKey", config.acceptAnyHostKey)
        put("jumpHost", config.jumpHost.trim())
        put("jumpPort", config.jumpPort)
        put("jumpUsername", config.jumpUsername)
        put("jumpPassword", secret("jumpPassword", config.jumpPassword))
        put("jumpPrivateKey", secret("jumpPrivateKey", config.jumpPrivateKey))
        put("jumpTrustedHostKey", config.jumpTrustedHostKey)
        put("jumpAllowInvalidProxyCertificate", config.jumpAllowInvalidProxyCertificate)
        put("jumpAcceptAnyHostKey", config.jumpAcceptAnyHostKey)
        put("sameJumpAuthentication", config.sameJumpAuthentication)
        put("allowInvalidProxyCertificate", config.allowInvalidProxyCertificate)
        put("preferHttp3", config.preferHttp3)
        put("fingerprint", config.profile.name)
        put("customJa3", config.customJa3.trim())
        put("dnsProvider", config.dnsProvider.name)
        put("customDohUrl", config.customDohUrl.trim())
        put("packages", JSONArray(config.selectedPackages.sorted()))
        put("allowIpv6", config.allowIpv6)
        put("routeAllApps", config.routeAllApps)
        put("bypassLocalNetworks", config.bypassLocalNetworks)
    }

    private fun decodeProfiles(value: String?): List<ProxyProfile> = operationResult {
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

    private fun decodeConfig(item: JSONObject): ProxyConfig {
        val unreadable = mutableMapOf<String, String>()
        fun secret(name: String) = decryptStored(item.optString(name).ifEmpty { null }, name, unreadable)
        return ProxyConfig(
            type = enumValue(item.optString("type"), ProxyType.HTTPS),
            host = item.optString("host"),
            port = item.optInt("port", 443),
            username = item.optString("username"),
            password = secret("password"),
            privateKey = secret("privateKey"),
            sshProfile = enumValue(item.optString("sshProfile"), SshProfile.DEFAULT),
            trustedHostKey = item.optString("trustedHostKey"),
            acceptAnyHostKey = item.optBoolean("acceptAnyHostKey", false),
            jumpHost = item.optString("jumpHost"),
            jumpPort = item.optInt("jumpPort", enumValue(item.optString("type"), ProxyType.HTTPS).defaultPort),
            jumpUsername = item.optString("jumpUsername"),
            jumpPassword = secret("jumpPassword"),
            jumpPrivateKey = secret("jumpPrivateKey"),
            jumpTrustedHostKey = item.optString("jumpTrustedHostKey"),
            jumpAllowInvalidProxyCertificate = item.optBoolean("jumpAllowInvalidProxyCertificate", false),
            jumpAcceptAnyHostKey = item.optBoolean("jumpAcceptAnyHostKey", false),
            sameJumpAuthentication = item.optBoolean("sameJumpAuthentication", true),
            allowInvalidProxyCertificate = item.optBoolean("allowInvalidProxyCertificate", false),
            preferHttp3 = item.optBoolean("preferHttp3", false),
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
            unreadableSecrets = unreadable.toMap(),
        )
    }

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

    private fun decodeGlobalConnectionSettings(value: String): GlobalConnectionSettings = operationResult {
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

    private var cachedKey: SecretKey? = null

    private fun key(createIfMissing: Boolean = true): SecretKey = synchronized(storageLock) {
        cachedKey?.let { return@synchronized it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { cachedKey = it; return@synchronized it }
        check(createIfMissing) { "Stored credential key is unavailable" }
        return@synchronized KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }.also { cachedKey = it }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decryptStored(packed: String?, name: String, unreadable: MutableMap<String, String>): String = operationResult {
        if (packed == null) return ""
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(createIfMissing = false), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
    }.getOrElse {
        if (packed != null) unreadable[name] = packed
        ""
    }

    private companion object {
        // ponytail: one lock per app process; split by store only if concurrent disk writes become a bottleneck.
        val storageLock = Any()
        const val KEY_ALIAS = "megaproxy.proxy.credentials.v1"
        const val IV_SIZE = 12
        const val PROFILES = "profiles_v2"
        private const val SUBSCRIPTION = "config_subscription_v1"
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

/** Call from the ordered I/O queue so a reported success includes the disk write. */
private fun SharedPreferences.Editor.commitOrThrow() {
    check(commit()) { "Configuration disk write failed" }
}
