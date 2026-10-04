package io.github.psd2live.ui.state

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.WeightPaint
import io.github.psd2live.ui.WeightPaintMode
import io.github.psd2live.ui.views.CanvasModeChoice
import io.github.psd2live.ui.views.chooseCanvasMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class CanvasModeMenuTest {
    @TempDir lateinit var temporary: Path

    /** Modes that open business sessions run against a committed application document. */
    private suspend fun workspace(action: suspend (PSD2LiveViewModel, RigPreviewModel) -> Unit) {
        val png = temporary.resolve("body.png")
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 8) for (x in 0 until 8) image.setRGB(x, y, 0xff7799bb.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, generatePhysics = false, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { backend ->
                vm.attachWorkspace(backend)
                backend.createArtwork(buildJsonObject { put("width", 8); put("height", 8); putJsonArray("layers") { add(buildJsonObject {
                    put("path", png.toString()); put("name", "body"); put("role", "topwear")
                }) } })
                action(vm, vm.state.value.previewModel!!)
            }
        }
    }
    @Test fun modeShortcutsCoverEveryChoiceAcrossPresetsWithoutConflicts() {
        for (preset in KeymapPreset.entries) {
            val keymap = Keymap.of(preset)
            CanvasModeChoice.entries.forEachIndexed { index, choice ->
                assertEquals("Alt+${index + 1}", keymap.labelFor(choice.shortcut))
                for (binding in keymap.bindingsFor(choice.shortcut)) {
                    assertEquals(listOf(choice.shortcut), keymap.conflictIndex()[binding])
                }
            }
        }
        val custom = Keymap.DEFAULT.with(ShortcutAction.MODE_PREVIEW, listOf(parseKeyBinding("Alt+9")!!))
        assertEquals("Alt+9", custom.labelFor(CanvasModeChoice.PREVIEW.shortcut))
    }

    @Test fun quickModeBindingsAreSingleKeysWithoutConflictsInEveryPreset() {
        for (preset in KeymapPreset.entries) {
            val keymap = Keymap.of(preset)
            for ((action, key) in listOf(ShortcutAction.TEMPORARY_SELECT to "Z", ShortcutAction.QUICK_PREVIEW to "`")) {
                assertEquals(key, keymap.labelFor(action))
                assertEquals(listOf(action), keymap.conflictIndex()[parseKeyBinding(key)])
            }
        }
    }

    @Test fun temporarySelectionRestoresModeToolAndVerticesDespiteKeyRepeat() {
        PSD2LiveViewModel().use { vm ->
            val model = preview()
            vm.setStateForTest(vm.state.value.copy(previewModel = model))
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            editor.selectLayer(model.rig.layerIdByDrawableId.values.first())
            editor.setHierarchyMode(EditHierarchyMode.SIMULATE)
            editor.activateTool(CanvasTool.WEIGHT_GRADIENT)
            val vertices = mapOf("body" to setOf(0))
            editor.selection = vertices
            assertTrue(editor.beginTemporarySelection())
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertEquals(CanvasTool.SELECT, editor.tool)
            assertTrue(editor.beginTemporarySelection())
            editor.endTemporarySelection()
            assertEquals(EditHierarchyMode.SIMULATE, editor.hierarchyMode)
            assertEquals(CanvasTool.WEIGHT_GRADIENT, editor.tool)
            assertEquals(vertices, editor.selection)
            editor.endTemporarySelection()
            assertEquals(EditHierarchyMode.SIMULATE, editor.hierarchyMode)
        }
    }

    @Test fun temporarySelectionKeepsPaintSessionAndDefersIfTargetIsCleared() = runBlocking<Unit> {
        workspace { vm, model ->
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            editor.selectLayer(model.rig.layerIdByDrawableId.values.first())
            editor.setHierarchyMode(EditHierarchyMode.PAINT)
            val session = assertNotNull(editor.paintSession)
            assertTrue(editor.beginTemporarySelection())
            editor.endTemporarySelection()
            assertSame(session, editor.paintSession)
            assertTrue(editor.beginTemporarySelection())
            editor.objects = emptySet()
            editor.selectLayer(null)
            editor.endTemporarySelection()
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertEquals(EditHierarchyMode.PAINT, editor.deferredMode?.mode)
        }
    }

    @Test fun temporarySelectionSuspendsDeferredModeUntilRelease() {
        PSD2LiveViewModel().use { vm ->
            val model = preview()
            vm.setStateForTest(vm.state.value.copy(previewModel = model))
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            editor.setHierarchyMode(EditHierarchyMode.DEFORM)
            assertNotNull(editor.deferredMode)
            assertTrue(editor.beginTemporarySelection())
            editor.selectLayer(model.rig.layerIdByDrawableId.values.first())
            editor.resolveDeferredMode()
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            editor.endTemporarySelection()
            assertEquals(EditHierarchyMode.DEFORM, editor.hierarchyMode)
            assertNull(editor.deferredMode)
        }
    }

    @Test fun quickPreviewReturnsToTheSameEditingModeAndTool() {
        PSD2LiveViewModel().use { vm ->
            val model = preview()
            vm.setStateForTest(vm.state.value.copy(previewModel = model))
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            editor.selectLayer(model.rig.layerIdByDrawableId.values.first())
            editor.setHierarchyMode(EditHierarchyMode.SIMULATE)
            editor.activateTool(CanvasTool.WEIGHT_GRADIENT)
            editor.toggleQuickPreview()
            assertEquals(CanvasMode.PREVIEW, vm.state.value.activeCanvas.mode)
            editor.toggleQuickPreview()
            assertEquals(CanvasMode.EDIT, vm.state.value.activeCanvas.mode)
            assertEquals(EditHierarchyMode.SIMULATE, editor.hierarchyMode)
            assertEquals(CanvasTool.WEIGHT_GRADIENT, editor.tool)
        }
    }

    private fun preview(): RigPreviewModel {
        val layer = WorkspaceSourceLayer(LayerId("body"), "body", "", SourceLayerKind.Raster, true, 1,
            LayerBounds(0, 0, 8, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(8, 8, ByteArray(8 * 8 * 4) { -1 }), null, null, true)
        return PSD2LivePipeline().buildPreview(WorkspaceSourceArt(8, 8, listOf(layer), emptyList()),
            PipelineConfig(meshOnly = true, atlasSize = 256))
    }

    @Test fun modeMenuListsEveryModeWithPreviewLast() {
        assertEquals(
            listOf(
                EditHierarchyMode.SELECT, EditHierarchyMode.DEFORM, EditHierarchyMode.EDIT,
                EditHierarchyMode.SIMULATE, EditHierarchyMode.SKELETON, EditHierarchyMode.PAINT, null,
            ),
            CanvasModeChoice.entries.map { it.mode },
        )
        assertEquals(CanvasModeChoice.PREVIEW, CanvasModeChoice.of(CanvasMode.PREVIEW, EditHierarchyMode.EDIT))
        assertEquals(CanvasModeChoice.SKELETON, CanvasModeChoice.of(CanvasMode.EDIT, EditHierarchyMode.SKELETON))
    }

    @Test fun previewIsPickedFromTheModeMenuAndAnyOtherRowReturnsToEditing() {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(previewModel = preview()))
            val id = vm.state.value.activeCanvas.id
            val editor = vm.canvasEditorFor(id)
            editor.chooseCanvasMode(CanvasModeChoice.PREVIEW)
            assertEquals(CanvasMode.PREVIEW, vm.state.value.activeCanvas.mode)

            // Nothing is selected, so Deform waits in object mode - but the canvas is back to editing.
            editor.chooseCanvasMode(CanvasModeChoice.DEFORM)
            assertEquals(CanvasMode.EDIT, vm.state.value.activeCanvas.mode)
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertEquals(EditHierarchyMode.DEFORM, editor.deferredMode?.mode)
        }
    }

    @Test fun simulateModeHoldsTheSelectedMeshesAndOffersTheWeightTools() {
        PSD2LiveViewModel().use { vm ->
            val preview = preview()
            vm.setStateForTest(vm.state.value.copy(previewModel = preview))
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            editor.selectLayer(preview.rig.layerIdByDrawableId.values.first())
            editor.setHierarchyMode(EditHierarchyMode.SIMULATE)
            assertEquals(EditHierarchyMode.SIMULATE, editor.hierarchyMode)
            assertEquals(listOf(CanvasTool.WEIGHT_PAINT, CanvasTool.WEIGHT_GRADIENT), editor.palette())
            assertEquals(CanvasTool.WEIGHT_PAINT, editor.tool)
            assertEquals(1, editor.editMeshTargets().size)
            // Simulate's display preset keeps the wires and drops the deformer guides.
            val view = vm.state.value.forCanvas(vm.state.value.activeCanvas.id, mode = CanvasMode.EDIT)
            assertTrue(view.showMesh)
            assertFalse(view.showWarp)

            // The weight tools belong to Simulate, whatever mode arms them.
            editor.setHierarchyMode(EditHierarchyMode.EDIT)
            assertFalse(CanvasTool.WEIGHT_PAINT in editor.palette())
            editor.activateTool(CanvasTool.WEIGHT_GRADIENT)
            assertEquals(EditHierarchyMode.SIMULATE, editor.hierarchyMode)
            assertEquals(CanvasTool.WEIGHT_GRADIENT, editor.tool)
        }
    }

    @Test fun skeletonModeTakesTheSkeletonAndSwitchesBetweenPoseAndEdit() = runBlocking<Unit> {
        workspace { vm, preview ->
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val spec = io.github.psd2live.core.SkeletonAutoBuilder.build(preview.analysis, preview.rig)
            assumeBones(spec)

            editor.setHierarchyMode(EditHierarchyMode.SKELETON)
            withTimeout(10000) { while (vm.state.value.canvasEditBusy || editor.hierarchyMode != EditHierarchyMode.SKELETON) delay(10) }
            assertEquals(EditHierarchyMode.SKELETON, editor.hierarchyMode)
            assertTrue(editor.skeletonSelected)
            assertEquals(listOf(CanvasTool.SKELETON_POSE, CanvasTool.SKELETON_EDIT), editor.palette())

            editor.activateTool(CanvasTool.SKELETON_EDIT)
            assertEquals(CanvasTool.SKELETON_EDIT, editor.tool)
            // The draft opens on its own rest-pose commit, so it appears once that write settles.
            withTimeout(10000) { while (vm.state.value.canvasEditBusy) delay(10) }
            assertNotNull(editor.skeletonDraft)

            // Leaving the mode writes the draft back and gives the skeleton up for the mode gone to.
            editor.setHierarchyMode(EditHierarchyMode.SELECT)
            withTimeout(10000) { while (vm.state.value.canvasEditBusy) delay(10) }
            assertNull(editor.skeletonDraft)
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertNotNull(editor.committedSkeleton)
        }
    }

    private fun assumeBones(spec: io.github.psd2live.core.SkeletonSpec) {
        org.junit.jupiter.api.Assumptions.assumeTrue(spec.bones.isNotEmpty(), "auto skeleton needs tagged layers")
    }

    @Test fun weightModesCombineReachWithTheGroup() {
        val base = floatArrayOf(0.2f, 0.5f, 0.9f)
        val reach = floatArrayOf(1f, 0.5f, 0f)
        assertContentEquals(floatArrayOf(0.7f, 0.75f, 0.9f), WeightPaint.apply(base, reach, WeightPaintMode.ADD, 0.5f), 1e-5f)
        assertContentEquals(floatArrayOf(0f, 0.25f, 0.9f), WeightPaint.apply(base, reach, WeightPaintMode.SUBTRACT, 0.5f), 1e-5f)
        assertContentEquals(floatArrayOf(1f, 0.75f, 0.9f), WeightPaint.apply(base, reach, WeightPaintMode.SET, 1f), 1e-5f)
        assertEquals(WeightPaintMode.SUBTRACT, WeightPaint.effective(WeightPaintMode.ADD, alt = true))
        assertEquals(WeightPaintMode.SMOOTH, WeightPaint.effective(WeightPaintMode.SMOOTH, alt = true))
    }

    @Test fun smoothingEvensAReachedPointTowardItsNeighbours() {
        // A strip 0 - 1 - 2 with a spike in the middle: only the middle is reached.
        val neighbors = listOf(intArrayOf(1), intArrayOf(0, 2), intArrayOf(1))
        val out = WeightPaint.apply(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f), WeightPaintMode.SMOOTH, 1f, neighbors)
        assertEquals(0f, out[0])
        assertEquals(0f, out[2])
        assertTrue(out[1] < 0.1f, "the spike is smoothed away: ${out[1]}")
    }

    @Test fun gradientRunsFullAtTheStartToNothingAtTheEnd() {
        val points = listOf(Offset(-5f, 0f), Offset(0f, 3f), Offset(5f, -2f), Offset(10f, 0f), Offset(20f, 0f))
        val reach = WeightPaint.gradient(points, Offset(0f, 0f), Offset(10f, 0f))
        assertContentEquals(floatArrayOf(1f, 1f, 0.5f, 0f, 0f), reach, 1e-5f)
        assertTrue(WeightPaint.gradient(points, Offset(1f, 1f), Offset(1.2f, 1f)).all { it == 0f })
    }

    private fun assertContentEquals(expected: FloatArray, actual: FloatArray, tolerance: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], tolerance, "at $i")
    }
}
