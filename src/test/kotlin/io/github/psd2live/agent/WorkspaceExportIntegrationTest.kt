package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.ui.state.*
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.psd.PsdReader
import org.umamo.interop.cmo3.Cmo3Import
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceExportIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun guiAndMcpExportTheSameCommittedRigAndSourceWithoutAddingHistory() = runBlocking<Unit> {
        val png = temporary.resolve("art.png")
        val pixels = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..20) for (x in 4..19) pixels.setRGB(x, y, 0xff785ca2.toInt())
        ImageIO.write(pixels, "png", png.toFile())
        PSD2LiveViewModel().use { vm -> DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
            vm.attachWorkspace(workspace)
            vm.setStateForTest(vm.state.value.copy(exportMoc3 = false))
            workspace.createArtwork(buildJsonObject {
                put("width", 32); put("height", 32)
                putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "Artwork"); put("role", "objects") }) }
            })
            // Complete a field immediately before exporting: the action follows only its own queue.
            vm.beginEditorField("atlas"); vm.setAtlasSize(256); vm.endEditorField("atlas")
            vm.generateRig(temporary.resolve("gui-model").toString())
            withTimeout(60000) { vm.state.first { it.successExportMessage != null && !it.isGenerating } }
            assertNull(vm.state.value.errorMessage)
            val captured = workspace.snapshot(); val history = workspace.history()
            assertEquals(256, workspace.projectSettings().getValue("atlasSize").jsonPrimitive.int)
            val guiPsd = temporary.resolve("gui.psd")
            vm.exportPsd(guiPsd, 1, false)
            withTimeout(60000) { vm.state.first { Files.exists(guiPsd) && !it.isExportingPsd } }
            assertNull(vm.state.value.errorMessage)
            WorkspaceOperations(workspace).use { operations ->
                val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
                suspend fun export(id: String, fields: JsonObject): JsonObject {
                    val request = JsonObject(fields + buildJsonObject {
                        put("request_id", id); put("project_id", captured.projectId); put("state", captured.state)
                    })
                    val job = operations.registry.invoke(id, request, agent).data
                    assertEquals(job.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id"))
                    val terminal = withTimeout(60000) {
                        var output = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
                        while (!output.getValue("terminal").jsonPrimitive.boolean)
                            output = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
                        output
                    }
                    assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                    return terminal.getValue("result").jsonObject
                }
                val model = export("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("mcp-model").toString()) })
                val mcpCmo = model.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                    .single { it.toString().endsWith(".cmo3") }
                val guiCmo = Files.list(temporary.resolve("gui-model")).use { files -> files.filter { it.toString().endsWith(".cmo3") }.findFirst().orElseThrow() }
                fun puppet(path: Path) = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(path)).root as CModelSource)
                val gui = puppet(guiCmo); val mcp = puppet(mcpCmo)
                assertEquals(gui.parameters, mcp.parameters); assertEquals(gui.drawables.map { it.id }, mcp.drawables.map { it.id })
                gui.drawables.zip(mcp.drawables).forEach { (a, b) -> assertContentEquals(a.mesh!!.positions, b.mesh!!.positions) }
                val mcpPsd = temporary.resolve("mcp.psd")
                export("project_export_psd", buildJsonObject { put("path", mcpPsd.toString()); put("include_generated_layers", false) })
                assertContentEquals(Files.readAllBytes(guiPsd), Files.readAllBytes(mcpPsd))
                assertContentEquals(vm.state.value.analysis!!.source.layers.single().raster.rgba,
                    PsdReader.read(Files.readAllBytes(mcpPsd)).layers.single().raster.rgba)
            }
            assertEquals(captured.state, workspace.snapshot().state); assertEquals(history, workspace.history())
        } }
    }
}
