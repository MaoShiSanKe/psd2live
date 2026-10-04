package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceDrawOrderIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun guiContinuousOrderAndResetSharePublicCandidatesAndCommitOnlyOnce() = runBlocking<Unit> {
        for (imported in listOf(false, true)) PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, meshOnly = true, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store-$imported")).use { workspace ->
                vm.attachWorkspace(workspace)
                if (imported) workspace.importCmo3(writeCmo3Fixture(temporary.resolve("input.cmo3"), "Card"), Cmo3ImportMode.NEW)
                else {
                    val path = temporary.resolve("card.png"); val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
                    for (y in 3..20) for (x in 3..20) image.setRGB(x, y, 0xffbb4070.toInt())
                    ImageIO.write(image, "png", path.toFile())
                    workspace.createArtwork(buildJsonObject {
                        put("width", 32); put("height", 32); putJsonArray("layers") { add(buildJsonObject {
                            put("path", path.toString()); put("name", "Card"); put("role", "objects")
                        }) }
                    })
                }
                val before = workspace.snapshot(); val original = workspace.currentPuppet()!!
                val target = original.drawables.first().id; val count = workspace.history().nodes.size
                vm.beginEditorGesture()
                vm.setLayerDrawOrder(target.raw, 10f); vm.setLayerDrawOrder(target.raw, 40f)
                assertEquals(before.state, workspace.snapshot().state); assertEquals(count, workspace.history().nodes.size)
                vm.endEditorGesture(); workspace.awaitEditorDrafts()
                withTimeout(10000) { vm.state.first { !it.editorDraftBusy && !it.canvasEditBusy } }
                assertNull(vm.state.value.errorMessage)
                assertEquals(count + 1, workspace.history().nodes.size); assertEquals("user", workspace.history().nodes.last().actor)
                val gui = workspace.currentPuppet()!!
                assertEquals(40f, CpuDeformationEvaluator().evaluate(gui, emptyMap()).drawOrder.getValue(target))
                workspace.checkoutHistory(before.historyHeadNodeId!!, MutationAuthor.USER)
                WorkspaceOperations(workspace).use { operations ->
                    suspend fun call(order: Float?, id: String) = operations.registry.invoke(WorkspaceDrawOrderEdits.OP,
                        JsonObject(WorkspaceDrawOrderEdits.request(target.raw, order) + buildJsonObject {
                            val current = workspace.snapshot()
                            put("project_id", current.projectId); put("state", current.state); put("request_id", id)
                        }), WorkspaceOperationContext(MutationAuthor.AGENT))
                    call(40f, "set")
                    assertEquals(gui.drawables.map { it.drawOrder }, workspace.currentPuppet()!!.drawables.map { it.drawOrder })
                    val overridden = workspace.snapshot()
                    vm.resetLayerDrawOrder(target.raw)
                    withTimeout(10000) { vm.state.first { !it.canvasEditBusy } }
                    assertNull(vm.state.value.errorMessage)
                    assertEquals("user", workspace.history().nodes.last().actor)
                    val guiReset = workspace.currentPuppet()!!
                    assertEquals(original.drawables.map { it.drawOrder }, guiReset.drawables.map { it.drawOrder })
                    workspace.checkoutHistory(overridden.historyHeadNodeId!!, MutationAuthor.USER)
                    call(null, "reset")
                    assertEquals(guiReset.drawables.map { it.drawOrder }, workspace.currentPuppet()!!.drawables.map { it.drawOrder })
                }
            }
        }
    }
}
