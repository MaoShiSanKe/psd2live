package io.github.psd2live.core

import io.github.psd2live.application.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class CanvasWeightAuthoringTest {
    private fun model(): PuppetModel {
        val id = DrawableId("islands")
        val mesh = DrawableMesh(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 12f, 0f, 22f, 0f, 12f, 10f),
            FloatArray(12), intArrayOf(0, 1, 2, 3, 4, 5))
        val drawable = Drawable(id, "Islands", null, BlendMode.Normal, emptyList(), mesh, null)
        return PuppetModel(emptyList(), emptyList(), emptyList(), listOf(drawable), listOf(OrgChild.Drawable(id)), null)
    }
    private fun paint(model: PuppetModel, connected: Boolean, repeated: Boolean) = WorkspaceCanvasWeightEdits.commands(model,
        WorkspaceDocumentOperation("vertex_group_paint", buildJsonObject { put("edit", buildJsonObject {
            put("action", "brush"); put("kind", "pin"); putJsonArray("targets") { add("islands") }
            put("radius", 50); put("hardness", 0.95); put("strength", 0.6); put("mode", "add"); put("connected_only", connected)
            putJsonArray("points") { repeat(if (repeated) 5 else 1) { add(buildJsonArray { add(1); add(1) }) } }
        }) }))

    @Test fun connectedCoverageSeparatesNearbyIslandsAndRepeatedDabsOnlyApplyOnce() {
        val source = model()
        val connected = RigAuthoringJournal.compile(source, paint(source, true, true)).first.vertexGroups.single()
        assertContentEquals(floatArrayOf(0.6f, 0.6f, 0.6f, 0f, 0f, 0f), connected.weights)
        val ordinary = RigAuthoringJournal.compile(source, paint(source, false, true)).first.vertexGroups.single()
        assertContentEquals(FloatArray(6) { 0.6f }, ordinary.weights)
        val once = RigAuthoringJournal.compile(source, paint(source, false, false)).first.vertexGroups.single()
        assertContentEquals(once.weights, ordinary.weights)
        assertTrue(source.vertexGroups.isEmpty())
        assertFailsWith<WorkspaceValidationException> {
            WorkspaceCanvasWeightEdits.commands(source, WorkspaceDocumentOperation("vertex_group_paint", buildJsonObject { put("edit", buildJsonObject {
                put("action", "invert"); put("kind", "pin"); putJsonArray("targets") { add("islands") }; put("radius", 5)
            }) }))
        }
    }

    @Test fun brushProfilesAndSmoothingMatchTheCanvasMathWithoutUiOwnedExecution() {
        for (shape in io.github.psd2live.ui.BrushShape.entries) for (falloff in io.github.psd2live.ui.BrushFalloff.entries) {
            val actual = computeCanvasBrushWeight(CanvasBrushPoint(8f, 9f), CanvasBrushPoint(1f, 2f), CanvasBrushPoint(4f, 5f),
                11f, 0.3f, CanvasBrushShape.valueOf(shape.name), 37f, 1.7f, CanvasBrushFalloff.valueOf(falloff.name), 193)
            val expected = io.github.psd2live.ui.computeBrushWeight(androidx.compose.ui.geometry.Offset(8f, 9f),
                androidx.compose.ui.geometry.Offset(1f, 2f), androidx.compose.ui.geometry.Offset(4f, 5f), 11f, 0.3f, shape,
                37f, 1.7f, falloff, 193)
            assertEquals(expected, actual, 0.000001f)
        }
        val base = floatArrayOf(0f, 0f, 1f, 0.5f)
        val neighbors = listOf(intArrayOf(1), intArrayOf(0, 2), intArrayOf(1), intArrayOf())
        val smoothed = CanvasWeightAuthoring.apply(base, floatArrayOf(0f, 1f, 0f, 0f), CanvasWeightAuthoring.Mode.SMOOTH, 0.5f, neighbors)
        assertContentEquals(floatArrayOf(0f, 0.46875f, 1f, 0.5f), smoothed)
        assertContentEquals(base, CanvasWeightAuthoring.invert(CanvasWeightAuthoring.invert(base)))
    }

    @Test fun posedBrushUsesParentWorldCoordinatesBeforeGlueAndPublicWeightsMatchThePreview() {
        val parameter = ParameterId("pose")
        val axis = listOf(KeyformAxis(parameter, floatArrayOf(-1f, 0f, 1f)))
        val rotation = Deformer.Rotation(DeformerId("rotation"), "Rotation", null, null, 0f,
            KeyformGrid(axis, listOf(
                KeyformCell(intArrayOf(0), RotationPivotForm(30f, -20f, -35f, 1.2f)),
                KeyformCell(intArrayOf(1), RotationPivotForm(30f, -20f, 0f, 1f)),
                KeyformCell(intArrayOf(2), RotationPivotForm(40f, -10f, 65f, 0.8f)))))
        val original = model().drawables.single()
        val a = original.copy(parentDeformerId = rotation.id, geometryGrid = KeyformGrid(axis,
            listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(12) { -4f })),
                KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(12))),
                KeyformCell(intArrayOf(2), MeshDeltaForm(FloatArray(12) { 4f })))))
        val b = original.copy(id = DrawableId("other"), mesh = original.mesh!!.let { mesh ->
            DrawableMesh(FloatArray(mesh.positions.size) { mesh.positions[it] + if (it % 2 == 0) 100f else 0f }, mesh.uvs, mesh.indices)
        })
        val source = model().copy(parameters = listOf(Parameter(parameter, "Pose", -1f, 1f, 0f)),
            deformers = listOf(rotation), drawables = listOf(a, b),
            glues = listOf(Glue(a.id, b.id, listOf(GluePair(0, 0, 1f, 0f)), intensity = 0.7f, id = "seam")))
        for (value in listOf(-1f, 0f, 0.5f, 1f)) {
            val pose = mapOf("pose" to value)
            val surfaces = CanvasWeightAuthoring.surfaces(source, listOf(a.id), pose, false)
            val unglued = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(source.copy(glues = emptyList()), mapOf(parameter to value))
                .worldPositions.getValue(a.id)
            for (i in surfaces.single().points.indices) {
                assertEquals(unglued[i * 2], surfaces.single().points[i].x, 0.00001f)
                assertEquals(-unglued[i * 2 + 1], surfaces.single().points[i].y, 0.00001f)
            }
            val glued = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(source, mapOf(parameter to value)).worldPositions.getValue(a.id)
            assertTrue(kotlin.math.abs(glued[0] - unglued[0]) > 1f)
            val center = surfaces.single().points[0]
            val reach = canvasBrushWeights(surfaces, center, center,
                CanvasBrushTip(6f, 0.5f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.SMOOTH), false).single()
            val command = WorkspaceCanvasWeightEdits.commands(source, WorkspaceDocumentOperation("vertex_group_paint", buildJsonObject {
                put("edit", buildJsonObject {
                    put("action", "brush"); put("kind", "pin"); putJsonArray("targets") { add(a.id.raw) }
                    put("radius", 6); put("strength", 0.6); put("pose", buildJsonObject { put("pose", value) })
                    putJsonArray("points") { add(buildJsonArray { add(center.x); add(center.y) }) }
                })
            }))
            val actual = RigAuthoringJournal.compile(source, command).first.vertexGroups.single().weights
            assertContentEquals(CanvasWeightAuthoring.apply(FloatArray(reach.size), reach, CanvasWeightAuthoring.Mode.ADD, 0.6f), actual)
        }
    }

    @Test fun interruptedLargeWeightCandidatesStopAtNeutralCheckpoints() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { paint(model(), false, true) }
            assertFailsWith<java.util.concurrent.CancellationException> {
                CanvasWeightAuthoring.apply(FloatArray(10000), FloatArray(10000) { 1f }, CanvasWeightAuthoring.Mode.ADD, 1f)
            }
        } finally { Thread.interrupted() }
    }
}
