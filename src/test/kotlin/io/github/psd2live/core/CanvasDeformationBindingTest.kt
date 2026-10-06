package io.github.psd2live.core

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.canvasGeometryCommand
import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.runtime.model.*
import kotlin.test.*

class CanvasDeformationBindingTest {
    private val fresh = ParameterId("NewParameter")
    private val old = ParameterId("OldParameter")
    private fun model() = PuppetModel(
        listOf(Parameter(fresh, "New", -1f, 1f, 0f, keys = listOf(-1f, 0f, 1f)), Parameter(old, "Old", -1f, 1f, 0f)),
        emptyList(), listOf(Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f))))))),
        listOf(Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(),
            DrawableMesh(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)), null)),
        emptyList(), null,
    )

    @Test fun panelSelectionIncludesUnchangedValuesAndLinkedInputs() {
        PSD2LiveViewModel().use { vm ->
            vm.setParameterValueFromPanel(fresh, 0f)
            assertEquals(fresh.raw, vm.canvasEditor.parameter)
            vm.setParameterValuesFromPanel(linkedMapOf(old to 1f, fresh to 1f))
            assertEquals(old.raw, vm.canvasEditor.parameter)
            // Explicit picker selection still overrides the panel selection.
            vm.canvasEditor.parameter = fresh.raw
            assertEquals(fresh.raw, vm.canvasEditor.parameter)
        }
    }

    @Test fun destinationKeepsExistingAxesAndBothSelectedParameters() {
        val source = model()
        val pose = mapOf(old.raw to -1f, fresh.raw to 1f)
        assertEquals(pose, canvasDeformationCoordinate(source, listOf(KeyformAxis(old, floatArrayOf(-1f, 0f, 1f))), pose, listOf(fresh.raw)))
        assertEquals(pose, canvasDeformationCoordinate(source, emptyList(), pose, listOf(old.raw, fresh.raw)))
        assertTrue(canvasDeformationCoordinate(source, emptyList(), pose, emptyList()).isEmpty())
    }

    @Test fun draggingAndBrushingBindFreshParameterAndPreserveNeutralPose() {
        val source = model()
        val pose = mapOf(fresh.raw to 1f)
        for ((kind, id) in listOf("mesh" to "mesh", "warp" to "warp")) {
            val geometry = RigGeometryTools.geometry(source, kind, id, pose)
            val key = canvasDeformationCoordinate(source, geometry.axes, pose, listOf(fresh.raw))
            val moved = geometry.points.copyOf().also { it[0] += 20f }
            val dragged = CanvasEdits.apply(source, canvasGeometryCommand(EditHierarchyMode.DEFORM, kind, id, key, moved))
            val request = CanvasDeformStroke.Request(CanvasDeformStroke.Action.BRUSH, CanvasDeformStroke.Mode.DEFORM,
                listOf(CanvasDeformStroke.Target(kind, id, key, setOf(0))), pose,
                CanvasBrushTip(500f, 0.5f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.CONSTANT), 1f, false)
            val stroke = CanvasDeformStroke.begin(source, request, CanvasDeformStroke.Sample(CanvasBrushPoint(0f, 0f)))
            val brushed = stroke.step(CanvasDeformStroke.Sample(CanvasBrushPoint(20f, 0f)))
            val replayed = CanvasEdits.apply(source, stroke.commands.single().let { it as kotlinx.serialization.json.JsonObject })
            for (result in listOf(dragged, brushed, replayed)) {
                assertEquals(fresh, RigGeometryTools.geometry(result, kind, id, pose).axes.single().parameterId)
                assertContentEquals(moved, RigGeometryTools.geometry(result, kind, id, pose).points)
                assertContentEquals(geometry.points, RigGeometryTools.geometry(result, kind, id, mapOf(fresh.raw to 0f)).points)
                assertContentEquals(source.drawables.single().mesh!!.uvs, result.drawables.single().mesh!!.uvs)
            }
        }
    }

    @Test fun newBindingPreservesPreviouslyAuthoredParameterShapes() {
        val base = model()
        val oldPose = mapOf(old.raw to 1f)
        val original = RigGeometryTools.geometry(base, "mesh", "mesh", oldPose).points
        val oldPoints = original.copyOf().also { it[2] += 10f }
        val source = CanvasEdits.apply(base, canvasGeometryCommand(EditHierarchyMode.DEFORM, "mesh", "mesh", oldPose, oldPoints))
        val pose = oldPose + (fresh.raw to 1f)
        val geometry = RigGeometryTools.geometry(source, "mesh", "mesh", pose)
        val key = canvasDeformationCoordinate(source, geometry.axes, pose, listOf(fresh.raw))
        val moved = geometry.points.copyOf().also { it[0] += 20f }
        val result = CanvasEdits.apply(source, canvasGeometryCommand(EditHierarchyMode.DEFORM, "mesh", "mesh", key, moved))
        assertEquals(listOf(old, fresh), result.drawables.single().geometryGrid!!.axes.map { it.parameterId })
        assertContentEquals(moved, RigGeometryTools.geometry(result, "mesh", "mesh", pose).points)
        assertContentEquals(oldPoints, RigGeometryTools.geometry(result, "mesh", "mesh", oldPose).points)
        assertContentEquals(original, RigGeometryTools.geometry(result, "mesh", "mesh", emptyMap()).points)
    }
}
