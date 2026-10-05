package io.github.psd2live.agent

import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.Files
import io.github.psd2live.core.Bounds
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceEditorDraftIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun savingImmediatelyAfterAFieldEditDrainsOneUserCommitAndReopensItsExactHistoryAndRender() = runBlocking {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xff8040b0.toInt())
        val png = temporary.resolve("art.png")
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 16); put("height", 16)
                    putJsonArray("layers") { add(buildJsonObject {
                        put("path", png.toString()); put("name", "Artwork"); put("role", "objects")
                    }) }
                })
                val original = workspace.snapshot()
                vm.beginEditorField("atlas")
                vm.setAtlasSize(512)
                vm.setAtlasSize(1024)
                vm.setAtlasSize(512)
                assertEquals(original.state, workspace.snapshot().state)
                vm.endEditorField("atlas")
                val file = temporary.resolve("saved.psd2live")
                vm.saveProjectNow(file)
                withTimeout(10000) { vm.state.first { !it.editorDraftBusy } }
                assertFalse(vm.state.value.projectDirty)
                assertNull(vm.state.value.errorMessage)
                assertEquals(512, workspace.projectSettings().getValue("atlasSize").jsonPrimitive.int)
                val history = workspace.history()
                assertEquals(2, history.nodes.size)
                assertEquals("user", history.nodes.last().actor)
                val request = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 16f, 16f)),
                    output = WorkspaceViewOutputSpec(128))
                val before = workspace.renderModel(request).png
                vm.openProjectNow(file)
                assertEquals(history, workspace.history())
                assertEquals(512, vm.state.value.atlasSize)
                val after = workspace.renderModel(request).png
                assertContentEquals(before, after)
                assertNotEquals(original.state, workspace.snapshot().state)
                val visual = Files.createDirectories(Path.of("build/editor-draft-visual"))
                Files.write(visual.resolve("before-reopen.png"), before)
                Files.write(visual.resolve("after-reopen.png"), after)
                val exported = workspace.exportModel(workspace.snapshot().state, temporary.resolve("export").toString())
                val cmo3 = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                    .single { it.toString().endsWith(".cmo3") }
                val readBack = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource)
                val puppet = assertNotNull(workspace.currentPuppet())
                assertEquals(puppet.drawables.map { it.id.raw }, readBack.drawables.map { it.id.raw })
                assertEquals(puppet.parameters.map { it.id.raw }, readBack.parameters.map { it.id.raw })
            }
        }
    }
}
