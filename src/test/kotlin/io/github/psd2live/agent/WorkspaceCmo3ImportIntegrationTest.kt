package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceCmo3ImportIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val connection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java)) { _, method, _ ->
        if (method.name == "getSessionId") "cmo3-test" else error("Unexpected client notification")
    } as ClientConnection

    private suspend fun call(server: Server, operation: String, request: JsonObject): JsonObject {
        val result = server.tools.getValue(operation).handler.invoke(connection, CallToolRequest(CallToolRequestParams(operation,
            buildJsonObject { put("request", request) })))
        assertFalse(result.isError == true, result.structuredContent.toString())
        return result.structuredContent!!.getValue("data").jsonObject
    }
    private fun request(workspace: DesktopWorkspace, id: String, path: Path, mode: String, discard: Boolean = false) = buildJsonObject {
        val snapshot = workspace.snapshot()
        put("request_id", id); put("state", snapshot.state); put("project_id", snapshot.projectId?.let(::JsonPrimitive) ?: JsonNull)
        put("path", path.toString()); put("mode", mode)
        if (discard) put("discard_unsaved", true)
    }
    private suspend fun wait(server: Server, started: JsonObject) = call(server, "job_wait", buildJsonObject { put("id", started.getValue("id")) })

    @Test fun mcpImportAndReplacementSurviveReconnectAndShareGuiPersistenceAndExport() = runBlocking {
        val first = writeCmo3Fixture(temporary.resolve("first.cmo3"), "kept", "shared")
        val incoming = writeCmo3Fixture(temporary.resolve("incoming.cmo3"), "shared", "added", color = 0xff4070dd.toInt())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                WorkspaceOperations(workspace).use { operations ->
                    val server = createAgentMcpServer(workspace, operations = operations)
                    val originalRequest = request(workspace, "first-import", first, "new")
                    val job = call(server, "project_import_cmo3", originalRequest)
                    val reconnected = createAgentMcpServer(workspace, operations = operations)
                    assertEquals(job.getValue("id"), call(reconnected, "project_import_cmo3", originalRequest).getValue("id"))
                    val imported = wait(reconnected, job)
                    assertEquals("completed", imported.getValue("status").jsonPrimitive.content)
                    assertEquals(workspace.snapshot().state, imported.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
                    assertEquals(setOf("kept", "shared"), workspace.currentPuppet()!!.drawables.map { it.id.raw }.toSet())
                    vm.saveParameterSnapshot("Keep snapshot")
                    val snapshots = vm.state.value.parameterSnapshots
                    vm.setParameterValue(ParameterId("ParamCustom"), 1f)
                    withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                    assertEquals(1f, workspace.previewSession().getValue("values").jsonObject.getValue("ParamCustom").jsonPrimitive.float)
                    vm.toggleParameterLock(ParameterId("ParamCustom"))
                    withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                    assertEquals(listOf("ParamCustom"), workspace.previewSession().getValue("locked").jsonArray.map { it.jsonPrimitive.content })
                    val replaced = wait(server, call(server, "project_import_cmo3", request(workspace, "replace", incoming, "replace")))
                    assertEquals("completed", replaced.getValue("status").jsonPrimitive.content, replaced.toString())
                    assertEquals(setOf("kept", "shared", "added"), workspace.currentPuppet()!!.drawables.map { it.id.raw }.toSet())
                    assertEquals(2, workspace.history().nodes.size)
                    assertEquals("agent", workspace.history().nodes.last().actor)
                    assertEquals(snapshots, vm.state.value.parameterSnapshots)
                    assertEquals(0f, vm.state.value.parameterValues.getValue(ParameterId("ParamCustom")))
                    assertTrue(vm.state.value.lockedParameters.isEmpty())
                    val history = workspace.history()
                    val view = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(io.github.psd2live.core.Bounds(0f, 0f, 32f, 32f)),
                        output = WorkspaceViewOutputSpec(128))
                    val before = workspace.renderModel(view).png
                    val archive = temporary.resolve("saved.psd2live")
                    vm.saveProjectNow(archive)
                    val exported = workspace.exportModel(workspace.snapshot().state, temporary.resolve("export").toString())
                    val cmo3 = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                        .single { it.toString().endsWith(".cmo3") }
                    val readBack = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource)
                    assertEquals(setOf("kept", "shared", "added"), readBack.drawables.map { it.id.raw }.toSet())
                    assertEquals(listOf("ParamCustom"), readBack.parameters.map { it.id.raw })
                    vm.openProjectNow(archive)
                    assertEquals(history, workspace.history())
                    assertEquals(snapshots, vm.state.value.parameterSnapshots)
                    val after = workspace.renderModel(view).png
                    assertContentEquals(before, after)
                    val visual = Files.createDirectories(Path.of("build/cmo3-import-visual"))
                    Files.write(visual.resolve("before-reopen.png"), before)
                    Files.write(visual.resolve("after-reopen.png"), after)
                    workspace.checkoutHistory(history.nodes.first().id, MutationAuthor.USER)
                    assertEquals(setOf("kept", "shared"), workspace.currentPuppet()!!.drawables.map { it.id.raw }.toSet())
                }
            }
        }
    }

    @Test fun publishedImportSchemaAndUnsavedSwitchPolicyRejectWithoutOpeningGuiDialogs() = runBlocking {
        val path = writeCmo3Fixture(temporary.resolve("model.cmo3"), "mesh")
        PSD2LiveViewModel().use { vm ->
            vm.confirmUnsavedChanges = { error("MCP must not open a GUI dialog") }
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                WorkspaceOperations(workspace).use { operations ->
                    val server = createAgentMcpServer(workspace, operations = operations)
                    val definition = operations.registry.definition("project_import_cmo3")
                    assertTrue(definition.jobBacked)
                    assertEquals(definition.requestSchema, server.tools.getValue("project_import_cmo3").tool.inputSchema.properties!!.getValue("request"))
                    val invalid = server.tools.getValue("project_import_cmo3").handler.invoke(connection,
                        CallToolRequest(CallToolRequestParams("project_import_cmo3", buildJsonObject {
                            put("request", request(workspace, "bad-mode", path, "unknown"))
                        })))
                    assertTrue(invalid.isError == true)
                    assertEquals("invalid_request", invalid.structuredContent!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                    assertFalse(workspace.snapshot().loaded)
                    assertEquals("completed", wait(server, call(server, "project_import_cmo3", request(workspace, "first", path, "new"))).getValue("status").jsonPrimitive.content)
                    val before = workspace.snapshot()
                    val denied = wait(server, call(server, "project_import_cmo3", request(workspace, "denied", path, "new")))
                    assertEquals("failed", denied.getValue("status").jsonPrimitive.content)
                    assertEquals("unsaved_changes", denied.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                    assertTrue(denied.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("unsaved"))
                    assertEquals(before.state, workspace.snapshot().state)
                    val allowed = wait(server, call(server, "project_import_cmo3", request(workspace, "discard", path, "new", discard = true)))
                    assertEquals("completed", allowed.getValue("status").jsonPrimitive.content)
                    assertNotEquals(before.projectId, workspace.snapshot().projectId)
                    assertNotEquals(before.state, workspace.snapshot().state)
                }
            }
        }
    }
}
