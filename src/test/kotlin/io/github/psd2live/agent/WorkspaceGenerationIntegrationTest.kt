package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceGenerationIntegrationTest {
    @TempDir lateinit var temporary: Path
    @Test fun guiAndPublicGenerationCommandsRebuildPersistAndExportTheSameBindings() = runBlocking<Unit> {
        val art = temporary.resolve("art.png"); val image = BufferedImage(48, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 5..58) for (x in 8..39) image.setRGB(x, y, 0xff7799bb.toInt())
        ImageIO.write(image, "png", art.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 48); put("height", 64); putJsonArray("layers") { add(buildJsonObject {
                        put("path", art.toString()); put("name", "Synthetic artwork"); put("role", "objects")
                    }) }
                })
                val layer = created.affectedLayerIds.single(); val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                WorkspaceOperations(workspace).use { operations ->
                    var sequence = 0
                    suspend fun call(id: String, fields: JsonObject): JsonObject {
                        val before = workspace.snapshot(); val input = JsonObject(fields + buildJsonObject {
                            put("project_id", before.projectId); put("state", before.state); put("request_id", "generation-${sequence++}")
                        })
                        val job = operations.registry.invoke(id, input, agent).data
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        validateOperationSchema(terminal.getValue("result"), operations.registry.definition(id).jobResultSchema!!)
                        assertEquals(job.getValue("id"), operations.registry.invoke(id, input, agent).data.getValue("id"))
                        return terminal.getValue("result").jsonObject
                    }
                    suspend fun gui(action: () -> Unit) {
                        val count = workspace.history().nodes.size; action()
                        withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                        assertNull(vm.state.value.errorMessage)
                        assertEquals(count + 1, workspace.history().nodes.size)
                        assertEquals("user", workspace.history().nodes.last().actor)
                    }
                    gui { vm.setGlobalMeshSettings(MeshSettings(edgeMode = MeshEdgeMode.TRIPLE, maxEdgeDistance = 24f)) }
                    assertEquals("TRIPLE", workspace.projectSettings().getValue("meshEdgeMode").jsonPrimitive.content)
                    val gestureState = workspace.snapshot().state; val gestureCount = workspace.history().nodes.size
                    vm.beginEditorGesture()
                    vm.setGlobalMeshSettings(MeshSettings(edgeMode = MeshEdgeMode.TRIPLE, maxEdgeDistance = 40f))
                    vm.setGlobalMeshSettings(MeshSettings(edgeMode = MeshEdgeMode.TRIPLE, maxEdgeDistance = 32f))
                    assertEquals(gestureState, workspace.snapshot().state)
                    vm.endEditorGesture(); workspace.awaitEditorDrafts()
                    withTimeout(10000) { vm.state.first { !it.editorDraftBusy } }
                    assertNull(vm.state.value.errorMessage); assertEquals(gestureCount + 1, workspace.history().nodes.size)
                    assertEquals(32f, workspace.projectSettings().getValue("meshMaxEdgeDistance").jsonPrimitive.float)
                    val previewSettings = MeshSettings(outerMargin = 4f, edgeMode = MeshEdgeMode.DOUBLE)
                    val previewState = workspace.snapshot().state
                    vm.previewPartMeshSettings(layer, previewSettings)
                    withTimeout(10000) { vm.state.first { it.previewModel?.config?.meshOverrides?.get(layer) == previewSettings } }
                    assertEquals(previewState, workspace.snapshot().state)
                    gui { vm.confirmPartMeshSettingsPreview(layer, previewSettings) }
                    assertEquals(4f, workspace.layerMeshSettings(layer).getValue("outerMargin").jsonPrimitive.float)
                    gui { vm.resetPartMeshSettings(layer) }
                    gui { vm.setLayerClassification(layer, LayerClassificationOverride(LayerType.TOGGLE, SemanticTag.OBJECTS, parameter = "Decoration")) }
                    assertTrue(workspace.snapshot().parameters.any { it.id == "Decoration" })
                    val typedCount = workspace.history().nodes.size; val typedState = workspace.snapshot().state
                    vm.beginEditorField("classification.$layer.parameter")
                    vm.setLayerClassification(layer, LayerClassificationOverride(LayerType.TOGGLE, SemanticTag.OBJECTS, parameter = "De"))
                    vm.setLayerClassification(layer, LayerClassificationOverride(LayerType.TOGGLE, SemanticTag.OBJECTS, parameter = "Decoration"))
                    assertEquals(typedState, workspace.snapshot().state)
                    vm.endEditorField("classification.$layer.parameter"); workspace.awaitEditorDrafts()
                    withTimeout(10000) { vm.state.first { !it.editorDraftBusy } }
                    assertNull(vm.state.value.errorMessage); assertEquals(typedCount, workspace.history().nodes.size)
                    val mesh = workspace.currentPuppet()!!.drawables.single()
                    workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("Decoration", 1) }
                        putJsonObject("geometry") { put("positionDeltas", JsonArray(List(mesh.mesh!!.positions.size) { JsonPrimitive(0.3f) })) }
                        putJsonObject("channels") { put("opacity", 0.4) }
                    }) }, MutationAuthor.USER)
                    call("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } })
                    call("layer_mesh_update", buildJsonObject { put("layer_id", layer); putJsonObject("changes") { put("outerMargin", 5) } })
                    val meshBaseNode = workspace.history().nodes.last().id
                    gui { vm.setPartMeshSettings(layer, MeshSettings(outerMargin = 6f, edgeMode = MeshEdgeMode.TRIPLE,
                        maxEdgeDistance = 32f, interiorDensity = 6f)) }
                    val guiMesh = workspace.currentPuppet()!!.drawables.single()
                    assertTrue(guiMesh.geometryGrid!!.cells.any { it.form.positionDeltas.any { delta -> delta != 0f } })
                    workspace.checkoutHistory(meshBaseNode, MutationAuthor.USER)
                    call("layer_mesh_update", buildJsonObject { put("layer_id", layer); putJsonObject("changes") {
                        put("outerMargin", 6); put("interiorDensity", 6)
                    } })
                    val publicMesh = workspace.currentPuppet()!!.drawables.single()
                    assertContentEquals(guiMesh.mesh!!.positions, publicMesh.mesh!!.positions)
                    guiMesh.geometryGrid.cells.zip(publicMesh.geometryGrid!!.cells).forEach { (a, b) ->
                        assertContentEquals(a.form.positionDeltas, b.form.positionDeltas)
                    }
                    val count = workspace.history().nodes.size
                    val noop = call("layer_classify", buildJsonObject { put("layer_id", layer); put("parameter", "Decoration") })
                    assertEquals(false, noop.getValue("applied").jsonPrimitive.boolean)
                    assertEquals(count, workspace.history().nodes.size)
                    val before = workspace.currentPuppet()!!
                    val frame = WorkspaceModelViewRequest(parameters = mapOf("Decoration" to 1f),
                        frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 48f, 64f)),
                        background = WorkspaceViewBackground.TRANSPARENT, output = WorkspaceViewOutputSpec(256))
                    val png = workspace.renderModel(frame).png
                    val archive = temporary.resolve("generation.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    val nodes = workspace.history().nodes
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(nodes, workspace.history().nodes); assertContentEquals(png, workspace.renderModel(frame).png)
                    val files = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("export").toString()) })
                        .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                    val exported = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.toString().endsWith(".cmo3") })).puppet
                    val evaluator = CpuDeformationEvaluator()
                    for (value in listOf(0f, 0.5f, 1f)) {
                        val pose = mapOf(ParameterId("Decoration") to value); val expected = evaluator.evaluate(before, pose)
                        for (actualModel in listOf(workspace.currentPuppet()!!, exported)) {
                            val actual = evaluator.evaluate(actualModel, pose)
                            expected.worldPositions.forEach { (id, vertices) ->
                                vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                                assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                            }
                        }
                    }
                    val visuals = Path.of("build/generation-command-visual").toAbsolutePath(); Files.createDirectories(visuals)
                    Files.write(visuals.resolve("before-reopen.png"), png)
                    Files.write(visuals.resolve("after-reopen.png"), workspace.renderModel(frame).png)
                }
            }
        }
    }
}
