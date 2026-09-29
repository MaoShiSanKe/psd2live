package io.github.psd2live.core

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkeletonBakeTest {
	private val bodyId = DeformerId("DeformBodyXY")
	private val size = 500f
	private val frame = Bounds(0f, 0f, size, size)

	private fun body() = Deformer.Warp(bodyId, "Body", null, null, 1, 1, true,
		KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(0f, 0f, size, 0f, 0f, size, size, size))))))

	private fun strip(id: String, x: Float, top: Float, bottom: Float, half: Float, step: Float, columns: Int = 4): Drawable {
		val rows = ((bottom - top) / step).toInt().coerceAtLeast(1)
		val positions = ArrayList<Float>()
		for (r in 0..rows) for (c in 0..columns) {
			positions += (x - half + 2 * half * c / columns) / size
			positions += (top + (bottom - top) * r / rows) / size
		}
		val indices = ArrayList<Int>()
		for (r in 0 until rows) for (c in 0 until columns) {
			val a = r * (columns + 1) + c
			val d = a + columns + 1
			indices += listOf(a, a + 1, d + 1, a, d + 1, d)
		}
		val p = positions.toFloatArray()
		return Drawable(DrawableId(id), id, bodyId, BlendMode.Normal, emptyList(), DrawableMesh(p, p.copyOf(), indices.toIntArray()), null)
	}

	private fun bone(id: String, parent: String?, role: BoneRole, hx: Float, hy: Float, tx: Float, ty: Float,
		meshes: List<String> = emptyList()) = SkeletonBone(id, id, parent, role, Side.LEFT, hx, hy, tx, ty, meshes)

	private val spec = SkeletonSpec(bones = listOf(
		SkeletonBone("chest", "chest", null, BoneRole.UPPER_BODY, Side.NONE, 100f, 200f, 100f, 60f, emptyList()),
		bone("upper", "chest", BoneRole.UPPER_ARM, 100f, 100f, 100f, 250f, listOf("arm")),
		bone("fore", "upper", BoneRole.FOREARM, 100f, 250f, 100f, 370f),
		bone("hand", "fore", BoneRole.HAND, 100f, 370f, 100f, 430f),
	))

	private val arm = strip("arm", 100f, 95f, 435f, 18f, 10f)

	/** The arm baked for a runtime that has every bone as a parameter, as [SkeletonRigTest] does. */
	private val baked: PuppetModel = SkeletonRig.apply(
		PuppetModel(emptyList(), emptyList(), listOf(body()), listOf(arm), listOf(OrgChild.Drawable(arm.id)), null),
		spec, frame,
	)

	private fun curve(result: BakeResult, parameter: String) = assertNotNull(result.curves.firstOrNull { it.parameterId == parameter }, parameter)

	private fun sampleAt(result: BakeResult, time: Float): Map<ParameterId, Float> =
		result.curves.associate { ParameterId(it.parameterId) to MotionClips.sample(it, time) }

	private fun tip(values: Map<ParameterId, Float>, bone: String = "hand"): Pair<Float, Float> =
		SkeletonPoseSolver.posed(baked, spec, values).single { it.bone.id == bone }.let { it.tailX to it.tailY }

	@Test fun linearEaseBetweenTwoKeysIsTwoKeys() {
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0f, mapOf("ParamArmLA" to 0f), ease = PoseEase.LINEAR),
			PoseKey(1f, mapOf("ParamArmLA" to 30f)),
		), BakeOptions(fps = 30f, tolerance = 0.01f))
		val keys = curve(result, "ParamArmLA").keys
		assertEquals(listOf(0f, 1f), keys.map { it.time })
		assertEquals(listOf(0f, 30f), keys.map { it.value })
		assertEquals(31, result.frameCount)
	}

	@Test fun smoothEaseFollowsTheSolvedFramesWithinTheTolerance() {
		val tolerance = 0.3f
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0f, mapOf("ParamArmLA" to -20f), ease = PoseEase.SMOOTH),
			PoseKey(1.5f, mapOf("ParamArmLA" to 40f)),
		), BakeOptions(fps = 60f, tolerance = tolerance))
		val curve = curve(result, "ParamArmLA")
		assertTrue(curve.keys.size < result.frameCount / 4, "${curve.keys.size} keys for ${result.frameCount} frames")
		for (i in 0..90) {
			val t = i / 60f
			val u = t / 1.5f
			val expected = -20f + 60f * (u * u * (3f - 2f * u))
			assertEquals(expected, MotionClips.sample(curve, t), tolerance + 1e-3f, "t=$t")
		}
		assertTrue(result.maxError <= tolerance + 1e-6f)
	}

	@Test fun bezierShapeNeedsFewerKeysThanLinearAtTheSameTolerance() {
		val keys = listOf(
			PoseKey(0f, mapOf("ParamArmLA" to 0f)),
			PoseKey(1f, mapOf("ParamArmLA" to 40f)),
			PoseKey(2f, mapOf("ParamArmLA" to -20f)),
			PoseKey(3f, mapOf("ParamArmLA" to 0f)),
		)
		val linear = SkeletonBake.bake(baked, spec, keys, BakeOptions(fps = 60f, tolerance = 0.2f))
		val bezier = SkeletonBake.bake(baked, spec, keys, BakeOptions(fps = 60f, tolerance = 0.2f, shape = BakeShape.BEZIER))
		assertTrue(bezier.keyCount < linear.keyCount, "${bezier.keyCount} vs ${linear.keyCount}")
		assertTrue(bezier.maxError <= 0.2f + 1e-6f)
	}

	@Test fun steppedEaseHoldsUntilTheNextKey() {
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0f, mapOf("ParamArmLA" to 10f), ease = PoseEase.STEPPED),
			PoseKey(1f, mapOf("ParamArmLA" to 30f)),
		), BakeOptions(fps = 30f, tolerance = 0.01f))
		val curve = curve(result, "ParamArmLA")
		assertEquals(10f, MotionClips.sample(curve, 0.9f), 0.05f)
		assertEquals(30f, MotionClips.sample(curve, 1f), 0.05f)
	}

	@Test fun anIkKeyPullsTheTipToItsTarget() {
		// A reachable target: where the hand is with the arm bent by FK.
		val target = tip(mapOf(ParameterId("ParamArmLA") to 25f, ParameterId("ParamArmLB") to -35f, ParameterId("ParamHandL") to 10f))
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0f, mapOf("ParamArmLA" to 0f, "ParamArmLB" to 0f, "ParamHandL" to 0f)),
			PoseKey(1f, ik = listOf(IkTarget("hand", target.first, target.second))),
		), BakeOptions(fps = 30f, tolerance = 0.2f))
		val end = tip(sampleAt(result, 1f))
		assertTrue(hypot(end.first - target.first, end.second - target.second) < 2f, "tip ended at $end, target $target")
		// Halfway the tip has moved along, not jumped.
		val start = tip(sampleAt(result, 0f))
		val middle = tip(sampleAt(result, 0.5f))
		val travelled = hypot(middle.first - start.first, middle.second - start.second)
		val total = hypot(end.first - start.first, end.second - start.second)
		assertTrue(travelled > 0.1f * total && travelled < 0.95f * total, "halfway $travelled of $total")
	}

	@Test fun anIkTargetMovesTheTipAlongItsPathEveryFrame() {
		val a = tip(mapOf(ParameterId("ParamArmLA") to 10f))
		val b = tip(mapOf(ParameterId("ParamArmLA") to 40f))
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0f, ik = listOf(IkTarget("hand", a.first, a.second)), ease = PoseEase.LINEAR),
			PoseKey(1f, ik = listOf(IkTarget("hand", b.first, b.second))),
		), BakeOptions(fps = 20f, tolerance = 0.05f))
		for (i in 0..20) {
			val u = i / 20f
			val want = a.first + (b.first - a.first) * u to a.second + (b.second - a.second) * u
			val got = tip(sampleAt(result, u))
			assertTrue(hypot(got.first - want.first, got.second - want.second) < 3f, "frame $i: $got vs $want")
		}
	}

	@Test fun parameterFilterAndRangeLimitTheBake() {
		val keys = listOf(
			PoseKey(0f, mapOf("ParamArmLA" to 0f, "ParamArmLB" to 0f)),
			PoseKey(2f, mapOf("ParamArmLA" to 40f, "ParamArmLB" to -40f)),
		)
		val only = SkeletonBake.bake(baked, spec, keys, BakeOptions(fps = 30f, tolerance = 0.1f, parameterIds = setOf("ParamArmLB")))
		assertEquals(listOf("ParamArmLB"), only.curves.map { it.parameterId })
		val ranged = SkeletonBake.bake(baked, spec, keys, BakeOptions(fps = 30f, tolerance = 0.1f, start = 0.5f, end = 1f))
		val curve = curve(ranged, "ParamArmLA")
		assertEquals(0.5f, curve.keys.first().time, 1e-4f)
		assertEquals(1f, curve.keys.last().time, 1e-4f)
		// Still eased along the whole 0..2 s: at a quarter of the way in, smoothstep(0.25) of 40.
		assertEquals(40f * (0.25f * 0.25f * (3f - 2f * 0.25f)), curve.keys.first().value, 0.2f)
	}

	@Test fun valuesAreClampedToTheParameterRange() {
		val limit = spec.bone("upper")!!.maxAngle
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0f, mapOf("ParamArmLA" to 0f)),
			PoseKey(1f, mapOf("ParamArmLA" to limit + 500f)),
		), BakeOptions(tolerance = 0.1f))
		assertTrue(curve(result, "ParamArmLA").keys.all { it.value <= limit + 1e-3f })
	}

	@Test fun aParameterAKeyLeavesOutIsJoinedAcrossTheGap() {
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0f, mapOf("ParamArmLA" to 0f), ease = PoseEase.LINEAR),
			PoseKey(1f, mapOf("ParamArmLB" to 5f)),
			PoseKey(2f, mapOf("ParamArmLA" to 20f)),
		), BakeOptions(fps = 30f, tolerance = 0.01f))
		assertEquals(10f, MotionClips.sample(curve(result, "ParamArmLA"), 1f), 0.05f)
	}

	@Test fun badInputIsRejected() {
		val one = PoseKey(0f, mapOf("ParamArmLA" to 1f))
		assertFailsWith<IllegalArgumentException> { SkeletonBake.bake(baked, spec, emptyList()) }
		assertFailsWith<IllegalArgumentException> { SkeletonBake.bake(baked, spec, listOf(one, one)) }
		assertFailsWith<IllegalArgumentException> { SkeletonBake.bake(baked, spec, listOf(PoseKey(0f, mapOf("Nope" to 1f)))) }
		assertFailsWith<IllegalArgumentException> { SkeletonBake.bake(baked, spec, listOf(PoseKey(0f, ik = listOf(IkTarget("ghost", 1f, 1f))))) }
		assertFailsWith<IllegalArgumentException> { SkeletonBake.bake(baked, SkeletonSpec.Disabled, listOf(one)) }
		assertFailsWith<IllegalArgumentException> {
			SkeletonBake.bake(baked, spec, listOf(one, PoseKey(5000f, mapOf("ParamArmLA" to 2f))), BakeOptions(fps = 120f))
		}
	}

	@Test fun cancellationStopsTheBake() {
		var frames = 0
		assertFailsWith<IllegalStateException> {
			SkeletonBake.bake(baked, spec, listOf(PoseKey(0f, mapOf("ParamArmLA" to 0f)), PoseKey(2f, mapOf("ParamArmLA" to 9f))),
				BakeOptions(fps = 30f), cancelled = { if (++frames == 5) error("stop") })
		}
		assertEquals(5, frames)
	}

	@Test fun replaceSwapsABakedParametersCurveAndMergeKeepsItsOutsideKeys() {
		val existing = MotionClip(
			id = "m", name = "M", duration = 1f,
			curves = listOf(
				MotionCurve("ParamArmLA", listOf(MotionKey(0f, 1f), MotionKey(0.4f, 2f), MotionKey(1f, 3f))),
				MotionCurve("ParamOther", listOf(MotionKey(0f, 5f))),
			),
		)
		val result = SkeletonBake.bake(baked, spec, listOf(
			PoseKey(0.5f, mapOf("ParamArmLA" to 10f), ease = PoseEase.LINEAR),
			PoseKey(2f, mapOf("ParamArmLA" to 20f)),
		), BakeOptions(fps = 30f, tolerance = 0.01f))
		val replaced = SkeletonBake.applyTo(existing, result, BakeWrite.REPLACE)
		assertEquals(2f, replaced.duration)
		assertEquals(listOf(0.5f, 2f), replaced.curve("ParamArmLA")!!.keys.map { it.time })
		assertEquals(listOf(5f), replaced.curve("ParamOther")!!.keys.map { it.value })
		val merged = SkeletonBake.applyTo(existing, result, BakeWrite.MERGE)
		assertEquals(listOf(0f, 0.4f, 0.5f, 2f), merged.curve("ParamArmLA")!!.keys.map { it.time })
		assertTrue(abs(merged.curve("ParamArmLA")!!.keys[1].value - 2f) < 1e-6f)
	}
}
