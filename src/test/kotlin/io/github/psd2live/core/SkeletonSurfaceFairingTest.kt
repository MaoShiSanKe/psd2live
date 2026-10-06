package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonSurfaceFairingTest {
	private val points = (0..20).flatMap { listOf(0f, it * 5f, 20f, it * 5f) }.toFloatArray()
	private val triangles = (0 until 20).flatMap { val a = it * 2; listOf(a, a + 1, a + 2, a + 1, a + 3, a + 2) }.toIntArray()
	private val mask = DoubleArray(points.size / 2) { if (it % 2 == 1 && it in 3..39) 1.0 else 0.0 }

	@Test fun suppressesAlternatingContourWrinklesAndPinsTheOtherVertices() {
		val posed = points.copyOf()
		for (v in mask.indices) if (mask[v] > 0) posed[v * 2] += if (v / 2 % 2 == 0) 2f else -2f
		val before = posed.copyOf()
		SkeletonSurfaceFairing(points, triangles).apply(posed, mask)
		fun noise(p: FloatArray) = mask.indices.filter { mask[it] > 0 }.sumOf { (p[it * 2] - 20.0).let { d -> d * d } }
		assertTrue(noise(posed) < noise(before) * .25)
		for (v in mask.indices) if (mask[v] == 0.0) for (axis in 0..1) assertEquals(before[v * 2 + axis], posed[v * 2 + axis])
		val meanWidth = mask.indices.filter { mask[it] > 0 }.map { posed[it * 2] }.average()
		assertEquals(20.0, meanWidth, .15, "fairing must not narrow the whole limb")
	}

	@Test fun straightContourIsUnchanged() {
		val actual = points.copyOf()
		SkeletonSurfaceFairing(points, triangles).apply(actual, mask)
		assertTrue(actual.contentEquals(points))
	}

	@Test fun smoothsInteriorWrinklesWithTheEntireBoundaryPinned() {
		val rest = (0..20).flatMap { row -> (0..4).flatMap { col -> listOf(col * 5f, row * 5f) } }.toFloatArray()
		val faces = (0 until 20).flatMap { row -> (0 until 4).flatMap { col ->
			val a = row * 5 + col; listOf(a, a + 1, a + 5, a + 1, a + 6, a + 5)
		} }.toIntArray()
		val weights = DoubleArray(rest.size / 2) { if (it / 5 in 1..19 && it % 5 in 1..3) 1.0 else 0.0 }
		val posed = rest.copyOf()
		for (row in 1..19) posed[(row * 5 + 2) * 2] += if (row % 2 == 0) 2f else -2f
		fun error(p: FloatArray) = weights.indices.sumOf { v -> (p[v * 2] - rest[v * 2]).toDouble().let { it * it } }
		val before = error(posed)
		val solver = SkeletonSurfaceFairing(rest, faces)
		solver.apply(posed, weights)
		assertTrue(error(posed) < before * .25, "interior wrinkle energy must decrease")
		for (v in weights.indices) if (weights[v] == 0.0) for (axis in 0..1) assertEquals(rest[v * 2 + axis], posed[v * 2 + axis])
		val affine = rest.copyOf()
		solver.apply(affine, weights)
		assertTrue(affine.contentEquals(rest), "straight material field must keep its width")
	}
}
