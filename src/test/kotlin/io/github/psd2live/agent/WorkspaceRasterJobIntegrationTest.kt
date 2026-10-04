package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import org.umamo.render.eval.CpuDeformationEvaluator
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceRasterJobIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun allPaintJobsKeepNewMeshBindingsAcrossSaveReopenAndModelExport() = runBlocking<Unit> {
        val visible = temporary.resolve("visible.png"); val blank = temporary.resolve("blank.png")
        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..21) for (x in 2..21) image.setRGB(x, y, 0xff8899aa.toInt())
        ImageIO.write(image, "png", visible.toFile())
        ImageIO.write(BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB), "png", blank.toFile())
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temporary.resolve("store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 48); put("height", 48); putJsonArray("layers") {
                        for ((path, name) in listOf(visible to "Visible artwork", blank to "Blank artwork")) add(buildJsonObject {
                            put("path", path.toString()); put("name", name); put("role", "objects")
                        })
                    }
                })
                val layer = created.affectedLayerIds.last()
                val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                WorkspaceOperations(workspace).use { operations ->
                    var sequence = 0
                    fun request(fields: JsonObject) = JsonObject(fields + buildJsonObject {
                        val c = workspace.snapshot(); put("project_id", c.projectId); put("state", c.state); put("request_id", "paint-${sequence++}")
                    })
                    suspend fun call(id: String, fields: JsonObject): JsonObject {
                        val input = request(fields)
                        val started = operations.registry.invoke(id, input, agent).data
                        if (!operations.registry.definition(id).jobBacked) return started
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, agent).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        validateOperationSchema(terminal.getValue("result"), operations.registry.definition(id).jobResultSchema!!)
                        assertEquals(started.getValue("id"), operations.registry.invoke(id, input, agent).data.getValue("id"))
                        return terminal.getValue("result").jsonObject
                    }
                    call("settings_update", buildJsonObject { putJsonObject("changes") {
                        put("atlasSize", 256); put("meshSpacing", 16); put("meshOnly", true); put("generatePhysics", false); put("exportMoc3", false)
                    } })
                    fun paint(mode: String, color: Int = 30, rebuild: Boolean = false) = buildJsonObject {
                        put("layer_id", layer); put("rebuild_mesh", rebuild)
                        if (mode != "clear") put("color", buildJsonArray { add(color); add(120); add(220); add(255) })
                        when (mode) {
                            "brush", "pencil", "eraser" -> { put("radius", 2); putJsonArray("points") { add(buildJsonArray { add(32); add(32) }) } }
                            "bucket" -> put("point", buildJsonArray { add(32); add(32) })
                            "shape" -> {
                                put("shape", "rectangle"); put("filled", true)
                                put("from", buildJsonArray { add(26); add(26) }); put("to", buildJsonArray { add(44); add(44) })
                            }
                        }
                    }
                    val born = call("source_paint_shape", paint("shape"))
                    val mesh = workspace.currentPuppet()!!.drawables.single { viewModel.state.value.previewModel!!.rig.layerIdByDrawableId[it.id.raw] == layer }
                    assertTrue(born.getValue("changed").jsonArray.any { it.jsonPrimitive.content == "mesh:${mesh.id.raw}" })
                    call("parameter_create", buildJsonObject { put("parameter_id", "PaintDriver"); put("name", "Paint driver") })
                    call("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("PaintDriver", 1) }
                        putJsonObject("channels") { put("opacity", 0.4) }
                    }) } })
                    for ((index, mode) in listOf("brush", "pencil", "bucket", "eraser", "clear").withIndex()) {
                        val count = workspace.history().nodes.size
                        call("source_paint_$mode", paint(mode, 40 + index))
                        assertEquals(count + 1, workspace.history().nodes.size)
                        assertEquals(mesh.id, workspace.currentPuppet()!!.drawables.single { it.id == mesh.id }.id)
                        assertContentEquals(mesh.mesh!!.positions, workspace.currentPuppet()!!.drawables.single { it.id == mesh.id }.mesh!!.positions)
                    }
                    call("source_paint_shape", paint("shape", 70, true))
                    val noOpCount = workspace.history().nodes.size
                    val noOp = call("source_paint_shape", paint("shape", 70))
                    assertEquals(false, noOp.getValue("applied").jsonPrimitive.boolean)
                    assertEquals(noOpCount, workspace.history().nodes.size)
                    val puppet = workspace.currentPuppet()!!
                    val history = workspace.history()
                    val view = WorkspaceModelViewRequest(parameters = mapOf("PaintDriver" to 1f),
                        frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 48f, 48f)),
                        background = WorkspaceViewBackground.TRANSPARENT, output = WorkspaceViewOutputSpec(256))
                    val rendered = workspace.renderModel(view).png
                    val archive = temporary.resolve("raster.psd2live")
                    val saved = call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(history.headNodeId, saved.getValue("history_node_id").jsonPrimitive.content)
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(history.nodes, workspace.history().nodes)
                    val reopened = workspace.currentPuppet()!!
                    val reopenedPng = workspace.renderModel(view).png
                    assertContentEquals(rendered, reopenedPng)
                    val directory = Path.of("build/raster-command-visual").toAbsolutePath()
                    Files.createDirectories(directory)
                    Files.write(directory.resolve("before-reopen.png"), rendered)
                    Files.write(directory.resolve("after-reopen.png"), reopenedPng)
                    val exported = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("export").toString()) })
                    val cmo = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }.single { it.toString().endsWith(".cmo3") }
                    val imported = Cmo3ModelImport.read(Files.readAllBytes(cmo)).puppet
                    val evaluator = CpuDeformationEvaluator()
                    for (value in listOf(-1f, 0f, 1f)) {
                        val pose = mapOf(ParameterId("PaintDriver") to value)
                        val expected = evaluator.evaluate(puppet, pose)
                        for (model in listOf(reopened, imported)) {
                            val actual = evaluator.evaluate(model, pose)
                            expected.worldPositions.forEach { (id, vertices) ->
                                assertEquals(vertices.size, actual.worldPositions.getValue(id).size)
                                vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                                assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                            }
                        }
                    }
                }
            }
        }
    }
}
