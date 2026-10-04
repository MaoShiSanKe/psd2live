package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceDocumentBatchIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)

    private suspend fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace, WorkspaceOperations) -> Unit) {
        val png = temporary.resolve("art.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..15) for (x in 0..15) image.setRGB(x, y, 0xff607080.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 32); put("height", 32)
                    putJsonArray("layers") { add(buildJsonObject {
                        put("path", png.toString()); put("name", "Artwork"); put("role", "objects")
                    }) }
                })
                WorkspaceOperations(workspace).use { action(vm, workspace, it) }
            }
        }
    }

    private fun edit(id: String, request: JsonObject) = buildJsonObject { put("operation", id); put("request", request) }
    private fun batch(workspace: DesktopWorkspace, id: String, edits: List<JsonObject>) = buildJsonObject {
        val captured = workspace.snapshot()
        put("request_id", id); put("project_id", captured.projectId); put("state", captured.state)
        put("summary", "Batch edit"); put("edits", JsonArray(edits))
    }
    private suspend fun WorkspaceOperations.waitBatch(request: JsonObject): JsonObject {
        val started = registry.invoke("workspace_apply_edits", request, agent).data
        return registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, agent).data
    }
    private suspend fun WorkspaceOperations.apply(request: JsonObject): JsonObject {
        val job = waitBatch(request)
        assertEquals("completed", job.getValue("status").jsonPrimitive.content, job.toString())
        return job.getValue("result").jsonObject
    }

    @Test fun orderedBatchUsesNewParametersAndFormsThenReplaysSavesAndExportsOneNode() = runBlocking {
        fixture { vm, workspace, operations ->
            val before = workspace.snapshot()
            val target = "mesh:" + workspace.currentPuppet()!!.drawables.first().id.raw
            val input = batch(workspace, "ordered", listOf(
                edit("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } }),
                edit("parameter_create", buildJsonObject { put("parameter_id", "BatchAxis"); put("name", "Batch axis"); put("min", -1); put("max", 1) }),
                edit("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                    put("op", "seed"); put("target", target); putJsonObject("key") { put("BatchAxis", 0) }
                }) } }),
                edit("motion_put", buildJsonObject { put("clip", MotionClips.toJson(MotionClip("batch-motion", "Batch motion", duration = 1f))) }),
                edit("motion_set_key", buildJsonObject {
                    put("id", "batch-motion"); put("parameter", "BatchAxis"); putJsonObject("key") { put("time", 0); put("value", 0.8) }
                })
            ))
            val result = operations.apply(input)
            assertEquals(result, operations.apply(input))
            val after = workspace.snapshot()
            assertEquals(2, workspace.history().nodes.size)
            assertEquals(before.historyHeadNodeId, workspace.history().nodes.last().parentId)
            assertEquals(after.state, result.getValue("state").jsonPrimitive.content)
            assertEquals("agent", workspace.history().nodes.last().actor)
            assertEquals(0.8f, workspace.motionClips().single().curves.single().keys.single().value)
            val puppet = workspace.currentPuppet()!!
            assertTrue(puppet.parameters.any { it.id.raw == "BatchAxis" })
            assertTrue(puppet.drawables.first().geometryGrid!!.axes.any { it.parameterId.raw == "BatchAxis" })
            val view = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)), output = WorkspaceViewOutputSpec(128))
            val beforeReopen = workspace.renderModel(view).png
            val archive = temporary.resolve("batch.psd2live")
            val controller = ProjectController(vm)
            controller.save(workspace, archive)
            assertEquals(2, workspace.history().nodes.size)
            controller.open(workspace, archive)
            assertEquals(after.historyHeadNodeId, workspace.snapshot().historyHeadNodeId)
            assertNotEquals(after.state, workspace.snapshot().state)
            assertTrue(workspace.currentPuppet()!!.parameters.any { it.id.raw == "BatchAxis" })
            val afterReopen = workspace.renderModel(view).png
            assertContentEquals(beforeReopen, afterReopen)
            val visual = Files.createDirectories(Path.of("build/batch-job-visual"))
            Files.write(visual.resolve("before-reopen.png"), beforeReopen)
            Files.write(visual.resolve("after-reopen.png"), afterReopen)
            assertEquals(puppet.drawables.first().geometryGrid!!.axes.map { it.parameterId.raw to it.keys.toList() },
                workspace.currentPuppet()!!.drawables.first().geometryGrid!!.axes.map { it.parameterId.raw to it.keys.toList() })
            val export = workspace.exportModel(workspace.snapshot().state, temporary.resolve("export").toString())
            val cmo3 = export.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                .single { it.toString().endsWith(".cmo3") }
            val readBack = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource)
            assertTrue(readBack.parameters.any { it.id.raw == "BatchAxis" })
            workspace.checkoutHistory(before.historyHeadNodeId!!, MutationAuthor.USER)
            assertFalse(workspace.currentPuppet()!!.parameters.any { it.id.raw == "BatchAxis" })
            workspace.checkoutHistory(after.historyHeadNodeId!!, MutationAuthor.USER)
            assertEquals(0.8f, workspace.motionClips().single().curves.single().keys.single().value)
        }
    }

    @Test fun invalidLaterEditDoesNotPublishPreparedSettingsPixelsOrHistory() = runBlocking {
        fixture { vm, workspace, operations ->
            val before = workspace.snapshot()
            val state = vm.state.value
            val history = workspace.history()
            val raster = state.analysis!!.source.layers.single().raster.rgba.copyOf()
            val id = before.layers.single().id
            val input = batch(workspace, "failure", listOf(
                edit("source_paint_pencil", buildJsonObject {
                    put("layer_id", id); put("radius", 2)
                    putJsonArray("points") { add(buildJsonArray { add(4); add(4) }) }
                    putJsonArray("color") { add(255); add(0); add(0); add(255) }
                }),
                edit("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 3) } }),
                edit("parameter_delete", buildJsonObject { put("parameter_id", "Missing") })
            ))
            val failed = operations.waitBatch(input)
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            val failure = failed.getValue("error").jsonObject
            assertEquals("invalid_edit", failure.getValue("code").jsonPrimitive.content)
            assertEquals(2, failure.getValue("edit_index").jsonPrimitive.int)
            assertEquals("parameter_delete", failure.getValue("edit_operation").jsonPrimitive.content)
            assertEquals(before.state, workspace.snapshot().state)
            assertEquals(history, workspace.history())
            assertSame(state.previewModel, vm.state.value.previewModel)
            assertContentEquals(raster, vm.state.value.analysis!!.source.layers.single().raster.rgba)
            assertEquals(state.headStrength, vm.state.value.headStrength)
        }
    }

    @Test fun completeAndNetNoOpsPreserveStateAndBoundsAndContextAreValidatedFirst() = runBlocking {
        fixture { _, workspace, operations ->
            val before = workspace.snapshot()
            val settings = workspace.projectSettings()
            val value = settings.getValue("headStrength")
            val noOp = edit("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", value) } })
            val result = operations.apply(batch(workspace, "max", List(128) { noOp }))
            assertFalse(result.getValue("applied").jsonPrimitive.boolean)
            assertEquals(before.state, workspace.snapshot().state)
            val cancelOut = operations.apply(batch(workspace, "net-no-op", listOf(
                edit("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } }), noOp)))
            assertFalse(cancelOut.getValue("applied").jsonPrimitive.boolean)
            assertEquals(before.state, workspace.snapshot().state)
            assertEquals(1, workspace.history().nodes.size)
            for (edits in listOf(emptyList(), List(129) { noOp }, listOf(edit("preview_reset", buildJsonObject {})),
                listOf(edit("settings_update", buildJsonObject { put("state", before.state); putJsonObject("changes") { put("headStrength", 2) } })))) {
                assertFailsWith<WorkspaceValidationException> { operations.apply(batch(workspace, "invalid-${edits.size}-${edits.hashCode()}", edits)) }
                assertEquals(before.state, workspace.snapshot().state)
            }
            val stale = batch(workspace, "stale", listOf(noOp))
            workspace.applyDocumentEdits(before.state, "User edit", listOf(WorkspaceDocumentOperation("settings_update",
                buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } })), MutationAuthor.USER)
            assertEquals("user", workspace.history().nodes.last().actor)
            assertFailsWith<WorkspaceConflict> { operations.apply(stale) }
        }
    }

    @Test fun guiParameterDefinitionFolderAndKeysUseOneSharedCommitAndRollBackTogether() = runBlocking {
        fixture { vm, workspace, _ ->
            workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                put("op", "structure"); putJsonArray("edits") { add(buildJsonObject {
                    put("action", "create"); put("kind", "param_group"); put("id", "Folder"); put("name", "Folder")
                }) }
            }) }, MutationAuthor.USER)
            suspend fun save(action: String, parent: String? = null, keys: List<JsonObject> = emptyList()): String? {
                val completed = CompletableDeferred<String?>()
                vm.saveParameterDefinition(action, "GuiAxis", "GUI axis", -1f, 0f, 1f,
                    keyEdits = keys, parentGroupId = parent, onComplete = { completed.complete(it) })
                val result = withTimeout(10000) { completed.await() }
                withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                return result
            }
            val before = workspace.snapshot()
            val missingFolder = save("create", "Missing")
            assertNotNull(missingFolder)
            assertEquals(before.state, workspace.snapshot().state)
            assertTrue(workspace.currentPuppet()!!.parameters.none { it.id.raw == "GuiAxis" })
            val points = buildJsonObject {
                put("op", "parameter_keys"); put("parameter", "GuiAxis"); put("action", "add")
                putJsonArray("values") { add(-1); add(0); add(1) }
            }
            assertNull(save("create", "Folder", listOf(points)))
            assertEquals(3, workspace.history().nodes.size)
            assertEquals("user", workspace.history().nodes.last().actor)
            val puppet = workspace.currentPuppet()!!
            val folder = puppet.parameterTree.filterIsInstance<org.umamo.runtime.model.ParameterNode.Group>().single { it.id.raw == "Folder" }
            assertTrue(folder.children.filterIsInstance<org.umamo.runtime.model.ParameterNode.Param>().any { it.id.raw == "GuiAxis" })
            assertEquals(listOf(-1f, 0f, 1f), puppet.parameters.single { it.id.raw == "GuiAxis" }.keys)
            val mesh = "mesh:" + puppet.drawables.first().id.raw
            workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                put("op", "set"); put("target", mesh); putJsonObject("key") { put("GuiAxis", 0) }
                putJsonObject("channels") { put("opacity", 0.5) }
            }) }, MutationAuthor.AGENT)
            val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f))
            val beforeDelete = workspace.renderModel(WorkspaceModelViewRequest(
                parameters = mapOf("GuiAxis" to 0f), frame = frame, output = WorkspaceViewOutputSpec(256))).png
            assertNull(save("delete"))
            assertTrue(workspace.currentPuppet()!!.parameters.none { it.id.raw == "GuiAxis" })
            assertEquals(0.5f, workspace.currentPuppet()!!.drawables.first().opacity, 1e-6f)
            val reopened = workspace.renderModel(WorkspaceModelViewRequest(frame = frame, output = WorkspaceViewOutputSpec(256))).png
            val beforeImage = ImageIO.read(ByteArrayInputStream(beforeDelete))
            val afterImage = ImageIO.read(ByteArrayInputStream(reopened))
            assertContentEquals(beforeImage.getRGB(0, 0, 256, 256, null, 0, 256),
                afterImage.getRGB(0, 0, 256, 256, null, 0, 256))
            val visual = Files.createDirectories(Path.of("build/gui-command-visual"))
            Files.write(visual.resolve("before-delete.png"), beforeDelete)
            Files.write(visual.resolve("after-reopen.png"), reopened)
            val exported = workspace.exportModel(workspace.snapshot().state, temporary.resolve("definition-export").toString())
            val cmo3 = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                .single { it.toString().endsWith(".cmo3") }
            val readBack = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource)
            assertTrue(readBack.parameters.none { it.id.raw == "GuiAxis" })
            assertEquals(0.5f, readBack.drawables.first().opacity, 1e-6f)
            val controller = ProjectController(vm)
            val archive = temporary.resolve("gui-definition.psd2live")
            controller.save(workspace, archive)
            controller.open(workspace, archive)
            assertTrue(workspace.currentPuppet()!!.parameters.none { it.id.raw == "GuiAxis" })
            assertEquals(0.5f, workspace.currentPuppet()!!.drawables.first().opacity, 1e-6f)
        }
    }

    @Test fun guiJournalRejectsHistoryIdsAndOldLoadTokensBeforePublishingOneUserNode() = runBlocking {
        fixture { vm, workspace, _ ->
            val before = workspace.snapshot()
            val target = workspace.currentPuppet()!!.drawables.first().id.raw
            val edits = buildJsonArray { add(buildJsonObject {
                put("op", "structure"); putJsonArray("edits") { add(buildJsonObject {
                    put("action", "rename"); put("kind", "mesh"); put("id", target); put("name", "Renamed")
                }) }
            }) }
            suspend fun save(state: String): String? {
                val completed = CompletableDeferred<String?>()
                vm.saveAuthoringEdits(state, edits) { completed.complete(it) }
                val result = withTimeout(10000) { completed.await() }
                withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                return result
            }
            assertNotNull(save(before.historyHeadNodeId!!))
            assertEquals(before.state, workspace.snapshot().state)
            val controller = ProjectController(vm)
            val file = temporary.resolve("gesture.psd2live")
            controller.save(workspace, file)
            controller.open(workspace, file)
            assertEquals(before.historyHeadNodeId, workspace.snapshot().historyHeadNodeId)
            assertNotEquals(before.state, vm.currentWorkspaceState())
            assertNotNull(save(before.state))
            assertEquals(1, workspace.history().nodes.size)
            assertNull(save(vm.currentWorkspaceState()!!))
            assertEquals(2, workspace.history().nodes.size)
            assertEquals("user", workspace.history().nodes.last().actor)
            assertEquals("Renamed", workspace.currentPuppet()!!.drawables.first().name)
        }
    }

    @Test fun typedAndInternalCommandsRejectHistoryAliasesAndStaleLoadStates() = runBlocking {
        fixture { vm, workspace, _ ->
            val before = workspace.snapshot()
            val mesh = workspace.currentPuppet()!!.drawables.first().id.raw
            val layer = before.layers.single().id
            suspend fun reject(state: String) {
                assertFailsWith<WorkspaceConflict> { workspace.updateProjectSettings(state, buildJsonObject { put("headStrength", 2) }) }
                assertFailsWith<WorkspaceConflict> { workspace.setLayerMeshSettings(state, layer, null, true) }
                assertFailsWith<WorkspaceConflict> { workspace.createParameter(WorkspaceCreateParameterRequest("LateAxis", state, "Late")) }
                assertFailsWith<WorkspaceConflict> { workspace.setKeyform(WorkspaceKeyformSetRequest(state,
                    WorkspaceKeyformTargetRef("mesh", mesh), mapOf("MissingAxis" to 0f), channels = WorkspaceKeyformChannels(opacity = 0.5f))) }
                assertFailsWith<WorkspaceConflict> { workspace.editObjects(buildJsonObject {
                    put("state", state); putJsonArray("edits") { add(buildJsonObject {
                        put("action", "rename"); put("kind", "mesh"); put("id", mesh); put("name", "Late")
                    }) }
                }) }
                assertFailsWith<WorkspaceConflict> { workspace.paintSource(buildJsonObject { put("state", state); put("layer_id", layer) }) }
                assertFailsWith<WorkspaceConflict> { workspace.editSkeleton(state, buildJsonObject { put("mode", "auto") }) }
                assertFailsWith<WorkspaceConflict> { workspace.editMotion(state, buildJsonObject { put("mode", "seed_builtin") }) }
                assertFailsWith<WorkspaceConflict> { workspace.setPreviewSession(buildJsonObject { put("state", state); put("mode", "reset") }) }
                assertFailsWith<WorkspaceConflict> { workspace.exportModel(state, temporary.resolve("stale-export").toString()) }
                assertFailsWith<WorkspaceConflict> { workspace.deletePhysics("Missing", state) }
            }
            reject(before.historyHeadNodeId!!)
            assertEquals(before.state, workspace.snapshot().state)
            val controller = ProjectController(vm)
            val archive = temporary.resolve("opaque.psd2live")
            controller.save(workspace, archive)
            controller.open(workspace, archive)
            val reopened = workspace.snapshot()
            assertEquals(before.historyHeadNodeId, reopened.historyHeadNodeId)
            reject(before.state)
            assertEquals(reopened.state, workspace.snapshot().state)
            assertEquals(1, workspace.history().nodes.size)
            assertFalse(Files.exists(temporary.resolve("stale-export")))
        }
    }

    @Test fun guiSimulationAndSwingUseTrustedUserContextAndTheOriginalSessionState() = runBlocking {
        fixture { vm, workspace, _ ->
            val originalAutoBake = AppSettings.simulationAutoBake
            try {
                vm.setSimulationAutoBake(false)
                val mesh = workspace.currentPuppet()!!.drawables.first().id.raw
                val simulation = io.github.psd2live.core.sim.RigSimEdit("GuiSim", "GUI simulation",
                    io.github.psd2live.core.sim.SimKind.CLOTH, listOf(mesh), autoBake = false)
                vm.putSimulation(simulation)
                withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                assertEquals(simulation, workspace.listSimulations().single())
                assertEquals("user", workspace.history().nodes.last().actor)
                assertEquals(2, workspace.history().nodes.size)
                vm.deleteSimulation(simulation.id)
                withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                assertTrue(workspace.listSimulations().isEmpty())
                assertEquals("user", workspace.history().nodes.last().actor)

                vm.beginSwing(listOf(mesh))
                withTimeout(10000) { while (vm.swingSession == null) kotlinx.coroutines.delay(10) }
                assertNotNull(vm.swingSession)
                workspace.createParameter(WorkspaceCreateParameterRequest("ConcurrentAxis", workspace.snapshot().state, "Concurrent axis"))
                val changed = workspace.snapshot()
                vm.commitSwing()
                withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                assertNotNull(vm.swingSession?.error)
                assertEquals(changed.state, workspace.snapshot().state)
                assertTrue(workspace.listSwings().isEmpty())
                val oldSession = vm.swingSession!!.sessionId
                vm.beginSwing(listOf(mesh))
                withTimeout(10000) { while (vm.swingSession?.sessionId == null || vm.swingSession?.sessionId == oldSession) kotlinx.coroutines.delay(10) }
                vm.commitSwing()
                withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                assertNull(vm.swingSession)
                assertEquals(1, workspace.listSwings().size)
                assertEquals("user", workspace.history().nodes.last().actor)
                val archive = temporary.resolve("gui-swing.psd2live")
                val controller = ProjectController(vm)
                controller.save(workspace, archive)
                controller.open(workspace, archive)
                assertEquals(1, workspace.listSwings().size)
            } finally {
                vm.setSimulationAutoBake(originalAutoBake)
            }
        }
    }

    @Test fun mcpReturnsGeneratedHandlesAndIndexedFailuresAndRetriesOnlyOnce() = runBlocking {
        fixture { _, workspace, operations ->
            val server = createAgentMcpServer(workspace, operations)
            val connection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
                arrayOf(ClientConnection::class.java)) { _, method, _ ->
                if (method.name == "getSessionId") "batch-test" else error("Unexpected client call")
            } as ClientConnection
            suspend fun call(request: JsonObject) = server.tools.getValue("workspace_apply_edits").handler.invoke(connection,
                CallToolRequest(CallToolRequestParams("workspace_apply_edits", buildJsonObject { put("request", request) })))
            val mesh = workspace.currentPuppet()!!.drawables.first().id.raw
            val request = batch(workspace, "generated", listOf(edit("canvas_rotation", buildJsonObject {
                put("name", "Generated rotation"); putJsonArray("meshes") { add(mesh) }
            })))
            val first = call(request)
            assertFalse(first.isError == true)
            assertEquals(first.structuredContent, call(request).structuredContent)
            val started = first.structuredContent!!.getValue("data").jsonObject
            val reconnected = createAgentMcpServer(workspace, operations)
            assertEquals(first.structuredContent, reconnected.tools.getValue("workspace_apply_edits").handler.invoke(connection,
                CallToolRequest(CallToolRequestParams("workspace_apply_edits", buildJsonObject { put("request", request) }))).structuredContent)
            val completedCall = reconnected.tools.getValue("job_wait").handler.invoke(connection,
                CallToolRequest(CallToolRequestParams("job_wait", buildJsonObject { putJsonObject("request") { put("id", started.getValue("id")) } })))
            assertFalse(completedCall.isError == true)
            val completed = completedCall.structuredContent!!.getValue("data").jsonObject
            assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
            val data = completed.getValue("result").jsonObject
            val handle = data.getValue("changed").jsonArray.map { it.jsonPrimitive.content }.single { it.startsWith("rotation:") }
            assertTrue(workspace.currentPuppet()!!.deformers.any { it.id.raw == handle.removePrefix("rotation:") })
            assertEquals(2, workspace.history().nodes.size)
            val before = workspace.snapshot()
            val failure = call(batch(workspace, "indexed-failure", listOf(
                edit("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 3) } }),
                edit("canvas_rotation", buildJsonObject { put("name", "Invalid"); put("add_to", "parent_of_deformer"); put("deformer_id", "missing") }))))
            assertFalse(failure.isError == true)
            val failedJob = operations.registry.invoke("job_wait", buildJsonObject {
                put("id", failure.structuredContent!!.getValue("data").jsonObject.getValue("id"))
            }, agent).data
            assertEquals("failed", failedJob.getValue("status").jsonPrimitive.content)
            val error = failedJob.getValue("error").jsonObject
            assertEquals("invalid_edit", error.getValue("code").jsonPrimitive.content)
            assertEquals(1, error.getValue("edit_index").jsonPrimitive.int)
            assertEquals("canvas_rotation", error.getValue("edit_operation").jsonPrimitive.content)
            assertEquals(before.state, workspace.snapshot().state)
            assertEquals(2, workspace.history().nodes.size)
        }
    }

    @Test fun batchPublicationReusesTheExactSingleOperationBusinessSchemas() = runBlocking {
        fixture { _, _, operations ->
            val batch = operations.registry.definition("workspace_apply_edits").requestSchema
            val variants = batch.getValue("properties").jsonObject.getValue("edits").jsonObject.getValue("items").jsonObject.getValue("oneOf").jsonArray
            val ids = variants.map { it.jsonObject.getValue("properties").jsonObject.getValue("operation").jsonObject.getValue("const").jsonPrimitive.content }.toSet()
            assertEquals(WorkspaceDocumentEdits.supported, ids)
            for (variant in variants) {
                val fields = variant.jsonObject.getValue("properties").jsonObject
                val id = fields.getValue("operation").jsonObject.getValue("const").jsonPrimitive.content
                val single = operations.registry.definition(id)
                assertTrue(single.batchable)
                val nested = fields.getValue("request").jsonObject
                assertEquals(single.requestSchema.getValue("properties").jsonObject - setOf("project_id", "request_id", "state"), nested.getValue("properties").jsonObject)
                assertFalse(nested.getValue("properties").jsonObject.keys.any { it in setOf("state", "project_id", "request_id") })
            }
            assertFalse(operations.registry.definition("snapshot_create").batchable)
            assertFalse(operations.registry.definition("project_export_model").batchable)
            assertFalse(operations.registry.definition("workspace_apply_edits").batchable)
            assertTrue(operations.registry.definition("workspace_apply_edits").jobBacked)
        }
    }
}
