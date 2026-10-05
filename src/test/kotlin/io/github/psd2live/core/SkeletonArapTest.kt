package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonArapTest {
	private val points = floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f, 10f, 10f)
	private val triangles = intArrayOf(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4)

	@Test fun rigidTransformIsRecoveredWithExactHandles() {
		val solver = SkeletonArap(points, triangles, booleanArrayOf(false, false, false, false, true))
		for (angle in listOf(-150.0, -90.0, 0.0, 120.0, 150.0)) {
			val target = FloatArray(points.size)
			for (i in points.indices step 2) {
				val p = SkeletonIk.rotate(points[i].toDouble(), points[i + 1].toDouble(), 0.0, 0.0, angle)
				target[i] = p[0].toFloat() + 30f; target[i + 1] = p[1].toFloat() - 40f
			}
			val seed = target.copyOf().also { it[8] += 3f; it[9] -= 2f }
			val actual = solver.solve(target, seed)
			for (i in target.indices) assertEquals(target[i], actual[i], 0.01f, "coordinate $i at $angle")
		}
	}

	@Test fun disconnectedUnconstrainedIslandRetainsItsSkinningTarget() {
		val solver = SkeletonArap(points, triangles, BooleanArray(5) { true })
		val target = points.map { it * 0.8f + 10f }.toFloatArray()
		val actual = solver.solve(target)
		for (i in target.indices) assertEquals(target[i], actual[i])
		assertTrue(actual.all(Float::isFinite))
	}

	@Test fun degenerateMeshDoesNotProduceNonFiniteCoordinates() {
		val solver = SkeletonArap(floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f), intArrayOf(0, 1, 2), booleanArrayOf(false, true, false))
		assertTrue(solver.solve(floatArrayOf(0f, 0f, 10f, 2f, 20f, 0f)).all(Float::isFinite))
	}

	@Test fun poseGuideMovesFreeRegionAndKeepsRigidHandles() {
		val solver = SkeletonArap(points, triangles, booleanArrayOf(false, false, false, false, true))
		val guide = points.copyOf().also { it[8] = 13f; it[9] = 12f }
		val actual = solver.solve(points, points, guide, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 100.0))
		for (i in 0..7) assertEquals(points[i], actual[i])
		assertEquals(13f, actual[8], .05f); assertEquals(12f, actual[9], .05f)
		val impossible = guide.copyOf().also { it[8] = 1000f }
		val safe = solver.solve(points, points, impossible, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 100.0))
		assertTrue(safe[8] < 20f, "guide cannot pull the centre across the rigid boundary")
	}
}
