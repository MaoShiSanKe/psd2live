package io.github.psd2live.application

import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceCapabilityContractsTest {
    private val s = WorkspaceResultSchema
    private fun ref(name: String) = buildJsonObject { put("\$ref", "#/\$defs/$name") }

    @Test fun capabilityDetailsValidateNestedSchemasAndTheirOwnPublishedEnvelope() {
        val request = s.obj(mapOf("nested" to s.obj(mapOf("value" to s.nullable(s.integer(0))))))
        val definition = WorkspaceOperationDefinition("workspace_get_operation", "Discover an exact contract", request,
            WorkspaceOperationKind.QUERY, resultSchema = WorkspaceCapabilityResultSchemas.detail)
        checkOperationSchema(definition.resultSchema)
        val detail = definition.toJson(true)
        validateOperationSchema(detail, definition.resultSchema)
        val envelope = buildJsonObject { put("ok", true); put("operation", definition.id); put("data", detail) }
        checkOperationSchema(definition.responseEnvelope())
        validateOperationSchema(envelope, definition.responseEnvelope())
        for (invalid in listOf(JsonObject(detail + ("unpublished" to JsonPrimitive(true))),
            JsonObject(detail + ("request_schema" to s.obj(mapOf("nested" to buildJsonObject { put("format", "uri") })))),
            JsonObject(detail + ("request_schema" to buildJsonObject { put("required", "one field") })))) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(invalid, definition.resultSchema) }
        }
    }

    @Test fun localRecursiveDefinitionsRetainTheirRootInRequestAndResponseEnvelopes() {
        val node = s.obj(mapOf("value" to s.integer(), "children" to s.array(ref("node"))))
        val schema = JsonObject(s.obj(mapOf("node" to ref("node"))) + ("\$defs" to buildJsonObject { put("node", node) }))
        val definition = WorkspaceOperationDefinition("model_read_tree", "Read a recursive tree", schema,
            WorkspaceOperationKind.QUERY, resultSchema = schema)
        val value = buildJsonObject { putJsonObject("node") {
            put("value", 1); putJsonArray("children") { add(buildJsonObject { put("value", 2); put("children", JsonArray(emptyList())) }) }
        } }
        validateOperationSchema(value, schema)
        validateOperationSchema(buildJsonObject { put("request", value) }, definition.requestEnvelope())
        validateOperationSchema(buildJsonObject { put("ok", true); put("operation", definition.id); put("data", value) }, definition.responseEnvelope())
        val invalid = buildJsonObject { putJsonObject("node") {
            put("value", 1); putJsonArray("children") { add(buildJsonObject { put("value", "wrong"); put("children", JsonArray(emptyList())) }) }
        } }
        val failure = assertFailsWith<WorkspaceValidationException> { validateOperationSchema(buildJsonObject { put("request", invalid) }, definition.requestEnvelope(), "arguments") }
        assertEquals("arguments.request.node.children[0].value", failure.fieldPath)
    }

    @Test fun missingExternalAndUnproductiveReferencesCannotBeRegistered() {
        for (reference in listOf("https://example.invalid/schema", "#/missing", "#/\$defs/missing")) {
            val schema = JsonObject(s.obj(mapOf("value" to buildJsonObject { put("\$ref", reference) })) + ("\$defs" to JsonObject(emptyMap())))
            assertFailsWith<IllegalArgumentException> { checkOperationSchema(schema) }
        }
        val cycle = JsonObject(s.obj(mapOf("value" to ref("loop"))) + ("\$defs" to buildJsonObject { put("loop", ref("loop")) }))
        assertFailsWith<IllegalArgumentException> { checkOperationSchema(cycle) }
    }
}
