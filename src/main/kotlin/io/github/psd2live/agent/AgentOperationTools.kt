package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.MutationAuthor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.Base64

/** The only translation needed for application operations: exact request schema and native output. */
internal fun installOperationTools(server: Server, registry: WorkspaceOperationRegistry) {
    registry.definitions().forEach { operation ->
        val outputSchema = operation.responseEnvelope().let { envelope -> ToolSchema(
            properties = envelope.getValue("properties").jsonObject,
            required = envelope.getValue("required").jsonArray.map { it.jsonPrimitive.content },
        ) }
        server.addTool(operation.id, operation.description,
            ToolSchema(properties = buildJsonObject { put("request", operation.requestSchema) }, required = listOf("request")),
            outputSchema = outputSchema,
            toolAnnotations = ToolAnnotations(readOnlyHint = operation.kind == WorkspaceOperationKind.QUERY,
                destructiveHint = operation.destructive, idempotentHint = operation.idempotent, openWorldHint = false),
        ) { call ->
            try {
                val envelope = call.arguments ?: throw WorkspaceValidationException("request", "Required object")
                validateOperationSchema(envelope, operation.requestEnvelope(), "arguments")
                val request = envelope.getValue("request").jsonObject
                val output = registry.invoke(operation.id, request, WorkspaceOperationContext(MutationAuthor.AGENT))
                val result = buildJsonObject { put("ok", true); put("operation", operation.id); put("data", output.data) }
                operation.responseEnvelope().let { validateWorkspaceResult(operation.id, it, result) }
                CallToolResult(content = listOf(TextContent(result.toString())) + output.images.map {
                    ImageContent(Base64.getEncoder().encodeToString(it), "image/png")
                }, structuredContent = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                operationError(operation, failure)
            }
        }
    }
}

private fun operationError(operation: WorkspaceOperationDefinition, failure: Exception): CallToolResult {
    val data = buildJsonObject {
        put("ok", false); put("operation", operation.id)
        put("error", WorkspaceFailure.from(failure).toJson())
    }
    operation.responseEnvelope().let { validateWorkspaceResult(operation.id, it, data) }
    return CallToolResult(content = listOf(TextContent(data.toString())), structuredContent = data, isError = true)
}
