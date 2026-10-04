package io.github.psd2live.core

import io.github.psd2live.application.WorkspaceCanvasDeformEdits
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.model.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class CanvasDeformStrokeTest {
    private val shape = ParameterId("Shape")
    private val blend = ParameterId("Blend")
    private val otherBlend = ParameterId("OtherBlend")
    private val limit = ParameterId("Limit")
    private fun mesh(id: String, x: Float = 0f) = Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(),
        DrawableMesh(floatArrayOf(x, 0f, x + 100f, 0f, x, 100f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)), null)
    private fun model() = PuppetModel(emptyList(), emptyList(), emptyList(), listOf(mesh("a")), emptyList(), null)
    private fun request(action: CanvasDeformStroke.Action = CanvasDeformStroke.Action.BRUSH,
                        mode: CanvasDeformStroke.Mode = CanvasDeformStroke.Mode.DEFORM,
                        targets: List<CanvasDeformStroke.Target> = listOf(CanvasDeformStroke.Target("mesh", "a")),
                        pose: Map<String, Float> = emptyMap(), connected: Boolean = false, shrink: Boolean = false) =
        CanvasDeformStroke.Request(action, mode, targets, pose,
            CanvasBrushTip(500f, 0.5f, CanvasBrushShape.CIRCLE, 0f, 1f, CanvasBrushFalloff.CONSTANT), 1f, connected, shrink)
    private fun sample(x: Float, y: Float, smooth: Boolean = false, ctrl: Boolean = false) =
        CanvasDeformStroke.Sample(CanvasBrushPoint(x, y), smooth, ctrl)
    private fun sameCoordinates(a: FloatArray, b: FloatArray, tolerance: Float = 0.0001f) {
        assertEquals(a.size, b.size); a.indices.forEach { assertEquals(a[it], b[it], tolerance, "coordinate $it") }
    }

    @Test fun capturedBrushMeasuresTotalCanvasTravelAndNeverMutatesItsSourceOrCallerSelection() {
        val source = model(); val selected = mutableSetOf(0)
        val stroke = CanvasDeformStroke.begin(source, request(targets = listOf(CanvasDeformStroke.Target("mesh", "a", vertices = selected))), sample(0f, 0f))
        selected.clear()
        stroke.step(sample(2f, 3f)); val result = stroke.step(sample(7f, 4f))
        assertContentEquals(floatArrayOf(7f, 4f, 100f, 0f, 0f, 100f), result.drawables.single().mesh!!.positions)
        assertContentEquals(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f), source.drawables.single().mesh!!.positions)
        assertContentEquals(source.drawables.single().mesh!!.uvs, result.drawables.single().mesh!!.uvs)
        val encoded = WorkspaceCanvasDeformEdits.operation(stroke)
        val materialized = WorkspaceCanvasDeformEdits.commands(source, encoded)
        sameCoordinates(result.drawables.single().mesh!!.positions, RigAuthoringJournal.compile(source, materialized).first.drawables.single().mesh!!.positions)
        assertEquals(1, materialized.size)
        stroke.cancel(); assertSame(source, stroke.preview); assertTrue(stroke.commands.isEmpty())
        assertFailsWith<IllegalStateException> { stroke.step(sample(8f, 4f)) }
    }

    @Test fun smoothingAccumulatesAndShiftBrushUsesTheSameNeighborAverages() {
        val source = model()
        val smooth = CanvasDeformStroke.begin(source, request(CanvasDeformStroke.Action.SMOOTH), sample(0f, 0f))
        val first = smooth.step(sample(0f, 1f))
        assertContentEquals(floatArrayOf(50f, 50f, 0f, 50f, 50f, 0f), first.drawables.single().mesh!!.positions)
        val second = smooth.step(sample(0f, 2f))
        assertContentEquals(floatArrayOf(25f, 25f, 50f, 25f, 25f, 50f), second.drawables.single().mesh!!.positions)
        val shifted = CanvasDeformStroke.begin(source, request(), sample(0f, 0f))
        shifted.step(sample(0f, 1f, smooth = true)); val shiftedNext = shifted.step(sample(0f, 2f, smooth = true))
        assertContentEquals(second.drawables.single().mesh!!.positions, shiftedNext.drawables.single().mesh!!.positions)
        assertEquals(1, smooth.commands.size)
    }

    @Test fun inflateAndShrinkUseCanvasSegmentDistanceWithoutChangingUntouchedVertices() {
        val source = model().copy(drawables = listOf(mesh("a").copy(mesh = DrawableMesh(
            floatArrayOf(5f, 5f, 100f, 0f, 0f, 100f), model().drawables.single().mesh!!.uvs, intArrayOf(0, 1, 2)))))
        val target = listOf(CanvasDeformStroke.Target("mesh", "a", vertices = setOf(0)))
        val expanded = CanvasDeformStroke.begin(source, request(CanvasDeformStroke.Action.INFLATE, targets = target), sample(0f, 0f)).step(sample(0f, 4f))
        val shrunk = CanvasDeformStroke.begin(source, request(CanvasDeformStroke.Action.INFLATE, targets = target, shrink = true), sample(0f, 0f)).step(sample(0f, 4f))
        val a = expanded.drawables.single().mesh!!.positions; val b = shrunk.drawables.single().mesh!!.positions
        assertTrue(a[0] > 5f && a[1] > 5f); assertTrue(b[0] < 5f && b[1] < 5f)
        assertEquals(10f, a[0] + b[0], 0.0001f); assertEquals(10f, a[1] + b[1], 0.0001f)
        assertContentEquals(source.drawables.single().mesh!!.positions.sliceArray(2..5), a.sliceArray(2..5))
        assertEquals(CanvasBrushPoint(0f, 0f), CanvasDeformStroke.inflateOffset(CanvasBrushPoint(0f, 0f), CanvasBrushPoint(0f, 0f), CanvasBrushPoint(0f, 0f), 2f))
    }

    @Test fun connectedReachStartsOnOneMeshWhileTransitiveGluePartnersFollowItsTouchedVertex() {
        val source = model().copy(drawables = listOf(mesh("a"), mesh("b", 250f), mesh("c", 250f)), glues = listOf(
            Glue(DrawableId("a"), DrawableId("b"), listOf(GluePair(0, 0, 0.3f, 0.7f)), id = "ab"),
            Glue(DrawableId("b"), DrawableId("c"), listOf(GluePair(0, 0, 1f, 0f)), id = "bc")))
        val independent = source.copy(glues = emptyList())
        val both = listOf(CanvasDeformStroke.Target("mesh", "a"), CanvasDeformStroke.Target("mesh", "b"))
        val disconnected = CanvasDeformStroke.begin(independent, request(targets = both, connected = true), sample(1f, 1f)).step(sample(7f, 4f))
        assertContentEquals(independent.drawables[1].mesh!!.positions, disconnected.drawables[1].mesh!!.positions)
        val stroke = CanvasDeformStroke.begin(source, request(targets = listOf(CanvasDeformStroke.Target("mesh", "a", vertices = setOf(0)))), sample(0f, 0f))
        val result = stroke.step(sample(7f, 4f))
        assertEquals(3, stroke.commands.size)
        result.drawables.forEach { drawable ->
            assertEquals(7f, drawable.mesh!!.positions[0]); assertEquals(4f, drawable.mesh.positions[1])
            assertContentEquals(source.drawables.single { it.id == drawable.id }.mesh!!.positions.sliceArray(2..5), drawable.mesh.positions.sliceArray(2..5))
        }
        assertSame(source.glues, result.glues)
    }

    private fun boundModel(): PuppetModel {
        val source = model(); val base = source.drawables.single()
        val geometry = KeyformGrid(listOf(KeyformAxis(shape, floatArrayOf(0f, 1f))), listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(6) { 3f }))))
        val channels = channelGridsOf(FormChannel.OPACITY to KeyformGrid(listOf(KeyformAxis(shape, floatArrayOf(0f, 1f))), listOf(
            KeyformCell(intArrayOf(0), ChannelValue.Scalar(0.4f) as ChannelValue), KeyformCell(intArrayOf(1), ChannelValue.Scalar(0.8f) as ChannelValue))))
        fun binding(id: ParameterId, value: Float) = BlendShapeBinding(id, floatArrayOf(0f, 1f), 0,
            listOf(null, MeshForm(FloatArray(6) { value }, opacity = 1f)),
            listOf(BlendWeightLimit(limit, listOf(BlendWeightLimitPoint(0f, 0.3f), BlendWeightLimitPoint(1f, 1f)))))
        return source.copy(parameters = listOf(Parameter(shape, "Shape", 0f, 1f, 0f),
            Parameter(blend, "Blend", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE), Parameter(otherBlend, "Other", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE),
            Parameter(limit, "Limit", 0f, 1f, 0.25f)),
            drawables = listOf(base.copy(geometryGrid = geometry, channelGrids = channels, blendShapes = listOf(binding(blend, 4f), binding(otherBlend, 2f)))))
    }

    @Test fun normalAndBlendStrokesWriteOnlyTheirExactDestinationAndRetainScalarChannels() {
        val source = boundModel(); val pose = mapOf("Shape" to 1f, "Blend" to 1f, "OtherBlend" to 0.5f, "Limit" to 1f)
        val before = RigGeometryTools.geometry(source, "mesh", "a", pose).points
        for (key in listOf(mapOf("Shape" to 1f), mapOf("Shape" to 1f, "Blend" to 1f))) {
            val stroke = CanvasDeformStroke.begin(source, request(targets = listOf(CanvasDeformStroke.Target("mesh", "a", key, setOf(0))), pose = pose), sample(before[0], before[1]))
            val after = stroke.step(sample(before[0] + 7f, before[1] + 4f))
            val expected = before.copyOf().also { it[0] += 7f; it[1] += 4f }
            sameCoordinates(expected, RigGeometryTools.geometry(after, "mesh", "a", pose).points)
            assertSame(source.drawables.single().channelGrids, after.drawables.single().channelGrids)
            assertContentEquals(source.drawables.single().mesh!!.positions, after.drawables.single().mesh!!.positions)
            assertContentEquals(source.drawables.single().mesh!!.uvs, after.drawables.single().mesh!!.uvs)
            sameCoordinates(RigGeometryTools.geometry(source, "mesh", "a", emptyMap()).points, RigGeometryTools.geometry(after, "mesh", "a", emptyMap()).points)
            if ("Blend" in key) {
                assertSame(source.drawables.single().geometryGrid, after.drawables.single().geometryGrid)
                assertSame(source.drawables.single().blendShapes[1], after.drawables.single().blendShapes[1])
            } else assertSame(source.drawables.single().blendShapes, after.drawables.single().blendShapes)
        }
        assertFailsWith<IllegalArgumentException> { CanvasDeformStroke.begin(source, request(pose = pose), sample(0f, 0f)) }
        assertFailsWith<IllegalArgumentException> { CanvasDeformStroke.begin(source, request(targets = listOf(CanvasDeformStroke.Target("mesh", "a", mapOf("Shape" to 0f))), pose = pose), sample(0f, 0f)) }
    }

    @Test fun realWarpParentMapsCanvasPixelsAndCtrlWarpPreservesChildMotion() {
        val warp = Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(10f, 20f, 110f, 25f, 15f, 120f, 120f, 125f))))))
        val source = model().copy(deformers = listOf(warp), drawables = listOf(mesh("a").copy(parentDeformerId = warp.id,
            mesh = DrawableMesh(floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.2f, 0.8f), model().drawables.single().mesh!!.uvs, intArrayOf(0, 1, 2)))))
        val evaluator = CpuDeformationEvaluator(); val world = evaluator.evaluate(source, emptyMap()).worldPositions.getValue(DrawableId("a"))
        val stroke = CanvasDeformStroke.begin(source, request(targets = listOf(CanvasDeformStroke.Target("mesh", "a", vertices = setOf(0)))), sample(world[0], -world[1]))
        val edited = stroke.step(sample(world[0] + 8f, -world[1] + 5f))
        val next = evaluator.evaluate(edited, emptyMap()).worldPositions.getValue(DrawableId("a"))
        assertEquals(world[0] + 8f, next[0], 0.02f); assertEquals(world[1] - 5f, next[1], 0.02f)
        sameCoordinates(world.sliceArray(2..5), next.sliceArray(2..5))
        for (mode in CanvasDeformStroke.Mode.entries) for (ctrl in listOf(false, true)) {
            val warpStroke = CanvasDeformStroke.begin(source, request(mode = mode, targets = listOf(CanvasDeformStroke.Target("warp", "warp", vertices = setOf(0)))), sample(10f, 20f))
            val result = warpStroke.step(sample(5f, 15f, ctrl = ctrl))
            val resultWorld = evaluator.evaluate(result, emptyMap()).worldPositions.getValue(DrawableId("a"))
            if (mode == CanvasDeformStroke.Mode.EDIT || ctrl) sameCoordinates(world, resultWorld, 0.02f)
            else assertTrue(world.indices.any { kotlin.math.abs(world[it] - resultWorld[it]) > 0.1f })
        }
    }

    @Test fun warpBlendCaptureUsesTheFullViewingPoseAndLimitWhileKeepingOtherBindings() {
        val parameters = boundModel().parameters
        val base = floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)
        fun shifted(amount: Float) = FloatArray(base.size) { base[it] + amount }
        fun binding(id: ParameterId, amount: Float) = BlendShapeBinding(id, floatArrayOf(0f, 1f), 0,
            listOf(null, WarpForm(shifted(amount))), listOf(BlendWeightLimit(limit,
                listOf(BlendWeightLimitPoint(0f, 0.3f), BlendWeightLimitPoint(1f, 1f)))))
        val warp = Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(listOf(KeyformAxis(shape, floatArrayOf(0f, 1f))), listOf(
                KeyformCell(intArrayOf(0), WarpLatticeForm(base)), KeyformCell(intArrayOf(1), WarpLatticeForm(shifted(3f))))),
            blendShapes = listOf(binding(blend, 4f), binding(otherBlend, 2f)))
        val source = model().copy(parameters = parameters, deformers = listOf(warp))
        val pose = mapOf("Shape" to 1f, "Blend" to 1f, "OtherBlend" to 0.5f, "Limit" to 1f)
        val before = RigGeometryTools.geometry(source, "warp", "warp", pose).points
        val stroke = CanvasDeformStroke.begin(source, request(targets = listOf(CanvasDeformStroke.Target("warp", "warp",
            mapOf("Shape" to 1f, "Blend" to 1f), setOf(0))), pose = pose), sample(before[0], before[1]))
        val result = stroke.step(sample(before[0] + 7f, before[1] + 4f))
        val expected = before.copyOf().also { it[0] += 7f; it[1] += 4f }
        sameCoordinates(expected, RigGeometryTools.geometry(result, "warp", "warp", pose).points)
        val next = result.deformers.single() as Deformer.Warp
        assertSame(warp.geometryGrid, next.geometryGrid); assertSame(warp.blendShapes[1], next.blendShapes[1])
        assertSame(warp.channelGrids, next.channelGrids)
        assertEquals(warp.blendShapes[0].limits, next.blendShapes[0].limits)
        val replay = RigAuthoringJournal.compile(source, WorkspaceCanvasDeformEdits.commands(source, WorkspaceCanvasDeformEdits.operation(stroke))).first
        sameCoordinates(expected, RigGeometryTools.geometry(replay, "warp", "warp", pose).points)
    }

    @Test fun interruptedOrInvalidStepDoesNotPublishAPartialCandidate() {
        val source = model(); val stroke = CanvasDeformStroke.begin(source, request(), sample(0f, 0f))
        stroke.step(sample(1f, 1f)); val prior = stroke.preview; val priorCommands = stroke.commands
        assertFailsWith<IllegalArgumentException> { stroke.step(sample(Float.NaN, 0f)) }
        assertSame(prior, stroke.preview); assertEquals(priorCommands, stroke.commands)
        Thread.currentThread().interrupt()
        try { assertFailsWith<CancellationException> { stroke.step(sample(2f, 2f)) } }
        finally { Thread.interrupted() }
        assertSame(prior, stroke.preview); assertEquals(priorCommands, stroke.commands)
        assertEquals(2, stroke.capturedSamples().size)
    }
}
