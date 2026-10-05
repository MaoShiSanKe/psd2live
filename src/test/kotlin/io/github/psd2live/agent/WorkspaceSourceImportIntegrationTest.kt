package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.Bounds
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
import org.umamo.format.psd.PsdReader
import org.umamo.interop.cmo3.Cmo3Import
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceSourceImportIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val connection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java)) { _, method, _ ->
        if (method.name == "getSessionId") "source-test" else error("Unexpected client notification")
    } as ClientConnection
    private suspend fun call(server: Server, operation: String, request: JsonObject): JsonObject {
        val result = server.tools.getValue(operation).handler.invoke(connection, CallToolRequest(CallToolRequestParams(operation,
            buildJsonObject { put("request", request) })))
        assertFalse(result.isError == true, result.structuredContent.toString())
        return result.structuredContent!!.getValue("data").jsonObject
    }
    private fun request(workspace: DesktopWorkspace, id: String, business: JsonObject): JsonObject = buildJsonObject {
        val snapshot = workspace.snapshot()
        put("request_id", id); put("state", snapshot.state); put("project_id", snapshot.projectId?.let(::JsonPrimitive) ?: JsonNull)
        business.forEach { (key, value) -> put(key, value) }
    }
    private suspend fun wait(server: Server, job: JsonObject) = call(server, "job_wait", buildJsonObject { put("id", job.getValue("id")) })

    @Test fun guiPsdAnalysisAndMcpArtworkSwitchSharePersistenceAndExportReadBack() = runBlocking {
        val (png, psd) = writeSourceImportFixture(temporary)
        val priorScan = AppSettings.autoDetectMeshSplitsOnImport
        try {
            AppSettings.autoDetectMeshSplitsOnImport = true
            PSD2LiveViewModel().use { vm ->
                val initial = vm.state.value
                val oldAxis = org.umamo.runtime.model.ParameterId("OldAxis")
                val secondary = initial.activeWorkspace.copy(id = "secondary", pose = io.github.psd2live.ui.state.WorkspacePose(
                    parameterValues = mapOf(oldAxis to 9f), lockedParameters = setOf(oldAxis), animationEnabled = true))
                vm.setStateForTest(initial.copy(atlasSize = 256, meshOnly = true, generatePhysics = false,
                    workspaces = initial.workspaces + secondary))
                DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    vm.setInputPath(psd.toString())
                    vm.analyze()
                    withTimeout(15000) { vm.state.first { it.analysis != null && !it.isAnalyzing } }
                    assertNull(vm.state.value.errorMessage)
                    assertEquals(psd.toString(), vm.state.value.loadedInputPath)
                    assertEquals(1, workspace.history().nodes.size)
                    val defaults = workspace.currentPuppet()!!.parameters.associate { it.id to it.default }
                    assertTrue(vm.state.value.workspaces.all { it.pose?.parameterValues == defaults &&
                        it.pose?.lockedParameters.orEmpty().isEmpty() && it.pose?.animationEnabled == false }, vm.state.value.workspaces.toString())
                    assertTrue(vm.state.value.workspaces.flatMap { it.canvases }.all {
                        it.editSession.camera == TabCamera() && it.previewSession.camera == TabCamera() })
                    val archive = temporary.resolve("psd.psd2live")
                    vm.saveProjectNow(archive)
                    val root = workspace.snapshot()
                    val psdHistory = workspace.history()
                    vm.openProjectNow(archive)
                    assertEquals(psdHistory, workspace.history())
                    assertNotEquals(root.state, workspace.snapshot().state)
                    WorkspaceOperations(workspace).use { operations ->
                        val server = createAgentMcpServer(workspace, operations)
                        for (id in listOf("project_import_psd", "project_create_artwork")) {
                            assertTrue(operations.registry.definition(id).jobBacked)
                            assertEquals(operations.registry.definition(id).requestSchema, server.tools.getValue(id).tool.inputSchema.properties!!.getValue("request"))
                        }
                        vm.confirmUnsavedChanges = { error("MCP must not open a GUI dialog") }
                        val beforeInvalid = workspace.snapshot()
                        for ((field, value) in listOf("x" to JsonPrimitive(4.5), "role" to JsonPrimitive("unknown-role"))) {
                            val business = sourceImportArguments(png)
                            val layer = business.getValue("layers").jsonArray.single().jsonObject
                            val invalid = JsonObject(business + ("layers" to JsonArray(listOf(JsonObject(layer + (field to value))))))
                            val result = server.tools.getValue("project_create_artwork").handler.invoke(connection,
                                CallToolRequest(CallToolRequestParams("project_create_artwork", buildJsonObject {
                                    put("request", request(workspace, "invalid-$field", invalid))
                                })))
                            assertTrue(result.isError == true)
                            assertEquals("invalid_request", result.structuredContent!!.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                            assertEquals(beforeInvalid.state, workspace.snapshot().state)
                        }
                        val createRequest = request(workspace, "create", sourceImportArguments(png))
                        val job = call(server, "project_create_artwork", createRequest)
                        val reconnect = createAgentMcpServer(workspace, operations)
                        assertEquals(job.getValue("id"), call(reconnect, "project_create_artwork", createRequest).getValue("id"))
                        val created = wait(reconnect, job)
                        assertEquals("completed", created.getValue("status").jsonPrimitive.content)
                        val artwork = workspace.snapshot()
                        assertNotEquals(root.projectId, artwork.projectId)
                        assertEquals(artwork.state, created.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
                        assertEquals(1, workspace.history().nodes.size)
                        val rejected = wait(server, call(server, "project_import_psd", request(workspace, "reject", buildJsonObject { put("path", psd.toString()) })))
                        assertEquals("unsaved_changes", rejected.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                        assertEquals(artwork.state, workspace.snapshot().state)
                        val view = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)), output = WorkspaceViewOutputSpec(128))
                        val before = workspace.renderModel(view).png
                        val artworkHistory = workspace.history()
                        val savedArtwork = temporary.resolve("artwork.psd2live")
                        vm.saveProjectNow(savedArtwork)
                        val export = workspace.exportModel(workspace.snapshot().state, temporary.resolve("model").toString())
                        val cmo3 = export.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }.single { it.toString().endsWith(".cmo3") }
                        val readBack = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource)
                        assertEquals(workspace.currentPuppet()!!.drawables.map { it.id }.toSet(), readBack.drawables.map { it.id }.toSet())
                        val outputPsd = temporary.resolve("output.psd")
                        workspace.exportPsd(workspace.snapshot().state, outputPsd.toString(), 1, false)
                        val source = PsdReader.read(Files.readAllBytes(outputPsd))
                        assertContentEquals(vm.state.value.analysis!!.source.layers.single().raster.rgba, source.layers.single().raster.rgba)
                        vm.openProjectNow(savedArtwork)
                        assertEquals(artworkHistory, workspace.history())
                        val after = workspace.renderModel(view).png
                        assertContentEquals(before, after)
                        val visual = Files.createDirectories(Path.of("build/source-import-visual"))
                        Files.write(visual.resolve("before-reopen.png"), before); Files.write(visual.resolve("after-reopen.png"), after)
                        workspace.setLayerMeshSettings(workspace.snapshot().state, vm.state.value.analysis!!.source.layers.single().id.raw,
                            buildJsonObject { put("outerMargin", 2) }, false)
                        assertTrue(workspace.snapshot().projectDirty)
                        val switched = wait(server, call(server, "project_import_psd", request(workspace, "switch", buildJsonObject { put("path", psd.toString()); put("discard_unsaved", true) })))
                        assertEquals("completed", switched.getValue("status").jsonPrimitive.content)
                        assertNotEquals(artwork.projectId, workspace.snapshot().projectId)
                        assertTrue(vm.state.value.meshOverrides.isEmpty())
                        assertEquals(1, workspace.history().nodes.size)
                    }
                }
            }
        } finally { AppSettings.autoDetectMeshSplitsOnImport = priorScan }
    }
}
