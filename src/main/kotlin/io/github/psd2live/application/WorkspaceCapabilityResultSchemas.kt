package io.github.psd2live.application

import kotlinx.serialization.json.*

/** Capability results describe recursive schemas using the same locally executable vocabulary. */
internal object WorkspaceCapabilityResultSchemas {
    private val s = WorkspaceResultSchema
    private val metadata = linkedMapOf("id" to s.handle(), "description" to s.handle(),
        "kind" to s.choices(*WorkspaceOperationKind.entries.map { it.name.lowercase() }.toTypedArray())) +
        listOf("workspace_bound", "batchable", "job_backed", "read_only", "idempotent", "destructive").associateWith { s.boolean() }

    private fun ref(name: String) = buildJsonObject { put("\$ref", "#/\$defs/$name") }
    private fun array(value: JsonObject, minimum: Int = 0) = JsonObject(s.array(value) + ("minItems" to JsonPrimitive(minimum)))
    private val types = s.choices("object", "array", "string", "boolean", "number", "integer", "null")
    private fun oneOf(vararg schemas: JsonObject) = buildJsonObject { put("oneOf", JsonArray(schemas.toList())) }

    // The recursion advances through object fields or array elements; it never fetches external schemas.
    private val jsonValue = oneOf(s.string(), s.number(), s.boolean(), buildJsonObject { put("type", "null") },
        s.dictionary(ref("json_value")), s.array(ref("json_value")))
    private val schema = s.obj(linkedMapOf(
        "type" to oneOf(types, array(types, 1)), "properties" to s.dictionary(ref("schema")),
        "required" to array(s.string()), "additionalProperties" to oneOf(s.boolean(), ref("schema")),
        "items" to ref("schema"), "uniqueItems" to s.boolean(), "oneOf" to array(ref("schema"), 1), "enum" to array(ref("json_value"), 1),
        "const" to ref("json_value"), "default" to ref("json_value"), "examples" to array(ref("json_value")),
        "pattern" to s.string(), "description" to s.string(), "title" to s.string(),
        "\$defs" to s.dictionary(ref("schema")), "\$ref" to s.handle(),
    ) + listOf("minProperties", "minItems", "maxItems", "minLength", "maxLength").associateWith { s.integer(0) } +
        listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum").associateWith { s.number() }, emptySet())

    val list = s.obj(mapOf("items" to s.array(s.obj(metadata)), "total" to s.integer(0), "next" to s.integer(1)), setOf("items", "total"))
    private val contracts = mapOf("request_schema" to ref("schema"), "output_schema" to ref("schema"))
    val detail = JsonObject(s.union(listOf(
        s.obj(metadata + contracts + ("job_backed" to s.constant(false))),
        s.obj(metadata + contracts + mapOf("job_backed" to s.constant(true), "job_result_schema" to ref("schema"))),
    )) + ("\$defs" to buildJsonObject { put("schema", schema); put("json_value", jsonValue) }))

    fun forOperation(id: String): JsonObject? = when (id) {
        "workspace_list_operations" -> list
        "workspace_get_operation" -> detail
        else -> null
    }
}
