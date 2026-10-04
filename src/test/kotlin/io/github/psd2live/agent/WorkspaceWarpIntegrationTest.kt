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
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceWarpIntegrationTest {
    @TempDir lateinit var temporary: Path

    private fun sameMotion(before: PuppetModel, after: PuppetModel) {
        val evaluator = CpuDeformationEvaluator()
        for (blend in listOf(0f, 0.4f, 1f)) for (axis in listOf(-1f, 0f, 0.5f, 1f)) {
            val pose = mapOf(ParameterId("Blend") to blend, ParameterId("WarpAxis") to axis)
            val expected = evaluator.evaluate(before, pose); val actual = evaluator.evaluate(after, pose)
            expected.worldPositions.forEach { (id, positions) ->
                positions.indices.forEach { assertEquals(positions[it], actual.worldPositions.getValue(id)[it], 0.001f, "$pose/$id/$it") }
                assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
            }
        }
    }

    @Test fun guiParentPublicIndependentWarpAndTypedWarpSaveReopenExportAndKeepMotion() = runBlocking<Unit> {
        val png = temporary.resolve("art.png"); val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 12..34) for (x in 12..30) image.setRGB(x, y, 0xff70a0c0.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject { put("width", 64); put("height", 64); putJsonArray("layers") {
                    add(buildJsonObject { put("path", png.toString()); put("name", "Artwork"); put("role", "objects") })
                } })
                val layer = workspace.snapshot().layers.single().id
                vm.selectLayer(layer)
                vm.editorForFocusedCanvas().createWarp()
                withTimeout(10000) { vm.state.first { !it.workspaceEditBusy } }
                assertNull(vm.state.value.errorMessage)
                assertEquals("user", workspace.history().nodes.last().actor)
                val guiParent = workspace.currentPuppet()!!.drawables.single().parentDeformerId
                assertNotNull(guiParent)
                val context = WorkspaceOperationContext(MutationAuthor.AGENT)
                WorkspaceOperations(workspace).use { operations ->
                    var sequence = 0
                    suspend fun call(id: String, fields: JsonObject): JsonObject {
                        val capture = workspace.snapshot()
                        val request = JsonObject(fields + buildJsonObject { put("project_id", capture.projectId); put("state", capture.state); put("request_id", "warp-${sequence++}") })
                        val result = operations.registry.invoke(id, request, context).data
                        if (!operations.registry.definition(id).jobBacked) return result
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", result.getValue("id")) }, context).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        validateOperationSchema(terminal.getValue("result"), operations.registry.definition(id).jobResultSchema!!)
                        assertEquals(result.getValue("id"), operations.registry.invoke(id, request, context).data.getValue("id"))
                        return terminal.getValue("result").jsonObject
                    }
                    val mesh = workspace.currentPuppet()!!.drawables.single()
                    call("parameter_create", buildJsonObject { put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape") })
                    call("parameter_create", buildJsonObject { put("parameter_id", "WarpAxis"); put("name", "Warp axis"); put("min", -1); put("max", 1) })
                    call("rig_deform", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                        put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("Blend", 1) }
                        putJsonArray("operations") { add(buildJsonObject { put("type", "translate"); put("delta", buildJsonArray { add(0.03); add(0) }) }) }
                    }) } })
                    call("vertex_group_update", buildJsonObject { put("target", "mesh:${mesh.id.raw}"); put("name", "Pin"); put("kind", "pin"); put("rule", "fill"); put("value", 0.5) })
                    val before = workspace.currentPuppet()!!
                    val view = WorkspaceModelViewRequest(parameters = mapOf("Blend" to 1f), frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 64f, 64f)),
                        background = WorkspaceViewBackground.TRANSPARENT, output = WorkspaceViewOutputSpec(256))
                    val beforePng = workspace.renderModel(view).png
                    val count = workspace.history().nodes.size
                    val result = call("rig_create_warp", buildJsonObject { put("id", "Independent"); put("name", "Independent"); put("targets", buildJsonArray { add("mesh:${mesh.id.raw}") }) })
                    assertEquals("warp:Independent", result.getValue("target").jsonPrimitive.content)
                    assertEquals(count + 1, workspace.history().nodes.size)
                    assertEquals(guiParent, workspace.currentPuppet()!!.deformers.single { it.id.raw == "Independent" }.parent)
                    sameMotion(before, workspace.currentPuppet()!!)
                    val typed = RigWarpEdit("TypedChild", "Typed child", "Independent", listOf(mesh.id.raw), 4, 4, true)
                    workspace.createWarp(typed, workspace.snapshot().state, null)
                    sameMotion(before, workspace.currentPuppet()!!)
                    assertEquals(before.vertexGroups, workspace.currentPuppet()!!.vertexGroups)
                    assertTrue(WorkspaceStateCodec.document(vm.state.value).rigEdits.warpEdits.isEmpty())
                    assertEquals(2, WorkspaceStateCodec.document(vm.state.value).rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == "warp" })
                    call("rig_deform", buildJsonObject { putJsonArray("changes") { for (value in listOf(-1, 0, 1)) add(buildJsonObject {
                        put("target", "warp:TypedChild"); putJsonObject("key") { put("WarpAxis", value) }
                        putJsonArray("operations") { add(buildJsonObject { put("type", "translate"); put("delta", buildJsonArray { add(value * 0.025); add(0) }) }) }
                    }) } })
                    val authored = workspace.currentPuppet()!!; val authoredPng = workspace.renderModel(view).png
                    val archive = temporary.resolve("warp.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    val history = workspace.history().nodes
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(history, workspace.history().nodes)
                    sameMotion(authored, workspace.currentPuppet()!!)
                    assertContentEquals(authoredPng, workspace.renderModel(view).png)
                    val files = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("export").toString()) })
                        .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                    val imported = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.toString().endsWith(".cmo3") })).puppet
                    sameMotion(authored, imported)
                    val visuals = Path.of("build/warp-command-visual").toAbsolutePath(); Files.createDirectories(visuals)
                    Files.write(visuals.resolve("before-create.png"), beforePng)
                    Files.write(visuals.resolve("authored.png"), authoredPng)
                    Files.write(visuals.resolve("reopened.png"), workspace.renderModel(view).png)
                }
            }
        }
    }
}
