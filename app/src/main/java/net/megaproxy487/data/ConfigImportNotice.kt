package net.megaproxy487.data

import org.json.JSONArray
import org.json.JSONObject

data class ConfigImportNotice(
    val browserFields: Boolean = false,
    val unknownFields: Boolean = false,
)

internal fun JSONObject.androidSection(): JSONObject? = optJSONObject("android")?.takeIf {
    !it.has("version") || it.opt("version") == 1
}

/** Field recognition only: canonical schema validation belongs to export tests, not legacy imports. */
internal object ConfigImportNotices {
    private val schema by lazy {
        JSONObject(checkNotNull(javaClass.getResourceAsStream("/megaproxy-v8.schema.json"))
            .bufferedReader().use { it.readText() })
    }

    // The common schema leaves platform extensions opaque. Recognize fields understood
    // by Android and browser fields for which the import UI has a dedicated notice.
    private val extensionFields = mapOf(
        "" to setOf("android", "browser", "alwaysOnProfileId"),
        "android" to setOf("version", "tls"),
        "android.tls" to setOf("fingerprint"),
        "routing" to setOf("routeAllApps", "selectedPackages"),
        "profiles.routing" to setOf("routeAllApps", "selectedPackages"),
        "profiles" to setOf("browser"),
        "profiles.browser" to setOf("knockHost", "bypass", "authMode", "masqueTemplate"),
        "browser" to setOf("theme", "language", "routing", "webRTC"),
        "browser.routing" to setOf("enabled", "mode", "domains", "sites", "subscriptions", "assignments", "strategy"),
        "browser.routing.assignments" to setOf("domain", "profileId", "includeSubdomains"),
        "browser.routing.subscriptions" to setOf("domainSources", "siteSources", "autoUpdate", "throughProxy"),
    )

    fun inspect(root: JSONObject): ConfigImportNotice {
        var unknown = root.has("android") && root.androidSection() == null
        fun walk(value: Any?, definition: JSONObject?, path: String) {
            val objectDefinition = if (value is JSONObject) definition?.optJSONArray("anyOf")?.let { variants ->
                (0 until variants.length()).mapNotNull(variants::optJSONObject)
                    .firstOrNull { it.has("\$ref") || it.has("properties") }
            } ?: definition else definition
            val reference = objectDefinition?.optString("\$ref").orEmpty()
            val resolved = if (reference.isEmpty()) objectDefinition
                else schema.getJSONObject("\$defs").getJSONObject(reference.substringAfterLast('/'))
            when (value) {
                is JSONObject -> {
                    val properties = resolved?.optJSONObject("properties")
                    value.keys().forEach { key ->
                        val child = properties?.optJSONObject(key)
                        if ((path == "profiles.proxy" && key == "preferHttp2") ||
                            ((path == "routing" || path == "profiles.routing") && key == "systemAllowIpv6") ||
                            (child == null && extensionFields[path]?.contains(key) != true)) unknown = true
                        else walk(value.opt(key), child, if (path.isEmpty()) key else "$path.$key")
                    }
                }
                is JSONArray -> {
                    val item = resolved?.optJSONObject("items")
                    if (item != null || path == "browser.routing.assignments") {
                        for (index in 0 until value.length()) {
                            val entry = value.opt(index)
                            if (entry is JSONObject) walk(entry, item, path)
                        }
                    }
                }
            }
        }
        walk(root, schema, "")
        val profiles = root.optJSONArray("profiles")
        val browser = root.has("browser") || profiles?.let { array ->
            (0 until array.length()).any { index ->
                val profile = array.optJSONObject(index)
                val browser = profile?.optJSONObject("browser")
                val supported = profile?.optJSONObject("proxy")?.optString("type", "HTTPS") == "HTTPS"
                if (browser != null) browser.keys().asSequence().any { it != "knockHost" || !supported }
                else profile?.has("browser") == true
            }
        } == true
        return ConfigImportNotice(browserFields = browser, unknownFields = unknown)
    }
}
