package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonJointTemplatesTest {
	private val bones = listOf(SkinBone(-1, 0.0, 0.0, 0.0, 100.0, 0.0), SkinBone(0, 0.0, 100.0, 0.0, 200.0, 35.0))
	private val rows = listOf(0f, 50f, 75f, 85f, 90f, 95f, 100f, 105f, 110f, 115f, 125f, 150f, 200f)
	private val canvas = rows.flatMap { y -> listOf(-10f, y, 0f, y, 10f, y) }.toFloatArray()
	private val triangles = (0 until rows.size - 1).flatMap { row -> (0..1).flatMap { column ->
		val a = row * 3 + column; listOf(a, a + 1, a + 3, a + 1, a + 4, a + 3)
	} }.toIntArray()
	private val skins = SkeletonWeights.skin(canvas, bones, triangles)
	private fun templates(knee: Boolean) = SkeletonJointTemplates(canvas, triangles, skins, bones,
		if (knee) listOf(BoneRole.THIGH, BoneRole.SHIN) else listOf(BoneRole.UPPER_ARM, BoneRole.FOREARM))

	@Test fun distinctMeasuredJointSurfacesAndAngleInterpolation() {
		val knee = SkeletonJointTemplates.sample("knee", 120.0, 0.0, 0.0)
		val elbow = SkeletonJointTemplates.sample("elbow", 120.0, 0.0, 0.0)
		// Barycentrically tracked outer surface samples from the official posed model.
		assertEquals(0.366162, knee[0], 1e-6)
		assertEquals(-0.301990, knee[1], 1e-6)
		assertEquals(-0.634387, elbow[1], 1e-6)
		val halfway = SkeletonJointTemplates.sample("knee", 112.5, 0.0, 0.0)
		val before = SkeletonJointTemplates.sample("knee", 105.0, 0.0, 0.0)
		for (axis in 0..1) assertTrue(halfway[axis] in minOf(before[axis], knee[axis])..maxOf(before[axis], knee[axis]))
	}

	@Test fun correctionIsLocalAndRestIsUnchanged() {
		val template = templates(true)
		val (rest, restWeights) = template.guide(canvas, floatArrayOf(0f, 0f))
		assertTrue(rest.contentEquals(canvas)); assertTrue(restWeights.all { it == 0.0 })
		val (guide, weights) = template.guide(canvas, floatArrayOf(0f, 120f))
		assertTrue(weights.any { it > 0.0 }, "closed knee silhouette activates the reference cage")
		for (v in skins.indices) if (skins[v].rigid) {
			assertEquals(0.0, weights[v]); assertEquals(canvas[v * 2], guide[v * 2]); assertEquals(canvas[v * 2 + 1], guide[v * 2 + 1])
		}
		assertTrue(guide.all(Float::isFinite))
		val elbow = templates(false).guide(canvas, floatArrayOf(0f, 120f)).first
		assertTrue(guide.indices.any { kotlin.math.abs(guide[it] - elbow[it]) > 1f })
	}

	@Test fun oppositeFlexionUsesMirroredAnatomy() {
		val template = templates(true)
		val positive = template.guide(canvas, floatArrayOf(0f, 90f))
		val negative = template.guide(canvas, floatArrayOf(0f, -90f))
		for (row in rows.indices) for (column in 0..2) {
			val a = row * 3 + column; val b = row * 3 + 2 - column
			assertEquals(positive.second[a], negative.second[b], 1e-9)
			assertEquals(positive.first[a * 2] - canvas[a * 2], -(negative.first[b * 2] - canvas[b * 2]), 1e-4f)
			assertEquals(positive.first[a * 2 + 1] - canvas[a * 2 + 1], negative.first[b * 2 + 1] - canvas[b * 2 + 1], 1e-4f)
		}
	}

	@Test fun parentScaleCarriesTheLocalCorrection() {
		val template = templates(true)
		val seed = FloatArray(canvas.size)
		for ((v, skin) in skins.withIndex()) {
			val weight = if (skin.rigid) { if (skin.from == 1) 1f else 0f } else skin.weight
			val p = SkeletonIk.rotate(canvas[v * 2].toDouble(), canvas[v * 2 + 1].toDouble(), 0.0, 100.0, (90f * weight).toDouble())
			seed[v * 2] = p[0].toFloat(); seed[v * 2 + 1] = p[1].toFloat()
		}
		val scaledSeed = FloatArray(seed.size) { seed[it] * if (it % 2 == 0) .8f else .5f }
		val normal = template.guide(seed, floatArrayOf(0f, 90f)).first
		val scaled = template.guide(scaledSeed, floatArrayOf(0f, 90f), listOf(doubleArrayOf(0.8, 0.0, 0.0, 0.5), doubleArrayOf(1.0, 0.0, 0.0, 1.0))).first
		for (v in skins.indices) {
			assertEquals(normal[v * 2] * 0.8f, scaled[v * 2], 1e-4f)
			assertEquals(normal[v * 2 + 1] * 0.5f, scaled[v * 2 + 1], 1e-4f)
		}
	}

	@Test fun referenceGuideReducesAnatomicalTargetError() {
		for (knee in listOf(true, false)) for (angle in listOf(-120f, -90f, 90f, 120f)) {
			val seed = FloatArray(canvas.size); val target = FloatArray(canvas.size)
			for ((v, skin) in skins.withIndex()) {
				val weight = if (skin.rigid) { if (skin.from == 1) 1f else 0f } else skin.weight
				val rotated = SkeletonIk.rotate(canvas[v * 2].toDouble(), canvas[v * 2 + 1].toDouble(), 0.0, 100.0, angle.toDouble())
				val guess = SkeletonIk.rotate(canvas[v * 2].toDouble(), canvas[v * 2 + 1].toDouble(), 0.0, 100.0, (angle * weight).toDouble())
				for (axis in 0..1) {
					target[v * 2 + axis] = (canvas[v * 2 + axis] + (rotated[axis] - canvas[v * 2 + axis]) * weight).toFloat()
					seed[v * 2 + axis] = guess[axis].toFloat()
				}
			}
			val solver = SkeletonArap(canvas, triangles, BooleanArray(skins.size) { !skins[it].rigid })
			val (guide, penalty) = templates(knee).guide(seed, floatArrayOf(0f, angle))
			val plain = solver.solve(target, seed)
			val corrected = solver.solve(target, seed, guide, penalty)
			fun error(points: FloatArray) = skins.indices.sumOf { v ->
				penalty[v] * ((points[v * 2] - guide[v * 2]).toDouble().let { it * it } + (points[v * 2 + 1] - guide[v * 2 + 1]).toDouble().let { it * it })
			}
			assertTrue(error(corrected) < error(plain) * .5, "reference error reduced for knee=$knee at $angle")
		}
	}

	@Test fun innerFoldTargetsCoincideOnOneLineForEveryJointType() {
		for (roles in listOf(listOf(BoneRole.THIGH, BoneRole.SHIN), listOf(BoneRole.UPPER_ARM, BoneRole.FOREARM), listOf(BoneRole.FOREARM, BoneRole.HAND), listOf(BoneRole.CUSTOM, BoneRole.CUSTOM))) {
			for (angle in listOf(-120f, 120f)) {
				val seed = FloatArray(canvas.size)
				for ((v, skin) in skins.withIndex()) {
					val weight = if (skin.rigid) { if (skin.from == 1) 1f else 0f } else skin.weight
					val p = SkeletonIk.rotate(canvas[v * 2].toDouble(), canvas[v * 2 + 1].toDouble(), 0.0, 100.0, (angle * weight).toDouble())
					seed[v * 2] = p[0].toFloat(); seed[v * 2 + 1] = p[1].toFloat()
				}
				val (guide, penalties) = SkeletonJointTemplates(canvas, triangles, skins, bones, roles).guide(seed, floatArrayOf(0f, angle))
				val column = if (angle > 0) 0 else 2
				val a = rows.indexOf(95f) * 3 + column; val b = rows.indexOf(105f) * 3 + column
				assertTrue(penalties[a] >= 64.0 && penalties[b] >= 64.0)
				for (axis in 0..1) assertEquals(guide[a * 2 + axis], guide[b * 2 + axis], 1e-4f, "$roles at $angle")
				val half = Math.toRadians(angle * .5)
				assertEquals(0.0, guide[a * 2] * -kotlin.math.sin(half) + (guide[a * 2 + 1] - 100.0) * kotlin.math.cos(half), 1e-4)
			}
		}
	}

	@Test fun sampledContourHasContinuousTangents() {
		for (kind in listOf("knee", "elbow")) for (s in (-10..10).map { it * .25 }) {
			val e = 1e-5
			val left = SkeletonJointTemplates.sample(kind, 120.0, s - e, 0.0)
			val middle = SkeletonJointTemplates.sample(kind, 120.0, s, 0.0)
			val right = SkeletonJointTemplates.sample(kind, 120.0, s + e, 0.0)
			for (axis in 0..1) assertEquals((middle[axis] - left[axis]) / e, (right[axis] - middle[axis]) / e, 1e-3)
		}
	}

	@Test fun tightBendDoesNotCarryTheBlendBandRadiusIntoTheOuterPivot() {
		for (knee in listOf(true, false)) for (angle in listOf(-120f, 120f, -150f, 150f)) {
			val seed = angularSeed(angle)
			val guide = templates(knee).guide(seed, floatArrayOf(0f, angle)).first
			val outer = rows.indexOf(100f) * 3 + if (angle > 0) 2 else 0
			val distance = kotlin.math.hypot(guide[outer * 2].toDouble(), guide[outer * 2 + 1] - 100.0)
			assertTrue(distance <= 10.0, "outer pivot extends beyond the rest half-width: $distance at $angle, knee=$knee")
		}
	}

	@Test fun innerContactCannotExtendOutwardAlongAnUnboundedOffsetRay() {
		for (angle in listOf(-150f, -120f, 120f, 150f)) {
			val template = templates(true)
			val guide = template.guide(angularSeed(angle), floatArrayOf(0f, angle)).first
			val closed = template.folding(floatArrayOf(0f, angle), closed = true)
			assertTrue(closed.any { it })
			for (v in closed.indices) if (closed[v]) {
				assertTrue(kotlin.math.hypot(guide[v * 2].toDouble(), guide[v * 2 + 1] - 100.0) <= 10.0001,
					"contact seam pushed outward at $angle, row=${rows[v / 3]}")
			}
			// The points 1.5 half-widths away belong to the limb, not the closed crease.
			assertTrue((0..2).none { closed[rows.indexOf(85f) * 3 + it] || closed[rows.indexOf(115f) * 3 + it] })
		}
	}

	@Test fun outerCorrectionMeetsShortRigidBandsWithTheSameTangent() {
		val narrow = listOf(bones[0], SkinBone(0, 0.0, 100.0, 0.0, 200.0, 15.0))
		val yValues = listOf(0f, 84.98f, 84.99f, 85f, 85.01f, 85.02f, 100f, 114.98f, 114.99f, 115f, 115.01f, 115.02f, 200f)
		val points = yValues.flatMap { listOf(-10f, it, 0f, it, 10f, it) }.toFloatArray()
		val faces = (0 until yValues.lastIndex).flatMap { row -> (0..1).flatMap { column ->
			val a = row * 3 + column; listOf(a, a + 1, a + 3, a + 1, a + 4, a + 3)
		} }.toIntArray()
		val weights = SkeletonWeights.skin(points, narrow, faces)
		val template = SkeletonJointTemplates(points, faces, weights, narrow, listOf(BoneRole.THIGH, BoneRole.SHIN))
		val seed = FloatArray(points.size)
		for ((v, skin) in weights.withIndex()) {
			val w = if (skin.rigid) { if (skin.from == 1) 1f else 0f } else skin.weight
			val p = SkeletonIk.rotate(points[v * 2].toDouble(), points[v * 2 + 1].toDouble(), 0.0, 100.0, (120f * w).toDouble())
			seed[v * 2] = p[0].toFloat(); seed[v * 2 + 1] = p[1].toFloat()
		}
		val guide = template.guide(seed, floatArrayOf(0f, 120f)).first
		for (edge in listOf(85f, 115f)) {
			val row = yValues.indexOf(edge)
			for (axis in 0..1) {
				fun coordinate(r: Int) = guide[(r * 3 + 2) * 2 + axis]
				val before = (coordinate(row) - coordinate(row - 1)) / (yValues[row] - yValues[row - 1])
				val after = (coordinate(row + 1) - coordinate(row)) / (yValues[row + 1] - yValues[row])
				assertEquals(before, after, .03f, "outer tangent at rigid band edge $edge, axis=$axis")
			}
		}
	}

	@Test fun closedContactPinsTheSilhouetteAndLeavesItsInteriorSoft() {
		val points = listOf(90f, 100f, 110f).flatMap { y -> listOf(-10f, -7.5f, 0f, 7.5f, 10f).flatMap { listOf(it, y) } }.toFloatArray()
		val faces = (0..1).flatMap { row -> (0..3).flatMap { col ->
			val a = row * 5 + col; listOf(a, a + 1, a + 5, a + 1, a + 6, a + 5)
		} }.toIntArray()
		val skins = SkeletonWeights.skin(points, bones, faces)
		val template = SkeletonJointTemplates(points, faces, skins, bones, listOf(BoneRole.THIGH, BoneRole.SHIN))
		val closed = template.folding(floatArrayOf(0f, 120f), closed = true)
		assertTrue(closed[5], "inner silhouette closes")
		assertTrue(!closed[6], "adjacent interior vertex must not be hard flattened")
		assertTrue(template.folding(floatArrayOf(0f, 120f))[6], "interior still permits overlap")
		val fairing = template.fairing(floatArrayOf(0f, 120f))
		assertEquals(0.0, fairing[5], "closed contact is fixed")
		assertTrue(fairing[6] > 0.0, "inner surface must participate in smoothing")
	}

	@Test fun mixedParentAndChildSectionsKeepTheirWidthOutsideTheContactCage() {
		for (angle in listOf(-150f, -120f, -90f, 60f, 90f, 120f, 150f)) {
			val guide = templates(true).guide(angularSeed(angle), floatArrayOf(0f, angle)).first
			for (y in listOf(75f, 85f, 115f, 125f)) {
				val row = rows.indexOf(y); val a = row * 3; val b = a + 2
				assertEquals(20.0, kotlin.math.hypot((guide[b * 2] - guide[a * 2]).toDouble(), (guide[b * 2 + 1] - guide[a * 2 + 1]).toDouble()),
					1e-4, "cross-section at $y, $angle")
			}
		}
	}

	@Test fun innerCreasePreservesSuccessiveMaterialSectionsInsteadOfCrushingThemToOnePoint() {
		for (angle in listOf(-120f, 120f)) {
			val guide = templates(true).guide(angularSeed(angle), floatArrayOf(0f, angle)).first
			val column = if (angle > 0) 0 else 2
			val centre = rows.indexOf(100f) * 3 + column
			val before = rows.indexOf(95f) * 3 + column
			val after = rows.indexOf(105f) * 3 + column
			for (axis in 0..1) assertEquals(guide[before * 2 + axis], guide[after * 2 + axis], 1e-4f)
			assertTrue(kotlin.math.hypot((guide[before * 2] - guide[centre * 2]).toDouble(),
				(guide[before * 2 + 1] - guide[centre * 2 + 1]).toDouble()) > 3.0,
				"different material sections must retain distinct positions along the contact line at $angle")
		}
	}

	@Test fun referencePoseInterpolationHasContinuousAngularTangents() {
		for (kind in listOf("knee", "elbow")) for (angle in 15..135 step 15) {
			val e = .001
			for (s in listOf(-1.0, 0.0, 1.0)) for (fraction in listOf(0.0, 1.0)) {
				val left = SkeletonJointTemplates.sample(kind, angle - e, s, fraction)
				val middle = SkeletonJointTemplates.sample(kind, angle.toDouble(), s, fraction)
				val right = SkeletonJointTemplates.sample(kind, angle + e, s, fraction)
				for (axis in 0..1) assertEquals((middle[axis] - left[axis]) / e, (right[axis] - middle[axis]) / e, 1e-5)
			}
		}
	}

	private fun angularSeed(angle: Float) = FloatArray(canvas.size).also { seed ->
		for ((v, skin) in skins.withIndex()) {
			val w = if (skin.rigid) { if (skin.from == 1) 1f else 0f } else skin.weight
			val p = SkeletonIk.rotate(canvas[v * 2].toDouble(), canvas[v * 2 + 1].toDouble(), 0.0, 100.0, (angle * w).toDouble())
			seed[v * 2] = p[0].toFloat(); seed[v * 2 + 1] = p[1].toFloat()
		}
	}
}
