package net.megaproxy487.data

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import net.megaproxy487.R
import net.megaproxy487.UiException
import net.megaproxy487.requireUi
import org.json.JSONObject

private object SubscriptionSchema {
    val mapper = ObjectMapper()
    val schema by lazy {
        val input = checkNotNull(javaClass.getResourceAsStream("/megaproxy-v8.schema.json"))
        val definition = input.use { mapper.readTree(it) }
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(definition)
    }
}

/** Strict canonical snapshots; manual/legacy imports keep their existing compatibility policy. */
internal fun validateSubscriptionConfiguration(root: JSONObject) {
    val snapshot = JSONObject(root.toString()).apply { remove("subscription") }
    val errors = SubscriptionSchema.schema.validate(SubscriptionSchema.mapper.readTree(snapshot.toString()))
    // Validation messages may contain remote field values; never display or log them.
    requireUi(errors.isEmpty()) { UiException(R.string.error_config_invalid) }
}
