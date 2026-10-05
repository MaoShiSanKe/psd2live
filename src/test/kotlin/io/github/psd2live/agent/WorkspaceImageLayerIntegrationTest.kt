package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceImageLayerIntegrationTest {
    @TempDir lateinit var temporary: Path
    @Test fun guiAndPublicImageImportShareHistoryAndPublicRetryThenReopenAndExportWithoutOriginalFiles() = runBlocking<Unit> {
        val original = temporary.resolve("original.png")
        val additional = temporary.resolve("addition.png")
        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..23) for (x in 3..23) image.setRGB(x, y, 0xff8899aa.toInt())
        ImageIO.write(image, "png", original.toFile())
        val addition = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..13) for (x in 2..13) addition.setRGB(x, y, 0xffff8844.toInt())
        ImageIO.write(addition, "png", additional.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 48); put("height", 48); putJsonArray("layers") { add(buildJsonObject {
                        put("path", original.toString()); put("name", "Original"); put("role", "objects")
                    }) }
                })
                val first = vm.importImagesNow(listOf(additional.toFile()), null, workspace.snapshot())
                assertEquals(1, first.affectedLayerIds.size)
                assertEquals("user", workspace.history().nodes.single { it.id == first.historyNodeId }.actor)
                WorkspaceOperations(workspace).use { operations ->
                    val context = WorkspaceOperationContext(MutationAuthor.AGENT)
                    val expected = workspace.snapshot()
                    val request = buildJsonObject {
                        put("project_id", expected.projectId); put("state", expected.state); put("request_id", "image-import")
                        put("paths", buildJsonArray { add(additional.toString()) })
                    }
                    val beforeCount = workspace.history().nodes.size
                    val started = operations.registry.invoke("layer_import_images", request, context).data
                    val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", started.getValue("id")) }, context).data
                    assertEquals("completed", terminal.text("status"), terminal.toString())
                    val result = terminal.getValue("result").jsonObject
                    validateOperationSchema(result, operations.registry.definition("layer_import_images").jobResultSchema!!)
                    assertEquals(workspace.snapshot().state, result.text("state"))
                    assertEquals(beforeCount + 1, workspace.history().nodes.size)
                    assertEquals(started.getValue("id"), operations.registry.invoke("layer_import_images", request, context).data.getValue("id"))
                    assertEquals(beforeCount + 1, workspace.history().nodes.size)
                    assertEquals("agent", workspace.history().nodes.single { it.id == result.text("history_node_id") }.actor)
                    val frame = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 48f, 48f)),
                        output = WorkspaceViewOutputSpec(128))
                    val png = workspace.renderModel(frame).png
                    val archive = temporary.resolve("image-layers.psd2live")
                    workspace.saveProjectAt(archive)
                    Files.delete(original); Files.delete(additional)
                    workspace.openProjectAt(archive, true)
                    assertEquals(3, workspace.currentPuppet()!!.drawables.size)
                    assertContentEquals(png, workspace.renderModel(frame).png)
                    val opened = workspace.snapshot()
                    val export = operations.registry.invoke("project_export_model", buildJsonObject {
                        put("project_id", opened.projectId); put("state", opened.state); put("request_id", "image-export")
                        put("output_directory", temporary.resolve("export").toString())
                    }, context).data
                    val exported = operations.registry.invoke("job_wait", buildJsonObject { put("id", export.getValue("id")) }, context).data
                    assertEquals("completed", exported.text("status"), exported.toString())
                    val cmo3 = exported.getValue("result").jsonObject.getValue("files").jsonArray.map {
                        Path.of(it.jsonObject.text("path"))
                    }.single { it.toString().endsWith(".cmo3") }
                    assertEquals(workspace.currentPuppet()!!.drawables.map { it.id }.toSet(), Cmo3ModelImport.read(Files.readAllBytes(cmo3)).puppet.drawables.map { it.id }.toSet())
                    val output = Path.of("build/image-layer-visual"); Files.createDirectories(output)
                    Files.write(output.resolve("authored.png"), png)
                    Files.write(output.resolve("reopened.png"), workspace.renderModel(frame).png)
                    // New artwork in an imported model must survive both archive and format replay.
                    workspace.importCmo3(cmo3, Cmo3ImportMode.NEW, true)
                    ImageIO.write(addition, "png", additional.toFile())
                    val importedAddition = vm.importImagesNow(listOf(additional.toFile()), null, workspace.snapshot())
                    val expectedIds = workspace.currentPuppet()!!.drawables.map { it.id }.toSet()
                    assertEquals(4, expectedIds.size)
                    assertEquals(1, importedAddition.affectedLayerIds.size)
                    val importedPng = workspace.renderModel(frame).png
                    workspace.saveProjectAt(temporary.resolve("imported-images.psd2live"))
                    Files.delete(additional)
                    workspace.openProjectAt(temporary.resolve("imported-images.psd2live"), true)
                    assertEquals(expectedIds, workspace.currentPuppet()!!.drawables.map { it.id }.toSet())
                    assertContentEquals(importedPng, workspace.renderModel(frame).png)
                    val final = workspace.snapshot()
                    val importedExport = operations.registry.invoke("project_export_model", buildJsonObject {
                        put("project_id", final.projectId); put("state", final.state); put("request_id", "imported-image-export")
                        put("output_directory", temporary.resolve("imported-export").toString())
                    }, context).data
                    val importedTerminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", importedExport.getValue("id")) }, context).data
                    assertEquals("completed", importedTerminal.text("status"), importedTerminal.toString())
                    val importedCmo3 = importedTerminal.getValue("result").jsonObject.getValue("files").jsonArray.map {
                        Path.of(it.jsonObject.text("path"))
                    }.single { it.toString().endsWith(".cmo3") }
                    assertEquals(expectedIds, Cmo3ModelImport.read(Files.readAllBytes(importedCmo3)).puppet.drawables.map { it.id }.toSet())
                    Files.write(output.resolve("imported-authored.png"), importedPng)
                    Files.write(output.resolve("imported-reopened.png"), workspace.renderModel(frame).png)
                }
            }
        }
    }
}
