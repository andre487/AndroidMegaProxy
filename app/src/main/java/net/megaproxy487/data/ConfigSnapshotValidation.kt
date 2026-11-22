package net.megaproxy487.data

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi
import org.json.JSONObject

private object SubscriptionSchema {
    val mapper = ObjectMapper()
    val v8 by lazy { load(8) }
    val current by lazy { load(ConfigTransfer.SCHEMA_VERSION) }

    private fun load(version: Int): JsonSchema {
        val input = checkNotNull(javaClass.getResourceAsStream("/megaproxy-v$version.schema.json"))
        val definition = input.use { mapper.readTree(it) }
        val extensions = checkNotNull(javaClass.getResourceAsStream("/android-extension.schema.json"))
        val defs = definition.path("\$defs") as ObjectNode
        defs.setAll<ObjectNode>(extensions.use { mapper.readTree(it) } as ObjectNode)
        (definition.path("properties") as ObjectNode).putObject("android").put("\$ref", "#/\$defs/androidDocument")
        (defs.path("profile").path("properties") as ObjectNode).putObject("android").put("\$ref", "#/\$defs/androidProfile")
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(definition)
    }
}

/** Strict canonical snapshots; manual/legacy imports keep their existing compatibility policy. */
internal fun validateSubscriptionConfiguration(root: JSONObject) {
    val schema = if (root.getInt("version") == 8) SubscriptionSchema.v8 else SubscriptionSchema.current
    val errors = schema.validate(SubscriptionSchema.mapper.readTree(root.toString()))
    // Validation messages may contain remote field values; never display or log them.
    requireUi(errors.isEmpty()) { UiException(R.string.error_config_invalid) }
}
