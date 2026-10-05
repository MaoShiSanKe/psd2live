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
import org.umamo.edit.withDrawablesDeleted
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceAuthoredLayerDeletionIntegrationTest {
    @TempDir lateinit var temporary: Path

    private fun compare(expected: PuppetModel, actual: PuppetModel) {
        assertEquals(expected.drawables.map { it.id }.toSet(), actual.drawables.map { it.id }.toSet())
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("LayerAxis") to value)
            val before = evaluator.evaluate(expected, pose); val after = evaluator.evaluate(actual, pose)
            before.worldPositions.forEach { (id, points) ->
                points.indices.forEach { assertEquals(points[it], after.worldPositions.getValue(id)[it], 0.001f) }
                assertEquals(before.opacity.getValue(id), after.opacity.getValue(id), 0.00001f)
            }
        }
    }

    @Test fun guiAndMcpDeleteOriginalAndImportedAuthoredLayersThroughReopenAndExport() = runBlocking<Unit> {
        val art = temporary.resolve("art.png"); val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 4..42) for (x in 4..42) image.setRGB(x, y, 0xff8899aa.toInt())
        ImageIO.write(image, "png", art.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val layers = workspace.createArtwork(buildJsonObject {
                    put("width", 48); put("height", 48); putJsonArray("layers") {
                        for (name in listOf("First", "Second")) add(buildJsonObject { put("path", art.toString()); put("name", name); put("role", "objects") })
                    }
                }).affectedLayerIds
                WorkspaceOperations(workspace).use { operations ->
                    var sequence = 0; val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                    suspend fun call(id: String, fields: JsonObject): JsonObject {
                        val captured = workspace.snapshot(); val definition = operations.registry.definition(id)
                        val request = JsonObject(fields + buildJsonObject {
                            put("project_id", captured.projectId); put("state", captured.state); put("request_id", "authored-delete-" + sequence++)
                        })
                        val started = operations.registry.invoke(id, request, agent).data
                        if (!definition.jobBacked) return started
                        val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, agent).data
                        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                        val result = terminal.getValue("result").jsonObject; validateOperationSchema(result, definition.jobResultSchema!!)
                        assertEquals(started.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id"))
                        return result
                    }
                    val originalMesh = vm.state.value.previewModel!!.rig.layerIdByDrawableId.entries.single { it.value == layers[0] }.key
                    call("parameter_create", buildJsonObject { put("parameter_id", "LayerAxis"); put("name", "Layer axis") })
                    call("canvas_warp", buildJsonObject {
                        put("id", "LayerParent"); put("name", "Parent"); put("rows", 2); put("columns", 2)
                        put("meshes", JsonArray(listOf(JsonPrimitive(originalMesh))))
                    })
                    call("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:" + originalMesh); putJsonObject("key") { put("LayerAxis", 1) }
                        putJsonObject("channels") { put("opacity", 0.4) }
                    }) } })
                    val before = workspace.currentPuppet()!!; val originalNodes = workspace.history().nodes
                    assertTrue(vm.state.value.rigEdits.authoringJournal.none { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
                    val frame = WorkspaceModelViewRequest(parameters = mapOf("LayerAxis" to 1f),
                        frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 48f, 48f)),
                        background = WorkspaceViewBackground.TRANSPARENT, output = WorkspaceViewOutputSpec(256))
                    val originalPng = workspace.renderModel(frame).png
                    vm.deleteLayer(layers[0]); withTimeout(10000) { vm.state.first { !it.workspaceEditBusy } }
                    assertNull(vm.state.value.errorMessage); assertEquals("user", workspace.history().nodes.last().actor)
                    compare(before.withDrawablesDeleted(setOf(DrawableId(originalMesh))), workspace.currentPuppet()!!)
                    val deletedPng = workspace.renderModel(frame).png; assertFalse(originalPng.contentEquals(deletedPng))
                    val archive = temporary.resolve("authored-deleted.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    val deletedNodes = workspace.history().nodes
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertEquals(deletedNodes, workspace.history().nodes); assertContentEquals(deletedPng, workspace.renderModel(frame).png)
                    suspend fun export(directory: String): Pair<Path, PuppetModel> {
                        val file = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve(directory).toString()) })
                            .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                            .single { it.toString().endsWith(".cmo3") }
                        return file to Cmo3ModelImport.read(Files.readAllBytes(file)).puppet
                    }
                    compare(before.withDrawablesDeleted(setOf(DrawableId(originalMesh))), export("deleted-export").second)
                    call("layer_restore", buildJsonObject {})
                    compare(before, workspace.currentPuppet()!!); assertContentEquals(originalPng, workspace.renderModel(frame).png)
                    assertEquals(originalNodes.map { it.copy(isHead = false) },
                        workspace.history().nodes.take(originalNodes.size).map { it.copy(isHead = false) })
                    val restoredExport = export("restored-export"); compare(before, restoredExport.second)
                    call("workspace_apply_edits", buildJsonObject { putJsonArray("edits") {
                        for (layer in layers) add(buildJsonObject { put("operation", "layer_soft_delete"); putJsonObject("request") { put("layer_id", layer) } })
                    } })
                    assertTrue(workspace.currentPuppet()!!.drawables.isEmpty())
                    call("project_save", buildJsonObject {}); call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertTrue(workspace.currentPuppet()!!.drawables.isEmpty()); assertTrue(export("empty-export").second.drawables.isEmpty())
                    vm.restoreAllDeletedLayers(); withTimeout(10000) { vm.state.first { !it.workspaceEditBusy } }
                    assertNull(vm.state.value.errorMessage); compare(before, workspace.currentPuppet()!!)
                    assertContentEquals(originalPng, workspace.renderModel(frame).png)
                    val visuals = Path.of("build/authored-layer-delete-visual").toAbsolutePath(); Files.createDirectories(visuals)
                    Files.write(visuals.resolve("before-delete.png"), originalPng); Files.write(visuals.resolve("deleted-reopened.png"), deletedPng)
                    Files.write(visuals.resolve("restored.png"), workspace.renderModel(frame).png)

                    call("project_import_cmo3", buildJsonObject { put("path", restoredExport.first.toString()); put("mode", "new"); put("discard_unsaved", true) })
                    val imported = workspace.currentPuppet()!!
                    val importedLayer = vm.state.value.previewModel!!.rig.layerIdByDrawableId.getValue(originalMesh)
                    call("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:" + originalMesh); putJsonObject("key") { put("LayerAxis", 1) }
                        putJsonObject("channels") { put("opacity", 0.65) }
                    }) } })
                    val importedAuthored = workspace.currentPuppet()!!; assertNotEquals(imported, importedAuthored)
                    call("layer_soft_delete", buildJsonObject { put("layer_id", importedLayer) })
                    compare(importedAuthored.withDrawablesDeleted(setOf(DrawableId(originalMesh))), workspace.currentPuppet()!!)
                    val importedArchive = temporary.resolve("imported-deleted.psd2live")
                    call("project_save_as", buildJsonObject { put("path", importedArchive.toString()) })
                    call("project_open", buildJsonObject { put("path", importedArchive.toString()) })
                    compare(importedAuthored.withDrawablesDeleted(setOf(DrawableId(originalMesh))), workspace.currentPuppet()!!)
                    call("layer_restore", buildJsonObject {}); compare(importedAuthored, workspace.currentPuppet()!!)
                    compare(importedAuthored, export("imported-restored-export").second)
                }
            }
        }
    }
}
