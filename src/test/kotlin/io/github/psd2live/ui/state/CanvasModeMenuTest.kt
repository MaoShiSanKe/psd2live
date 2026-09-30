package io.github.psd2live.ui.state

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.WeightPaint
import io.github.psd2live.ui.WeightPaintMode
import io.github.psd2live.ui.views.CanvasModeChoice
import io.github.psd2live.ui.views.chooseCanvasMode
import org.umamo.format.art.*
import kotlin.test.*

class CanvasModeMenuTest {
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

    @Test fun skeletonModeTakesTheSkeletonAndSwitchesBetweenPoseAndEdit() {
        PSD2LiveViewModel().use { vm ->
            val preview = preview()
            vm.setStateForTest(vm.state.value.copy(previewModel = preview))
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val spec = io.github.psd2live.core.SkeletonAutoBuilder.build(preview.analysis, preview.rig)
            assumeBones(spec)

            editor.setHierarchyMode(EditHierarchyMode.SKELETON)
            assertEquals(EditHierarchyMode.SKELETON, editor.hierarchyMode)
            assertTrue(editor.skeletonSelected)
            assertEquals(listOf(CanvasTool.SKELETON_POSE, CanvasTool.SKELETON_EDIT), editor.palette())

            editor.activateTool(CanvasTool.SKELETON_EDIT)
            assertEquals(CanvasTool.SKELETON_EDIT, editor.tool)
            assertNotNull(editor.skeletonDraft)

            // Leaving the mode writes the draft back and gives the skeleton up for the mode gone to.
            editor.setHierarchyMode(EditHierarchyMode.SELECT)
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
