package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceOutputContractsTest {
    private val empty = WorkspaceResultSchema.obj(emptyMap())
    private val data = WorkspaceResultSchema.obj(mapOf("value" to WorkspaceResultSchema.number()))
    private val context = WorkspaceOperationContext(MutationAuthor.AGENT)

    @Test fun invalidResultIsAnOutputFailureAndRetriesDoNotRepeatTheMutation() = runBlocking<Unit> {
        val host = object : WorkspaceStatePort {
            override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true,
                null, 1, 1, false, "ready", null, emptyList(), emptyList(), state = "load:0:0")
        }
        WorkspaceRequestExecutor(host).use { executor ->
            val registry = WorkspaceOperationRegistry(executor)
            var calls = 0
            registry.register(WorkspaceOperationDefinition("session_probe", "Probe a result contract", empty,
                WorkspaceOperationKind.SESSION, resultSchema = data)) { _, _ ->
                calls++
                WorkspaceOperationOutput(buildJsonObject { put("value", "wrong") })
            }
            val request = buildJsonObject { put("request_id", "same"); put("project_id", "project"); put("state", "load:0:0") }
            val first = assertFailsWith<WorkspaceOutputContractFailure> { registry.invoke("session_probe", request, context) }
            val second = assertFailsWith<WorkspaceOutputContractFailure> { registry.invoke("session_probe", request, context) }
            assertSame(first, second)
            assertEquals(1, calls)
            val error = WorkspaceFailure.from(first).toJson()
            assertEquals("output_contract", error.getValue("code").jsonPrimitive.content)
            assertEquals("result.value", error.getValue("field").jsonPrimitive.content)
            assertEquals("session_probe", error.getValue("operation").jsonPrimitive.content)
            validateOperationSchema(error, WorkspaceResultSchema.failure)
        }
    }

    @Test fun publishedEnvelopeRequiresExactlyOneTypedOutcomeAndRejectsExtraFields() = runBlocking<Unit> {
        val definition = WorkspaceOperationDefinition("model_probe", "Read a typed result", empty, WorkspaceOperationKind.QUERY, resultSchema = data)
        val registry = WorkspaceOperationRegistry()
        registry.register(definition) { _, _ -> WorkspaceOperationOutput(buildJsonObject { put("value", 3) }) }
        val schema = definition.responseEnvelope()
        assertEquals(schema, definition.toJson(true).getValue("output_schema"))
        val success = buildJsonObject { put("ok", true); put("operation", definition.id); put("data", registry.invoke(definition.id, JsonObject(emptyMap()), context).data) }
        validateOperationSchema(success, schema)
        val failure = buildJsonObject { put("ok", false); put("operation", definition.id); put("error", WorkspaceFailure.from(IllegalStateException("Unavailable")).toJson()) }
        validateOperationSchema(failure, schema)
        for (bad in listOf(JsonObject(success + ("extra" to JsonPrimitive(true))),
            JsonObject(success + ("error" to failure.getValue("error"))), JsonObject(success - "data"),
            JsonObject(success + ("operation" to JsonPrimitive("other_probe"))),
            JsonObject(success + ("data" to buildJsonObject { put("value", 3); put("unpublished", 4) })))) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(bad, schema) }
        }
    }

    @Test fun backgroundDefinitionsAndCapabilityDetailsCannotOmitOrInventTerminalContracts() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceOperationDefinition("project_probe", "Probe a job", empty, WorkspaceOperationKind.PROJECT,
                jobBacked = true, resultSchema = empty)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspaceOperationDefinition("project_probe", "Probe a job", empty, WorkspaceOperationKind.PROJECT,
                resultSchema = empty, jobResultSchema = data)
        }
        val job = WorkspaceOperationDefinition("project_probe", "Probe a job", empty, WorkspaceOperationKind.PROJECT,
            jobBacked = true, resultSchema = empty, jobResultSchema = data).toJson(true)
        validateOperationSchema(job, WorkspaceCapabilityResultSchemas.detail)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(job - "job_result_schema"), WorkspaceCapabilityResultSchemas.detail) }
        val immediate = WorkspaceOperationDefinition("model_probe", "Probe a query", empty, WorkspaceOperationKind.QUERY, resultSchema = data).toJson(true)
        validateOperationSchema(immediate, WorkspaceCapabilityResultSchemas.detail)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(immediate + ("job_result_schema" to data)), WorkspaceCapabilityResultSchemas.detail) }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(immediate - "output_schema"), WorkspaceCapabilityResultSchemas.detail) }
    }

    @Test fun auxiliaryContractsDescribeNullableAnnotationsAndBoundedSnapshotSummaries() {
        val identity = buildJsonObject { put("project_id", "project"); put("state", "load:0:0"); put("history_node_id", "head") }
        val annotation = JsonObject(identity + mapOf("node_id" to JsonPrimitive("head"), "annotation" to JsonNull))
        validateOperationSchema(annotation, WorkspaceAuxiliaryResultSchemas.forOperation("history_annotation_get"))
        val page = JsonObject(identity + buildJsonObject {
            put("total", 1); putJsonArray("items") { add(buildJsonObject { put("id", "pose"); put("number", 1); put("name", "Pose") }) }
        })
        validateOperationSchema(page, WorkspaceAuxiliaryResultSchemas.forOperation("snapshot_list"))
        assertFailsWith<WorkspaceValidationException> {
            validateOperationSchema(JsonObject(page + ("total" to JsonPrimitive(-1))), WorkspaceAuxiliaryResultSchemas.forOperation("snapshot_list"))
        }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(JsonObject(annotation + ("annotation" to buildJsonObject { put("title", "Only one field") })),
            WorkspaceAuxiliaryResultSchemas.forOperation("history_annotation_get")) }
    }
}
