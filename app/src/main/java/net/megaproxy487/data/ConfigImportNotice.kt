package net.megaproxy487.data

import org.json.JSONArray
import org.json.JSONObject

data class ConfigImportNotice(
    val browserFields: Boolean = false,
    val unknownFields: Boolean = false,
)

/** Field recognition only: canonical schema validation belongs to export tests, not legacy imports. */
internal object ConfigImportNotices {
    private val schema by lazy {
        JSONObject(checkNotNull(javaClass.getResourceAsStream("/megaproxy-v8.schema.json"))
            .bufferedReader().use { it.readText() })
    }

    fun inspect(root: JSONObject): ConfigImportNotice {
        var unknown = false
        fun walk(value: Any?, definition: JSONObject) {
            val objectDefinition = if (value is JSONObject) definition.optJSONArray("anyOf")?.let { variants ->
                (0 until variants.length()).mapNotNull(variants::optJSONObject)
                    .firstOrNull { it.has("\$ref") || it.has("properties") }
            } ?: definition else definition
            val reference = objectDefinition.optString("\$ref")
            val resolved = if (reference.isEmpty()) objectDefinition
                else schema.getJSONObject("\$defs").getJSONObject(reference.substringAfterLast('/'))
            when (value) {
                is JSONObject -> {
                    val properties = resolved.optJSONObject("properties")
                    value.keys().forEach { key ->
                        val child = properties?.optJSONObject(key)
                        if (child == null) unknown = true else walk(value.opt(key), child)
                    }
                }
                is JSONArray -> resolved.optJSONObject("items")?.let { item ->
                    for (index in 0 until value.length()) walk(value.opt(index), item)
                }
            }
        }
        walk(root, schema)
        val profiles = root.optJSONArray("profiles")
        val browser = root.has("browser") || root.has("subscription") || profiles?.let { array ->
            (0 until array.length()).any { array.optJSONObject(it)?.has("browser") == true }
        } == true
        return ConfigImportNotice(browserFields = browser, unknownFields = unknown)
    }
}
