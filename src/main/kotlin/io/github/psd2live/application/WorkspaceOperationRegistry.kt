package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import kotlinx.serialization.json.*

internal enum class WorkspaceOperationKind { QUERY, DOCUMENT, SESSION, PROJECT, OUTPUT }

/** One definition drives publication, validation, capability discovery and adapter contract tests. */
internal data class WorkspaceOperationDefinition(
    val id: String,
    val description: String,
    val requestSchema: JsonObject,
    val kind: WorkspaceOperationKind,
    val batchable: Boolean = false,
    val jobBacked: Boolean = false,
    val idempotent: Boolean = kind == WorkspaceOperationKind.QUERY,
    val destructive: Boolean = false,
    val workspaceBound: Boolean = kind != WorkspaceOperationKind.QUERY,
    val resultSchema: JsonObject,
    val jobResultSchema: JsonObject? = null,
) {
    init {
        require(Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)+").matches(id)) { "Use a domain_operation identifier" }
        require(description.isNotBlank()) { "Describe the operation" }
        require(requestSchema["type"] == JsonPrimitive("object")) { "Operation request must be an object" }
        require(requestSchema["additionalProperties"] == JsonPrimitive(false)) { "Operation request must reject unknown fields" }
        require(!batchable || kind == WorkspaceOperationKind.DOCUMENT) { "Only document edits are batchable" }
        checkOperationSchema(requestSchema)
        require(resultSchema["type"] == JsonPrimitive("object") && resultSchema["additionalProperties"] == JsonPrimitive(false)) {
            "Operation result must be a strict object"
        }
        checkOperationSchema(resultSchema)
        require(jobBacked == (jobResultSchema != null)) { "Every background operation must declare its terminal result" }
        jobResultSchema?.let {
            require(jobBacked) { "Only background operations have a job result schema" }
            require(it["type"] == JsonPrimitive("object") && it["additionalProperties"] == JsonPrimitive(false)) {
                "Job result must be a strict object"
            }
            checkOperationSchema(it)
        }
    }

    fun toJson(includeSchema: Boolean = false): JsonObject = buildJsonObject {
        put("id", id); put("description", description); put("kind", kind.name.lowercase())
        put("workspace_bound", workspaceBound); put("batchable", batchable); put("job_backed", jobBacked)
        put("read_only", kind == WorkspaceOperationKind.QUERY)
        put("idempotent", idempotent); put("destructive", destructive)
        if (includeSchema) {
            put("request_schema", requestSchema)
            put("output_schema", responseEnvelope())
            jobResultSchema?.let { put("job_result_schema", it) }
        }
    }
}

/** Actor identity comes from the adapter, never from a client-supplied author field. */
internal data class WorkspaceOperationContext(val author: MutationAuthor)

/** One envelope drives transport publication and validation, including rejection of unknown fields. */
internal fun WorkspaceOperationDefinition.requestEnvelope(): JsonObject = buildJsonObject {
    requestSchema["\$defs"]?.let { put("\$defs", it) }
    put("type", "object"); put("additionalProperties", false)
    put("properties", buildJsonObject { put("request", requestSchema) })
    put("required", JsonArray(listOf(JsonPrimitive("request"))))
}

internal class WorkspaceOperationRegistry(private val requests: WorkspaceRequestExecutor? = null) {
    private data class Entry(
        val definition: WorkspaceOperationDefinition,
        val execute: suspend (JsonObject, WorkspaceOperationContext) -> WorkspaceOperationOutput,
    )
    private val entries = linkedMapOf<String, Entry>()
    private var frozen = false

    fun register(
        definition: WorkspaceOperationDefinition,
        execute: suspend (JsonObject, WorkspaceOperationContext) -> WorkspaceOperationOutput,
    ) {
        check(!frozen) { "Operation registry has been published" }
        require(definition.id !in entries) { "Operation already registered: ${definition.id}" }
        val published = if (requests == null) definition else definition.withRequestContext()
        val businessFields = definition.requestSchema["properties"]?.jsonObject.orEmpty().keys
        entries[definition.id] = Entry(published) { request, context ->
            execute(if (requests == null) request else JsonObject(request - (setOf("project_id", "request_id", "state") - businessFields)), context)
        }
    }

    fun markBatchable(ids: Set<String>) {
        check(!frozen) { "Operation registry has been published" }
        ids.forEach { id ->
            val entry = requireEntry(id)
            require(entry.definition.kind == WorkspaceOperationKind.DOCUMENT) { "Only document edits can join a batch" }
            entries[id] = entry.copy(definition = entry.definition.copy(batchable = true))
        }
    }

    fun definitions(): List<WorkspaceOperationDefinition> {
        frozen = true
        return entries.values.map { it.definition }
    }

    fun definition(id: String): WorkspaceOperationDefinition = requireEntry(id).definition

    suspend fun invoke(id: String, request: JsonObject, context: WorkspaceOperationContext): WorkspaceOperationOutput {
        val entry = requireEntry(id)
        validateOperationSchema(request, entry.definition.requestSchema)
        val action: suspend () -> WorkspaceOperationOutput = {
            val output = entry.execute(request, context)
            validateWorkspaceResult(id, entry.definition.resultSchema, output.data)
            output
        }
        return requests?.execute(entry.definition, request, context, action) ?: action()
    }

    private fun requireEntry(id: String): Entry = entries[id] ?: throw IllegalArgumentException("Operation not found: $id")
}
