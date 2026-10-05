package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceSimulationPreviewIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun guiContinuousPoseAndPublicSessionsShowTheSameWorldVerticesAndNeverSaveTheRunningScene() = runBlocking<Unit> {
        val path = temporary.resolve("strip.png"); val image = BufferedImage(24, 80, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..76) for (x in 8..15) image.setRGB(x, y, 0xff5599bb.toInt())
        ImageIO.write(image, "png", path.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 24); put("height", 80); putJsonArray("layers") { add(buildJsonObject {
                        put("path", path.toString()); put("name", "Synthetic strip"); put("role", "objects")
                    }) }
                })
                workspace.applyDocumentEdits(workspace.snapshot().state, "Drive", simulationDrivingEdits(workspace.currentPuppet()!!), MutationAuthor.USER)
                workspace.applyDocumentEdits(workspace.snapshot().state, "Body", listOf(simulationPut(workspace.currentPuppet()!!)), MutationAuthor.USER)
                suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10000) { while (!predicate()) delay(10) }
                val before = workspace.snapshot(); val pose = workspace.previewSession(); val model = vm.state.value.previewModel
                val history = workspace.history()
                vm.setSimulationPreview("sway")
                waitFor { vm.simulationSessionId != null && vm.simulationFrames.value != null }
                val guiId = vm.simulationSessionId!!; val initial = vm.simulationFrames.value!!
                vm.beginParameterScrub(); vm.setParameterValue(ParameterId("Drive"), 20f)
                vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.PREVIEW)
                vm.requestSdkFrame(24, 80, 1f, 0f, 0f, viewId = vm.canvasRenderKey(vm.state.value.activeCanvas.id))
                vm.setCanvasMode(vm.state.value.activeCanvas.id, CanvasMode.EDIT)
                waitFor { (vm.simulationFrames.value?.serial ?: 0) > initial.serial }
                val driven = workspace.simulationPreviewFrame(guiId)
                assertEquals(20f, driven.values.getValue(ParameterId("Drive")))
                driven.frame.positions.forEach { (id, vertices) -> assertContentEquals(vertices, vm.simulationFrames.value!!.positions.getValue(id)) }
                assertEquals(before.state, workspace.snapshot().state); assertEquals(history.nodes, workspace.history().nodes)
                assertSame(model, vm.state.value.previewModel); assertEquals(pose, workspace.previewSession())
                vm.cancelParameterScrub()
                vm.restartSimulationPreview()
                waitFor { vm.simulationFrames.value!!.serial > driven.frame.serial }
                val restarted = workspace.simulationPreviewFrame(guiId)
                initial.positions.forEach { (id, vertices) -> assertContentEquals(vertices, restarted.frame.positions.getValue(id)) }
                WorkspaceOperations(workspace).use { operations ->
                    val actor = WorkspaceOperationContext(MutationAuthor.AGENT); var sequence = 0
                    suspend fun call(operation: String, fields: JsonObject): WorkspaceOperationOutput {
                        val definition = operations.registry.definition(operation); val captured = workspace.snapshot()
                        val request = if (definition.kind == WorkspaceOperationKind.QUERY) fields else JsonObject(fields + buildJsonObject {
                            put("state", captured.state); put("project_id", captured.projectId); put("request_id", "preview-${sequence++}")
                        })
                        val result = operations.registry.invoke(operation, request, actor)
                        if (!definition.jobBacked) return result
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", result.data.getValue("id")) }, actor).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        assertEquals(result.data.getValue("id"), operations.registry.invoke(operation, request, actor).data.getValue("id"))
                        return WorkspaceOperationOutput(terminal.getValue("result").jsonObject)
                    }
                    val begun = call("simulation_preview", buildJsonObject { put("mode", "start"); put("simulation_id", "sway") }).data
                    val session = begun.getValue("session_id")
                    val frame = call("simulation_preview_step", buildJsonObject {
                        put("session_id", session); put("dt", 1f / 60f); put("steps", 20); putJsonObject("values") { put("Drive", -20) }
                    }).data
                    val observed = workspace.simulationPreviewFrame(session.jsonPrimitive.content)
                    assertEquals(frame.getValue("serial"), observed.report.getValue("serial"))
                    val query = call("simulation_preview_get", buildJsonObject { put("session_id", session); put("include_positions", true) }).data
                    observed.frame.positions.forEach { (id, vertices) ->
                        assertContentEquals(vertices, query.getValue("positions").jsonObject.getValue(id.raw).jsonArray.map { it.jsonPrimitive.float }.toFloatArray())
                        assertContentEquals(vertices, vm.simulationFrames.value!!.positions.getValue(id))
                    }
                    val bounds = Bounds(0f, 0f, 24f, 80f)
                    val request = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(bounds), output = WorkspaceViewOutputSpec(256))
                    val guiImage = workspace.renderSimulationPreview(session.jsonPrimitive.content, request)
                    val publicImage = call("simulation_preview_render", buildJsonObject {
                        put("session_id", session); putJsonArray("bounds") { add(0); add(0); add(24); add(80) }; put("size", 256)
                    })
                    assertContentEquals(guiImage.png, publicImage.images.single())
                    val direct = WorkspaceViewRenderer.modelComposite(observed.model, workspace.snapshot().revisionId,
                        observed.values.mapKeys { it.key.raw }, observed.model.rig.layerIdByDrawableId.values.toSet(), emptySet(),
                        request.frame, request.background, request.output, worldPositions = observed.frame.positions)
                    assertContentEquals(direct.png, guiImage.png)
                    assertFalse(workspace.renderModel(request).png.contentEquals(guiImage.png), "Reference geometry must differ from the resting committed rig")
                    val serial = observed.frame.serial
                    call("simulation_preview_get", buildJsonObject { put("session_id", session) })
                    assertEquals(serial, workspace.simulationPreviewFrame(session.jsonPrimitive.content).frame.serial)
                    assertEquals(before.state, workspace.snapshot().state); assertEquals(history.nodes, workspace.history().nodes); assertEquals(pose, workspace.previewSession())
                    val archive = temporary.resolve("running-preview.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertNull(vm.simulationSessionId); assertNull(vm.simulationFrames.value); assertNull(vm.state.value.simulationPreviewId)
                    assertEquals(pose.getValue("values"), workspace.previewSession().getValue("values")); assertEquals(history.nodes, workspace.history().nodes)
                    assertTrue(workspace.simulationPreviewFrame(session.jsonPrimitive.content).report.getValue("stale").jsonPrimitive.boolean)
                    val visuals = Path.of("build/simulation-preview-visual").toAbsolutePath(); Files.createDirectories(visuals)
                    Files.write(visuals.resolve("live-reference.png"), guiImage.png)
                    Files.write(visuals.resolve("committed-rest.png"), workspace.renderModel(request).png)
                }
            }
        }
    }
}
