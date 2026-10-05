package io.github.psd2live.agent

import io.github.psd2live.application.WorkspaceOperationRegistry
import io.github.psd2live.application.requestEnvelope
import io.github.psd2live.application.responseEnvelope
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.response.ApplicationSendPipeline
import kotlinx.serialization.json.*

/** Retain root schema constraints omitted by the SDK's narrow ToolSchema at the JSON transport boundary. */
internal fun Application.installExactToolPublication(registry: WorkspaceOperationRegistry) {
    sendPipeline.intercept(ApplicationSendPipeline.After) {
        val content = subject as? OutgoingContent.ByteArrayContent ?: return@intercept
        if (content.contentType?.match(ContentType.Application.Json) != true) return@intercept
        val original = content.bytes()
        // Image results can be large. Scan bytes before allocating a second JSON tree for a catalog response.
        if (!original.containsToolList()) return@intercept
        val response = runCatching { Json.parseToJsonElement(original.decodeToString()) }.getOrNull() as? JsonObject
            ?: return@intercept
        val rewritten = response.withExactToolPublication(registry)
        if (rewritten == response) return@intercept
        val bytes = rewritten.toString().encodeToByteArray()
        proceedWith(object : OutgoingContent.ByteArrayContent() {
            override val contentType = content.contentType
            override val status = content.status
            override val headers = content.headers
            override val contentLength = bytes.size.toLong()
            override fun bytes() = bytes
        })
    }
}

private fun ByteArray.containsToolList(): Boolean {
    val name = "\"tools\"".encodeToByteArray()
    for (start in indices) {
        if (start + name.size > size || this[start] != name[0]) continue
        if (name.indices.any { this[start + it] != name[it] }) continue
        var end = start + name.size
        fun skipWhitespace() { while (end < size && when (this[end].toInt()) { 9, 10, 13, 32 -> true; else -> false }) end++ }
        skipWhitespace()
        if (end >= size || this[end++].toInt() != ':'.code) continue
        skipWhitespace()
        if (end < size && this[end].toInt() == '['.code) return true
    }
    return false
}

internal fun JsonObject.withExactToolPublication(registry: WorkspaceOperationRegistry): JsonObject {
    if (this["jsonrpc"] != JsonPrimitive("2.0")) return this
    val result = this["result"] as? JsonObject ?: return this
    val tools = result["tools"] as? JsonArray ?: return this
    val published = tools.map { item ->
        val tool = item.jsonObject
        val operation = registry.definition(tool.getValue("name").jsonPrimitive.content)
        JsonObject(tool + buildJsonObject {
            put("inputSchema", operation.requestEnvelope())
            put("outputSchema", operation.responseEnvelope())
        })
    }
    return JsonObject(this + ("result" to JsonObject(result + ("tools" to JsonArray(published)))))
}
