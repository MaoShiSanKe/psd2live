package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.*

class RigWarpEditTest {
    private val meshId = DrawableId("mesh")
    private val parentId = DeformerId("parent")
    private val axis = ParameterId("Axis")
    private val bend = ParameterId("Bend")
    private val blendA = ParameterId("BlendA")
    private val blendB = ParameterId("BlendB")
    private val limit = ParameterId("Limit")

    private fun model(quad: Boolean): PuppetModel {
        fun lattice(value: Float) = FloatArray(24) { component ->
            val point = component / 2; val x = (point % 3) / 2f; val y = (point / 3) / 3f
            if (component % 2 == 0) x + value * 0.12f * y * (1f - x) else y + value * 0.08f * x * (1f - y)
        }
        val parent = Deformer.Warp(parentId, "Parent", null, null, 3, 2, quad,
            KeyformGrid(listOf(KeyformAxis(bend, floatArrayOf(-1f, 0f, 1f))), (-1..1).map { value ->
                KeyformCell(intArrayOf(value + 1), WarpLatticeForm(lattice(value.toFloat())))
            }))
        fun deltas(x: Float, y: Float) = floatArrayOf(x, y, x, y, x, y)
        fun binding(id: ParameterId, dx: Float, dy: Float) = BlendShapeBinding(id, floatArrayOf(-1f, 0f, 1f), 1,
            listOf(MeshForm(deltas(0.03f - dx, -dy), opacity = 0.9f), null,
                MeshForm(deltas(0.03f + dx, dy), drawOrder = 520f, opacity = 0.8f)),
            listOf(BlendWeightLimit(limit, listOf(BlendWeightLimitPoint(0f, 0.3f), BlendWeightLimitPoint(1f, 1f)))))
        val mesh = Drawable(meshId, "Mesh", parentId, BlendMode.Normal, emptyList(),
            DrawableMesh(floatArrayOf(0.32f, 0.38f, 0.48f, 0.32f, 0.34f, 0.5f),
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
            KeyformGrid(listOf(KeyformAxis(axis, floatArrayOf(-1f, 0f, 1f))), listOf(
                KeyformCell(intArrayOf(0), MeshDeltaForm(deltas(0.015f, 0f))),
                KeyformCell(intArrayOf(1), MeshDeltaForm(deltas(0.03f, 0f))),
                KeyformCell(intArrayOf(2), MeshDeltaForm(deltas(0.05f, 0f))))),
            blendShapes = listOf(binding(blendA, 0.13f, 0.08f), binding(blendB, 0.14f, 0.06f)))
        return PuppetModel(listOf(Parameter(axis, "Axis", -1f, 1f, 0f), Parameter(bend, "Bend", -1f, 1f, 0f),
            Parameter(blendA, "Blend A", -1f, 1f, 0f, ParameterKind.BLEND_SHAPE),
            Parameter(blendB, "Blend B", -1f, 1f, 0f, ParameterKind.BLEND_SHAPE), Parameter(limit, "Limit", 0f, 1f, 1f)),
            emptyList(), listOf(parent), listOf(mesh), emptyList(), null,
            deformPaths = listOf(DeformPath("path", meshId, listOf(DeformPathPoint(0, 1, 2, 1f, 0f, 0f),
                DeformPathPoint(0, 1, 2, 0f, 1f, 0f)))),
            vertexGroups = listOf(VertexGroup("Pin", meshId, VertexGroupKind.PIN, floatArrayOf(1f, 0.5f, 0f))))
    }

    private fun sameMotion(before: PuppetModel, after: PuppetModel) {
        val evaluator = CpuDeformationEvaluator()
        for (a in listOf(-1f, 0f, 1f)) for (b in listOf(-1f, 0.4f, 1f))
            for (c in listOf(-2f, -0.5f, 0f, 0.6f, 2f)) for (d in listOf(-1f, 0f, 1f)) for (cap in listOf(0f, 1f)) {
                val pose = mapOf(axis to a, bend to b, blendA to c, blendB to d, limit to cap)
                val expected = evaluator.evaluate(before, pose); val actual = evaluator.evaluate(after, pose)
                val positions = expected.worldPositions.getValue(meshId)
                positions.indices.forEach { assertEquals(positions[it], actual.worldPositions.getValue(meshId)[it], 0.00001f, "$pose/$it") }
                assertEquals(expected.opacity.getValue(meshId), actual.opacity.getValue(meshId), 0.00001f)
                assertEquals(expected.drawOrder.getValue(meshId), actual.drawOrder.getValue(meshId), 0.00001f)
            }
    }

    @Test fun fittedWarpPreservesStackedBlendMotionDefaultGridReferenceAndParentInterpolation() {
        for (quad in listOf(false, true)) {
            val before = model(quad)
            val original = before.drawables.single(); val saved = original.blendShapes.map { it.forms.map { form -> form?.positionDeltas?.copyOf() } }
            val after = RigWarpEdit("child", "Child", parentId.raw, listOf(meshId.raw), 16, 16, true).applyTo(before)
            sameMotion(before, after)
            val mesh = after.drawables.single()
            assertContentEquals(original.mesh!!.uvs, mesh.mesh!!.uvs); assertContentEquals(original.mesh.indices, mesh.mesh.indices)
            assertEquals(before.deformPaths, after.deformPaths)
            assertEquals(before.vertexGroups, after.vertexGroups)
            original.blendShapes.forEachIndexed { index, binding ->
                assertContentEquals(binding.keys, mesh.blendShapes[index].keys)
                assertEquals(binding.limits, mesh.blendShapes[index].limits)
                binding.forms.forEachIndexed { key, form ->
                    if (form != null) { assertContentEquals(saved[index][key], form.positionDeltas); assertNotSame(form.positionDeltas, mesh.blendShapes[index].forms[key]!!.positionDeltas) }
                }
            }
            val child = after.deformers.last() as Deformer.Warp
            assertTrue(child.rows < 18 || child.columns < 12)
            assertEquals(quad, child.isQuadTransform)
        }
    }

    @Test fun nonFittedWarpKeepsLocalGeometryAndBlendFormsByIdentity() {
        val before = model(false)
        val after = RigWarpEdit("child", "Child", parentId.raw, listOf(meshId.raw), 4, 4).applyTo(before)
        assertSame(before.drawables.single().mesh, after.drawables.single().mesh)
        assertSame(before.drawables.single().geometryGrid, after.drawables.single().geometryGrid)
        assertSame(before.drawables.single().blendShapes, after.drawables.single().blendShapes)
        sameMotion(before, after)
    }

    @Test fun fitRejectsCombinedBlendEnvelopeOutsideTheParentWithoutChangingInput() {
        val original = model(false)
        val mesh = original.drawables.single()
        val excessive = mesh.blendShapes.first().copy(forms = listOf(null, null, MeshForm(FloatArray(6) { 0.8f })))
        val before = original.copy(drawables = listOf(mesh.copy(blendShapes = listOf(excessive, excessive))))
        assertFailsWith<IllegalArgumentException> { RigWarpEdit("child", "Child", parentId.raw, listOf(meshId.raw), fitLocal = true).applyTo(before) }
        assertEquals(1, before.deformers.size)
        assertSame(mesh.mesh, before.drawables.single().mesh)
    }

    @Test fun invalidReferencesIdentityCollisionsAndOversizedAlignedLatticesAreRejected() {
        val before = model(false)
        assertFailsWith<IllegalArgumentException> { RigWarpEdit(meshId.raw, "Child", parentId.raw, listOf(meshId.raw)).applyTo(before) }
        assertFails { RigWarpEdit("child", "Child", parentId.raw, listOf("missing")).applyTo(before) }
        assertFailsWith<IllegalArgumentException> { RigWarpEdit("child", "Child", parentId.raw, listOf(meshId.raw, meshId.raw)) }
        val parent = before.deformers.single() as Deformer.Warp
        assertFailsWith<IllegalArgumentException> { RigWarpEdit("child", "Child", parentId.raw, listOf(meshId.raw), 32, 32)
            .applyTo(before.copy(deformers = listOf(parent.copy(rows = 1, columns = 3)))) }
    }

    @Test fun legacyStaticWarpAndStructureRecordsStillReplayInTheirOriginalOrder() {
        val before = model(false)
        val edit = RigWarpEdit("legacy", "Legacy", parentId.raw, listOf(meshId.raw))
        for (overlay in listOf(RigEditOverlay(warpEdits = listOf(edit)), RigEditOverlay(warpEdits = listOf(edit),
            structureEdits = listOf(kotlinx.serialization.json.JsonObject(edit.toJson() +
                ("action" to kotlinx.serialization.json.JsonPrimitive("create_warp"))))))) {
            val replay = overlay.applyTo(before)
            assertEquals(2, replay.deformers.size)
            assertEquals("legacy", replay.drawables.single().parentDeformerId!!.raw)
            sameMotion(before, replay)
        }
    }
}
