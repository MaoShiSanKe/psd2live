package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceAssetParentIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun desktopAdditionAndMcpRelocationShareParentGeometryArchiveAndExportForBothModelKinds() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val directory = temporary.resolve(if (imported) "imported" else "generated")
            Files.createDirectories(directory)
            val original = directory.resolve("original.png")
            val assetFile = directory.resolve("asset.png")
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xffff8844.toInt())
            ImageIO.write(image, "png", original.toFile()); ImageIO.write(image, "png", assetFile.toFile())
            PSD2LiveViewModel().use { vm ->
                vm.setStateForTest(vm.state.value.copy(atlasSize = 256, meshOnly = true, generatePhysics = false, exportMoc3 = false))
                DesktopWorkspace(vm, directory.resolve("store")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    if (imported) workspace.importCmo3(writeCmo3Fixture(directory.resolve("original.cmo3"), "Original"), Cmo3ImportMode.NEW, true)
                    else workspace.createArtwork(buildJsonObject {
                        put("width", 32); put("height", 32); putJsonArray("layers") { add(buildJsonObject {
                            put("path", original.toString()); put("name", "Original"); put("role", "objects")
                        }) }
                    })
                    WorkspaceOperations(workspace).use { operations ->
                        val context = WorkspaceOperationContext(MutationAuthor.AGENT)
                        var number = 0
                        suspend fun call(id: String, fields: JsonObject): JsonObject {
                            val definition = operations.registry.definition(id)
                            val expected = workspace.snapshot()
                            val request = buildJsonObject {
                                put("project_id", expected.projectId); put("state", expected.state); put("request_id", "parent-${number++}")
                                fields.forEach { (key, value) -> put(key, value) }
                            }
                            val started = operations.registry.invoke(id, request, context).data
                            if (!definition.jobBacked) return started
                            val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, context).data
                            assertEquals("completed", terminal.text("status"), terminal.toString())
                            val result = terminal.getValue("result").jsonObject
                            validateOperationSchema(result, definition.jobResultSchema!!)
                            assertEquals(workspace.snapshot().state, result.text("state"))
                            assertEquals(started.getValue("id"), operations.registry.invoke(id, request, context).data.getValue("id"))
                            return result
                        }
                        val baseLayer = workspace.snapshot().layers.first { !it.deleted }.id
                        if (!imported) call("canvas_warp", buildJsonObject {
                            put("id", "placement-parent"); put("name", "Placement parent"); put("rows", 2); put("columns", 2)
                            putJsonArray("meshes") { add(workspace.currentPuppet()!!.drawables.first().id.raw) }
                        })
                        val reference = call("asset_prepare_reference", buildJsonObject {
                            put("layer_id", baseLayer); put("piece_id", "decoration"); put("background_color", "#11ccff"); put("target_long_edge", 128)
                            putJsonObject("target_anchors") {
                                putJsonObject("root") { put("x", 0); put("y", 0) }; putJsonObject("tip") { put("x", 16); put("y", 16) }
                            }
                        }).text("id")
                        val asset = call("asset_import_png", buildJsonObject {
                            put("reference_id", reference); put("png_path", assetFile.toString()); put("require_transparency", true)
                        }).text("assetId")
                        val registration = call("asset_register", buildJsonObject { put("asset_id", asset); put("mode", "frame") }).text("id")
                        val before = workspace.snapshot()
                        val originalIds = workspace.currentPuppet()!!.drawables.map { it.id }.toSet()
                        val added = withContext(WorkspaceExecution(before.projectId, before.state, MutationAuthor.USER)) {
                            workspace.addLayer(WorkspaceAddLayerRequest(asset, before.state, "Decoration", "decoration", registrationId = registration,
                                parentDeformerId = if (imported) null else "placement-parent"))
                        }
                        assertEquals("user", workspace.history().nodes.single { it.id == added.historyNodeId }.actor)
                        val movedRegistration = call("asset_register", buildJsonObject {
                            put("asset_id", asset); put("mode", "absolute"); putJsonObject("transform") { put("x", 14); put("y", 14); put("scale_x", 1) }
                        }).text("id")
                        val moved = call("layer_set_placement", buildJsonObject { put("layer_id", "decoration"); put("registration_id", movedRegistration) })
                        assertEquals("agent", workspace.history().nodes.single { it.id == moved.text("history_node_id") }.actor)
                        call("layer_finalize_placement", buildJsonObject { put("layer_id", "decoration") })
                        val expectedIds = workspace.currentPuppet()!!.drawables.map { it.id }.toSet()
                        val frame = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)), output = WorkspaceViewOutputSpec(128))
                        val png = workspace.renderModel(frame).png
                        val archive = directory.resolve("placed.psd2live")
                        workspace.saveProjectAt(archive)
                        Files.delete(original); Files.delete(assetFile)
                        workspace.openProjectAt(archive, true)
                        assertContentEquals(png, workspace.renderModel(frame).png)
                        assertEquals(expectedIds, workspace.currentPuppet()!!.drawables.map { it.id }.toSet())
                        val export = call("project_export_model", buildJsonObject { put("output_directory", directory.resolve("export").toString()) })
                        val cmo3 = export.getValue("files").jsonArray.map { Path.of(it.jsonObject.text("path")) }.single { it.toString().endsWith(".cmo3") }
                        val readback = Cmo3ModelImport.read(Files.readAllBytes(cmo3)).puppet
                        assertEquals(expectedIds, readback.drawables.map { it.id }.toSet())
                        val decoration = workspace.currentPuppet()!!.drawables.single { it.id !in originalIds }
                        assertEquals(decoration.parentDeformerId, readback.drawables.single { it.id == decoration.id }.parentDeformerId)
                        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
                        val expectedGeometry = evaluator.evaluate(workspace.currentPuppet()!!, emptyMap()).worldPositions
                        val actualGeometry = evaluator.evaluate(readback, emptyMap()).worldPositions
                        expectedGeometry.forEach { (id, geometry) ->
                            val replay = actualGeometry.getValue(id)
                            assertEquals(geometry.size, replay.size)
                            assertTrue(geometry.indices.all { kotlin.math.abs(geometry[it] - replay[it]) < 0.05f }, "Export placement changed for ${id.raw}")
                        }
                        val output = Path.of("build/asset-parent-visual")
                        Files.createDirectories(output)
                        val prefix = if (imported) "imported" else "generated"
                        Files.write(output.resolve("$prefix-authored.png"), png)
                        Files.write(output.resolve("$prefix-reopened.png"), workspace.renderModel(frame).png)
                    }
                }
            }
        }
    }
}
