package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.NoSuchFileException
import kotlin.test.*

class WorkspaceJobFailureIntegrationTest {
    private val connection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java)) { _, method, _ ->
        if (method.name == "getSessionId") "failure-test" else error("Unexpected client notification")
    } as ClientConnection

    private open class Backend : WorkspaceBackendStub() {
        var currentState = "generation:0"
        var exports = 0
        override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true, "artwork",
            4, 4, false, "ready", null, emptyList(), emptyList(), state = currentState)
    }
    private fun exportRequest(id: String) = buildJsonObject {
        put("request_id", id); put("project_id", "project"); put("state", "generation:0"); put("output_directory", "/out")
    }
    private suspend fun call(server: Server, operation: String, request: JsonObject): CallToolResult =
        server.tools.getValue(operation).handler.invoke(connection, CallToolRequest(CallToolRequestParams(operation,
            buildJsonObject { put("request", request) })))
    private fun CallToolResult.data(): JsonObject {
        assertFalse(isError == true, structuredContent.toString())
        return structuredContent!!.getValue("data").jsonObject
    }

    @Test fun asynchronousConflictMatchesImmediateFailureAndSurvivesReconnectAndRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : Backend() {
            override suspend fun exportModel(state: String, outputDirectory: String): JsonObject {
                exports++
                entered.complete(Unit)
                release.await()
                throw WorkspaceConflict(state, currentState)
            }
        }
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations)
            val request = exportRequest("async")
            val job = call(server, "project_export_model", request).data()
            entered.await()
            backend.currentState = "generation:1"
            release.complete(Unit)
            val failed = call(server, "job_wait", buildJsonObject { put("id", job.getValue("id")) }).data()
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            assertTrue(failed.getValue("terminal").jsonPrimitive.boolean)
            assertFalse("result" in failed)
            val error = failed.getValue("error").jsonObject
            assertEquals("state_conflict", error.getValue("code").jsonPrimitive.content)
            assertEquals("generation:0", error.getValue("expected_state").jsonPrimitive.content)
            assertEquals("generation:1", error.getValue("actual_state").jsonPrimitive.content)
            val immediate = call(server, "project_export_model", exportRequest("immediate"))
            assertTrue(immediate.isError == true)
            assertEquals(error, immediate.structuredContent!!.getValue("error"))
            val reconnected = createAgentMcpServer(backend, operations)
            assertEquals(job.getValue("id"), call(reconnected, "project_export_model", request).data().getValue("id"))
            assertEquals(error, call(reconnected, "job_get", buildJsonObject { put("id", job.getValue("id")) }).data().getValue("error"))
            val listed = call(reconnected, "job_list", buildJsonObject {}).data().getValue("items").jsonArray.single().jsonObject
            assertEquals(error, listed.getValue("error"))
            assertEquals(1, backend.exports)
        }
    }

    @Test fun fileFailureRetainsItsCodeAndMessageWithoutCreatingASuccessResult() = runBlocking {
        val backend = object : Backend() {
            override suspend fun exportModel(state: String, outputDirectory: String): JsonObject {
                exports++
                throw NoSuchFileException("missing-input.psd")
            }
        }
        WorkspaceOperations(backend).use { operations ->
            val server = createAgentMcpServer(backend, operations)
            val job = call(server, "project_export_model", exportRequest("file-failure")).data()
            val failed = call(server, "job_wait", buildJsonObject { put("id", job.getValue("id")) }).data()
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            assertFalse("result" in failed)
            val error = failed.getValue("error").jsonObject
            assertEquals("io_error", error.getValue("code").jsonPrimitive.content)
            assertEquals("missing-input.psd", error.getValue("message").jsonPrimitive.content)
            assertEquals(failed, call(server, "job_get", buildJsonObject { put("id", job.getValue("id")) }).data())
            assertEquals(1, backend.exports)
        }
    }
}
