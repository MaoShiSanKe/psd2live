package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonJointTemplatesTest {
	private val bones = listOf(SkinBone(-1, 0.0, 0.0, 0.0, 100.0, 0.0), SkinBone(0, 0.0, 100.0, 0.0, 200.0, 35.0))
	private val rows = listOf(0f, 50f, 75f, 100f, 125f, 150f, 200f)
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
		for (axis in 0..1) assertEquals((before[axis] + knee[axis]) * 0.5, halfway[axis], 1e-9)
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
		val normal = template.guide(canvas, floatArrayOf(0f, 90f)).first
		val scaled = template.guide(canvas, floatArrayOf(0f, 90f), listOf(doubleArrayOf(0.8, 0.0, 0.0, 0.5), doubleArrayOf(1.0, 0.0, 0.0, 1.0))).first
		for (v in skins.indices) {
			assertEquals((normal[v * 2] - canvas[v * 2]) * 0.8f, scaled[v * 2] - canvas[v * 2], 1e-4f)
			assertEquals((normal[v * 2 + 1] - canvas[v * 2 + 1]) * 0.5f, scaled[v * 2 + 1] - canvas[v * 2 + 1], 1e-4f)
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
}
