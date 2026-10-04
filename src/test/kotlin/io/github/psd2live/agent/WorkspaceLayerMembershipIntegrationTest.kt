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

class WorkspaceLayerMembershipIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun publicAndGuiRestorePreserveBornMeshAcrossDeletedArchiveAndExport() = runBlocking<Unit> {
        val art = temporary.resolve("art.png"); val blank = temporary.resolve("blank.png")
        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..21) for (x in 2..21) image.setRGB(x, y, 0xff8899aa.toInt())
        ImageIO.write(image, "png", art.toFile())
        ImageIO.write(BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB), "png", blank.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 48); put("height", 48); putJsonArray("layers") {
                        for ((path, name) in listOf(art to "Visible artwork", blank to "Blank artwork")) add(buildJsonObject {
                            put("path", path.toString()); put("name", name); put("role", "objects")
                        })
                    }
                })
                val layer = created.affectedLayerIds.last()
                val context = WorkspaceOperationContext(MutationAuthor.AGENT)
                WorkspaceOperations(workspace).use { operations ->
                    var sequence = 0
                    suspend fun call(id: String, fields: JsonObject): JsonObject {
                        val captured = workspace.snapshot()
                        val request = JsonObject(fields + buildJsonObject {
                            put("project_id", captured.projectId); put("state", captured.state); put("request_id", "membership-${sequence++}")
                        })
                        val started = operations.registry.invoke(id, request, context).data
                        if (!operations.registry.definition(id).jobBacked) return started
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, context).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        validateOperationSchema(terminal.getValue("result"), operations.registry.definition(id).jobResultSchema!!)
                        assertEquals(started.getValue("id"), operations.registry.invoke(id, request, context).data.getValue("id"))
                        return terminal.getValue("result").jsonObject
                    }
                    call("source_paint_shape", buildJsonObject {
                        put("layer_id", layer); put("shape", "rectangle"); put("filled", true)
                        put("from", buildJsonArray { add(26); add(26) }); put("to", buildJsonArray { add(44); add(44) })
                        put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
                    })
                    val mesh = workspace.currentPuppet()!!.drawables.single { vm.state.value.previewModel!!.rig.layerIdByDrawableId[it.id.raw] == layer }
                    call("parameter_create", buildJsonObject { put("parameter_id", "LayerAxis"); put("name", "Layer axis") })
                    call("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("LayerAxis", 1) }
                        putJsonObject("channels") { put("opacity", 0.4) }
                    }) } })
                    val before = workspace.currentPuppet()!!
                    val frame = WorkspaceModelViewRequest(parameters = mapOf("LayerAxis" to 1f),
                        frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 48f, 48f)),
                        background = WorkspaceViewBackground.TRANSPARENT, output = WorkspaceViewOutputSpec(256))
                    val originalPng = workspace.renderModel(frame).png
                    val count = workspace.history().nodes.size
                    val deleted = call("layer_soft_delete", buildJsonObject { put("layer_id", layer) })
                    assertEquals(count + 1, workspace.history().nodes.size)
                    assertTrue(workspace.currentPuppet()!!.drawables.none { it.id == mesh.id })
                    assertTrue(workspace.snapshot().layers.single { it.id == layer }.deleted)
                    val deletedPng = workspace.renderModel(frame).png
                    assertFalse(originalPng.contentEquals(deletedPng))
                    val archive = temporary.resolve("deleted.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(deleted.getValue("history_node_id").jsonPrimitive.content, workspace.history().headNodeId)
                    assertContentEquals(deletedPng, workspace.renderModel(frame).png)
                    suspend fun export(directory: String) = call("project_export_model", buildJsonObject {
                        put("output_directory", temporary.resolve(directory).toString())
                    }).getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                        .single { it.toString().endsWith(".cmo3") }.let { Cmo3ModelImport.read(Files.readAllBytes(it)).puppet }
                    assertTrue(export("deleted-export").drawables.none { it.id == mesh.id })
                    call("layer_restore", buildJsonObject { put("layer_ids", buildJsonArray { add(layer) }) })
                    assertContentEquals(originalPng, workspace.renderModel(frame).png)
                    val restored = export("restored-export")
                    val evaluator = CpuDeformationEvaluator()
                    for (axis in listOf(-1f, 0f, 1f)) {
                        val pose = mapOf(ParameterId("LayerAxis") to axis)
                        val expected = evaluator.evaluate(before, pose)
                        val actual = evaluator.evaluate(restored, pose)
                        expected.worldPositions.forEach { (id, vertices) ->
                            vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                            assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                        }
                    }
                    val activeCount = workspace.history().nodes.size
                    val noop = call("layer_restore", buildJsonObject {})
                    assertEquals(false, noop.getValue("applied").jsonPrimitive.boolean)
                    assertEquals(activeCount, workspace.history().nodes.size)
                    vm.deleteLayer(layer)
                    withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                    assertNull(vm.state.value.errorMessage)
                    assertEquals("user", workspace.history().nodes.last().actor)
                    assertTrue(workspace.currentPuppet()!!.drawables.none { it.id == mesh.id })
                    vm.restoreAllDeletedLayers()
                    withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                    assertNull(vm.state.value.errorMessage)
                    assertEquals("user", workspace.history().nodes.last().actor)
                    assertContentEquals(originalPng, workspace.renderModel(frame).png)
                    call("project_save", buildJsonObject {})
                    val nodes = workspace.history().nodes
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(nodes, workspace.history().nodes)
                    assertContentEquals(originalPng, workspace.renderModel(frame).png)
                    val visuals = Path.of("build/layer-membership-visual").toAbsolutePath()
                    Files.createDirectories(visuals)
                    Files.write(visuals.resolve("before-delete.png"), originalPng)
                    Files.write(visuals.resolve("deleted-reopened.png"), deletedPng)
                    Files.write(visuals.resolve("restored-reopened.png"), workspace.renderModel(frame).png)
                }
            }
        }
    }
}
