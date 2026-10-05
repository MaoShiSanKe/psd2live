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

class WorkspaceImagePlacementIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun guiPreviewSaveConfirmAndMcpPlacementCancellationShareDocumentHistoryAndReplay() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val directory = temporary.resolve(if (imported) "imported" else "generated"); Files.createDirectories(directory)
            val file = directory.resolve("image.png")
            val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
            for (y in 1..6) for (x in 1..6) image.setRGB(x, y, 0xffff8844.toInt())
            ImageIO.write(image, "png", file.toFile())
            PSD2LiveViewModel().use { vm ->
                vm.setStateForTest(vm.state.value.copy(atlasSize = 256, meshOnly = true, generatePhysics = false, exportMoc3 = false))
                DesktopWorkspace(vm, directory.resolve("store")).use { workspace ->
                    vm.attachWorkspace(workspace)
                    if (imported) workspace.importCmo3(writeCmo3Fixture(directory.resolve("original.cmo3"), "Original"), Cmo3ImportMode.NEW, true)
                    else workspace.createArtwork(buildJsonObject {
                        put("width", 32); put("height", 32); putJsonArray("layers") { add(buildJsonObject { put("path", file.toString()); put("name", "Original"); put("role", "objects") }) }
                    })
                    val frame = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)), output = WorkspaceViewOutputSpec(128))
                    val originalPng = workspace.renderModel(frame).png
                    val added = vm.importImagesNow(listOf(file.toFile()), null, workspace.snapshot())
                    val id = added.affectedLayerIds.single(); val before = workspace.snapshot(); val history = workspace.history()
                    val session = workspace.beginImagePlacement(before.state, listOf(id))
                    val originalBounds = vm.state.value.analysis!!.source.layers.single { it.id.raw == id }.bounds
                    val preview = WorkspaceImageBounds(id, 18f, 10f, 8f, 16f, "Placed image")
                    session.preview(preview).await()
                    assertEquals(before.state, workspace.snapshot().state); assertEquals(history, workspace.history())
                    assertEquals(originalBounds, vm.state.value.analysis!!.source.layers.single { it.id.raw == id }.bounds)
                    val previewOnly = directory.resolve("preview.psd2live")
                    workspace.saveProjectAt(previewOnly)
                    ProjectRepository().open(previewOnly).use { opened ->
                        assertEquals(originalBounds, opened.history.head().snapshot.source.layers.single { it.id.raw == id }.bounds)
                    }
                    val confirmed = CompletableDeferred<Unit>()
                    vm.relocateImportedLayer(session, id, "Placed image", 18f, 10f, 8f, 16f, onCommitted = { confirmed.complete(Unit) })
                    withTimeout(10000) { confirmed.await() }; workspace.awaitEditorDrafts(); session.dismiss()
                    assertEquals(18, vm.state.value.analysis!!.source.layers.single { it.id.raw == id }.bounds.left)
                    assertEquals("user", workspace.history().nodes.last().actor)
                    val confirmedHead = workspace.history().headNodeId
                    val oldPlacement = workspace.beginImagePlacement(workspace.snapshot().state, listOf(id))
                    val editor = vm.canvasEditor
                    editor.beginLayerPlacement(id, "Placed image", "Root", null, 18f, 10f, 8f, 16f, listOf(id), oldPlacement)
                    oldPlacement.preview(preview.copy(left = 24f)).await()
                    workspace.checkoutHistory(before.historyHeadNodeId!!, MutationAuthor.USER)
                    assertNull(editor.placement)
                    assertFailsWith<IllegalStateException> { oldPlacement.commit(preview, "Old confirmation") }
                    workspace.checkoutHistory(confirmedHead, MutationAuthor.USER)
                    WorkspaceOperations(workspace).use { operations ->
                        val context = WorkspaceOperationContext(MutationAuthor.AGENT); var number = 0
                        suspend fun call(operation: String, fields: JsonObject): JsonObject {
                            val expected = workspace.snapshot()
                            val request = buildJsonObject { put("project_id", expected.projectId); put("state", expected.state); put("request_id", "placement-${number++}"); fields.forEach { (key, value) -> put(key, value) } }
                            val started = operations.registry.invoke(operation, request, context).data
                            val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, context).data
                            assertEquals("completed", terminal.text("status"), terminal.toString())
                            val result = terminal.getValue("result").jsonObject
                            validateOperationSchema(result, operations.registry.definition(operation).jobResultSchema!!)
                            assertEquals(workspace.snapshot().state, result.text("state"))
                            assertEquals(started.getValue("id"), operations.registry.invoke(operation, request, context).data.getValue("id"))
                            return result
                        }
                        call("layer_set_bounds", buildJsonObject { put("layer_id", id); put("left", 10); put("top", 14); put("width", 12); put("height", 10) })
                        assertEquals("agent", workspace.history().nodes.last().actor)
                        val png = workspace.renderModel(frame).png
                        val archive = directory.resolve("placed.psd2live"); workspace.saveProjectAt(archive)
                        Files.delete(file); workspace.openProjectAt(archive, true)
                        assertContentEquals(png, workspace.renderModel(frame).png)
                        val expectedIds = workspace.currentPuppet()!!.drawables.map { it.id }.toSet()
                        val export = call("project_export_model", buildJsonObject { put("output_directory", directory.resolve("export").toString()) })
                        val cmo3 = export.getValue("files").jsonArray.map { Path.of(it.jsonObject.text("path")) }.single { it.toString().endsWith(".cmo3") }
                        val readback = Cmo3ModelImport.read(Files.readAllBytes(cmo3)).puppet
                        assertEquals(expectedIds, readback.drawables.map { it.id }.toSet())
                        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
                        val expectedGeometry = evaluator.evaluate(workspace.currentPuppet()!!, emptyMap()).worldPositions
                        val actualGeometry = evaluator.evaluate(readback, emptyMap()).worldPositions
                        expectedGeometry.forEach { (meshId, geometry) ->
                            val replay = actualGeometry.getValue(meshId)
                            assertEquals(geometry.size, replay.size)
                            assertTrue(geometry.indices.all { kotlin.math.abs(geometry[it] - replay[it]) < 0.05f }, "Export placement changed for ${meshId.raw}")
                        }
                        val output = Path.of("build/image-placement-visual"); Files.createDirectories(output)
                        val prefix = if (imported) "imported" else "generated"
                        Files.write(output.resolve("$prefix-authored.png"), png); Files.write(output.resolve("$prefix-reopened.png"), workspace.renderModel(frame).png)
                        call("layer_cancel_import", buildJsonObject { putJsonArray("layer_ids") { add(id) } })
                        assertContentEquals(originalPng, workspace.renderModel(frame).png)
                        val cancelledArchive = directory.resolve("cancelled.psd2live"); workspace.saveProjectAt(cancelledArchive); workspace.openProjectAt(cancelledArchive, true)
                        assertContentEquals(originalPng, workspace.renderModel(frame).png)
                    }
                }
            }
        }
    }
}
