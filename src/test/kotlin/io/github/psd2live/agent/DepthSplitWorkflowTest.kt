package io.github.psd2live.agent

import io.github.psd2live.core.DepthSplit
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class DepthSplitWorkflowTest {
    @TempDir lateinit var temp: Path

    @Test fun createSelectsTheFrontEraserAndHistoryRestoresTheOriginal() =
        exercise(listOf("neckwear", "neck"), quick = false)

    @Test fun twoSelectedMeshesGenerateDirectlyAndOnlyCopyTheContextTarget() =
        exercise(listOf("neckwear", "neck"), quick = true)

    @Test fun multipleSelectedMeshesStayBetweenOnePairOfSlices() =
        exercise(listOf("neckwear", "neck", "face"), quick = true)

    private fun exercise(names: List<String>, quick: Boolean) = runBlocking {
        val png = temp.resolve("art.png")
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..29) for (x in 2..29) image.setRGB(x, y, 0xff507080.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            ViewModelAgentWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachAgentWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 32); put("height", 32)
                    putJsonArray("layers") {
                        for (name in names) add(buildJsonObject {
                            put("path", png.toString()); put("name", name); put("role", "objects")
                        })
                    }
                })
                val before = assertNotNull(vm.state.value.previewModel)
                val sourceId = before.rig.layerIdByDrawableId.entries.single { it.value == created.affectedLayerIds.first() }.key
                val middleIds = created.affectedLayerIds.drop(1).map { layerId ->
                    before.rig.layerIdByDrawableId.entries.single { it.value == layerId }.key
                }
                vm.selectLayer(created.affectedLayerIds.first())
                if (quick) created.affectedLayerIds.drop(1).forEach { vm.selectLayer(it, additive = true) }
                assertEquals(if (quick) middleIds.toSet() else emptySet(), vm.depthSplitMiddleIds(sourceId).toSet())
                vm.requestDepthSplit(sourceId)
                if (quick) assertNull(vm.pendingDepthSplit) else {
                    assertNotNull(vm.pendingDepthSplit)
                    vm.confirmDepthSplit(middleIds.single())
                }
                withTimeout(15000) {
                    while (vm.state.value.canvasEditBusy || vm.state.value.previewModel === before) {
                        vm.state.value.errorMessage?.let { fail(it) }
                        delay(20)
                    }
                }
                val split = assertNotNull(vm.state.value.previewModel)
                val frontId = assertNotNull(vm.state.value.selectedLayerId)
                assertTrue(DepthSplit.isFrontLayer(split, frontId))
                assertEquals(setOf(frontId), vm.state.value.selectedLayerIds)
                assertEquals(EditHierarchyMode.PAINT, vm.canvasEditor.hierarchyMode)
                assertEquals(CanvasTool.PAINT_ERASER, vm.canvasEditor.tool)
                assertEquals(frontId, vm.canvasEditor.paintSession?.layerId)
                assertEquals(names.size + 1, split.rig.puppet.drawables.size)
                assertEquals(1, split.rig.puppet.glues.size)
                assertEquals(1, split.config.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.contentOrNull == DepthSplit.OP })
                val back = split.rig.puppet.drawables.single { it.id.raw == sourceId }
                val front = split.rig.puppet.drawables.single { split.rig.layerIdByDrawableId[it.id.raw] == frontId }
                assertEquals(sourceId, split.rig.puppet.glues.single().meshA.raw)
                middleIds.forEach { id ->
                    val middle = split.rig.puppet.drawables.single { it.id.raw == id }
                    assertTrue(back.drawOrder < middle.drawOrder && middle.drawOrder < front.drawOrder)
                    assertEquals(before.rig.puppet.drawables.single { it.id.raw == id }.name, middle.name)
                    assertContentEquals(before.rig.puppet.drawables.single { it.id.raw == id }.mesh!!.positions, middle.mesh!!.positions)
                }
                val splitHead = assertNotNull(vm.state.value.historySnapshot).headNodeId
                workspace.checkoutHistory(created.historyNodeId, MutationAuthor.USER)
                assertEquals(names.size, vm.state.value.previewModel!!.rig.puppet.drawables.size)
                assertTrue(vm.state.value.previewModel!!.rig.puppet.glues.isEmpty())
                assertNull(vm.canvasEditor.paintSession)
                workspace.checkoutHistory(splitHead, MutationAuthor.USER)
                assertEquals(names.size + 1, vm.state.value.previewModel!!.rig.puppet.drawables.size)
                assertEquals(1, vm.state.value.previewModel!!.rig.puppet.glues.size)
            }
        }
    }
}
