package io.github.psd2live.agent

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopWorkspaceCaptureTest {
    @TempDir lateinit var temp: Path

    @Test fun queriesUseCommittedCaptureAndNeverPublishPendingGuiDrafts() = runBlocking {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..15) for (x in 0..15) image.setRGB(x, y, 0xffce4321.toInt())
        val png = temp.resolve("art.png")
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.setStateForTest(vm.state.value.copy(atlasSize = 256))
                vm.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 32); put("height", 32)
                    putJsonArray("layers") { add(buildJsonObject {
                        put("path", png.toString()); put("name", "Artwork"); put("role", "objects")
                    }) }
                })
                val before = workspace.snapshot()
                val history = workspace.history()
                val puppet = assertNotNull(workspace.currentPuppet())
                val original = vm.state.value
                // A GUI field preview may patch its model before the gesture completes.
                val draftPuppet = puppet.copy(drawables = puppet.drawables.map { it.copy(name = "Uncommitted") })
                val preview = assertNotNull(original.previewModel)
                vm.setStateForTest(original.copy(meshSpacing = original.meshSpacing + 1,
                    previewModel = preview.copy(rig = preview.rig.copy(puppet = draftPuppet)), previewModelDirty = true))
                val pending = vm.state.value
                assertSame(puppet, workspace.currentPuppet())
                assertEquals(before.revisionId, workspace.snapshot().revisionId)
                assertEquals(history, workspace.history())
                assertEquals(original.meshSpacing, workspace.projectSettings().getValue("meshSpacing").jsonPrimitive.int)
                val mesh = workspace.getObject(WorkspaceKeyformTargetRef("mesh", puppet.drawables.first().id.raw))
                assertEquals(puppet.drawables.first().name, mesh.name)
                val layerView = workspace.renderLayer(created.affectedLayerIds.single(), WorkspaceViewBackground.TRANSPARENT,
                    WorkspaceViewOutputSpec(128))
                assertEquals(before.revisionId, layerView.revisionId)
                assertSame(pending.previewModel, vm.state.value.previewModel)
                assertEquals(pending.meshSpacing, vm.state.value.meshSpacing)
                assertEquals(pending.logEntries.map { it.id }, vm.state.value.logEntries.map { it.id })
                assertEquals(history, workspace.history())
                assertEquals(before.revisionId, workspace.snapshot().revisionId)
            }
        }
    }

    @Test fun inspectingUnattachedGuiStateDoesNotInitializeHistoryOrWriteRecoveryData() {
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster,
            true, 0, LayerBounds(0, 0, 2, 2), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(2, 2, ByteArray(16) { if (it % 4 == 3) 255.toByte() else 40 }), null, null, false)
        val source = WorkspaceSourceArt(32, 32, listOf(layer), emptyList())
        val preview = PSD2LivePipeline().buildPreview(source, PipelineConfig(atlasSize = 256))
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(projectId = "pending", analysis = preview.analysis, previewModel = preview))
            val root = temp.resolve("store")
            DesktopWorkspace(vm, root).use { workspace ->
                assertFalse(workspace.snapshot().loaded)
                assertNull(workspace.currentPuppet())
                assertFailsWith<IllegalArgumentException> { workspace.history() }
                assertFalse(Files.exists(root))
                assertEquals("pending", vm.state.value.projectId)
            }
        }
    }

    @Test fun capturedRenderSessionsKeepOldImagesAndStoreResourcesUnderTheirOriginalProject() = runBlocking<Unit> {
        val root = temp.resolve("captured-store")
        fun image(name: String, color: Int): Path {
            val raster = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0..15) for (x in 0..15) raster.setRGB(x, y, color)
            return temp.resolve(name).also { ImageIO.write(raster, "png", it.toFile()) }
        }
        val originalPng = image("original.png", 0xffce4321.toInt()); val replacementPng = image("replacement.png", 0xff215ace.toInt())
        fun artwork(path: Path) = buildJsonObject {
            put("width", 32); put("height", 32); put("discard_unsaved", true)
            putJsonArray("layers") { add(buildJsonObject { put("path", path.toString()); put("name", "Artwork"); put("role", "objects") }) }
        }
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256))
            DesktopWorkspace(vm, root).use { workspace ->
                vm.attachWorkspace(workspace); val created = workspace.createArtwork(artwork(originalPng))
                val before = workspace.snapshot(); val capture = workspace.captureObservation(); val layer = created.affectedLayerIds.single()
                val output = WorkspaceViewOutputSpec(256)
                val request = WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(-16f, -16f, 48f, 48f)), output = output)
                suspend fun views() = listOf(capture.renderLayer(layer, WorkspaceViewBackground.TRANSPARENT, output),
                    capture.renderContext(layer, 0.65f, 1f, WorkspaceViewBackground.TRANSPARENT, output), capture.renderModel(request))
                val expected = views()
                workspace.createArtwork(artwork(replacementPng))
                val current = workspace.snapshot(); val history = workspace.history(); val gui = vm.state.value
                assertNotEquals(before.projectId, current.projectId)
                val actual = views()
                workspace.flushProjectPersistence()
                val store = WorkspaceStore(root)
                actual.zip(expected).forEach { (view, earlier) ->
                    assertEquals(before.revisionId, view.revisionId); assertContentEquals(earlier.png, view.png)
                    assertEquals(view.spatial, store.loadSpatial(before.projectId!!, view.viewId))
                    assertNull(store.loadSpatial(current.projectId!!, view.viewId))
                }
                assertEquals(current.state, workspace.snapshot().state); assertEquals(history, workspace.history())
                assertSame(gui.previewModel, vm.state.value.previewModel); assertEquals(gui.logEntries.map { it.id }, vm.state.value.logEntries.map { it.id })
                val visual = Files.createDirectories(Path.of("build/render-session-visual"))
                actual.forEachIndexed { index, view -> Files.write(visual.resolve("captured-$index.png"), view.png) }
            }
        }
    }
}
