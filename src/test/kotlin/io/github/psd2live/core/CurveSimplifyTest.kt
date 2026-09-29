package io.github.psd2live.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CurveSimplifyTest {
	private fun wave(frames: Int = 121, fps: Float = 60f, amplitude: Float = 30f, cycles: Float = 1f): List<Pair<Float, Float>> =
		(0 until frames).map { i ->
			val t = i / fps
			t to amplitude * sin(2.0 * PI * cycles * i / (frames - 1)).toFloat()
		}

	/** The largest deviation of the simplified keys from [samples], measured the way a player samples them. */
	private fun deviation(keys: List<MotionKey>, samples: List<Pair<Float, Float>>): Float {
		val curve = MotionCurve("P", keys)
		return samples.maxOf { (t, v) -> abs(MotionClips.sample(curve, t) - v) }
	}

	@Test fun linearResultStaysWithinTheTolerance() {
		val samples = wave()
		for (tolerance in listOf(0.05f, 0.5f, 2f)) {
			val result = CurveSimplify.simplify(samples, tolerance, CurveSimplify.Shape.LINEAR)
			assertTrue(deviation(result.keys, samples) <= tolerance + 1e-3f, "tolerance $tolerance")
			assertTrue(result.maxError <= tolerance + 1e-6f)
		}
	}

	@Test fun bezierResultStaysWithinTheToleranceAndNeedsFewerKeys() {
		val samples = wave()
		for (tolerance in listOf(0.05f, 0.5f, 2f)) {
			val linear = CurveSimplify.simplify(samples, tolerance, CurveSimplify.Shape.LINEAR)
			val bezier = CurveSimplify.simplify(samples, tolerance, CurveSimplify.Shape.BEZIER)
			assertTrue(deviation(bezier.keys, samples) <= tolerance + 1e-3f, "tolerance $tolerance")
			assertTrue(bezier.keys.size <= linear.keys.size, "bezier ${bezier.keys.size} vs linear ${linear.keys.size}")
			assertTrue(bezier.keys.any { it.interpolation == MotionInterpolation.BEZIER } || bezier.keys.size == 2)
		}
		val loose = CurveSimplify.simplify(samples, 0.5f, CurveSimplify.Shape.BEZIER)
		val tight = CurveSimplify.simplify(samples, 0.5f, CurveSimplify.Shape.LINEAR)
		assertTrue(loose.keys.size <= tight.keys.size * 0.6, "a smooth wave should shrink a lot: ${loose.keys.size} vs ${tight.keys.size}")
	}

	@Test fun laxerToleranceKeepsFewerKeys() {
		val samples = wave(cycles = 3f)
		val counts = listOf(0.05f, 0.5f, 3f).map { CurveSimplify.simplify(samples, it, CurveSimplify.Shape.LINEAR).keys.size }
		assertTrue(counts[0] > counts[1] && counts[1] > counts[2], "$counts")
	}

	@Test fun endsAreKeptAndKeysStayInTimeOrder() {
		val samples = wave()
		for (shape in CurveSimplify.Shape.entries) {
			val keys = CurveSimplify.simplify(samples, 1f, shape).keys
			assertEquals(samples.first().first, keys.first().time)
			assertEquals(samples.last().first, keys.last().time)
			assertEquals(samples.first().second, keys.first().value, 1e-6f)
			assertEquals(samples.last().second, keys.last().value, 1e-6f)
			assertTrue(keys.zipWithNext().all { (a, b) -> a.time < b.time })
		}
	}

	@Test fun bezierHandlesStayInsideTheirSegments() {
		val keys = CurveSimplify.simplify(wave(), 0.2f, CurveSimplify.Shape.BEZIER).keys
		for (key in keys) {
			assertTrue(key.outHandle.x in 0f..1f && key.inHandle.x in 0f..1f)
		}
	}

	@Test fun aConstantAndAStraightRunAreTwoKeys() {
		val constant = (0..30).map { it / 30f to 7f }
		assertEquals(2, CurveSimplify.simplify(constant, 0.01f, CurveSimplify.Shape.LINEAR).keys.size)
		val ramp = (0..30).map { it / 30f to it * 0.5f }
		assertEquals(2, CurveSimplify.simplify(ramp, 0.01f, CurveSimplify.Shape.BEZIER).keys.size)
	}

	@Test fun aSingleSampleIsOneKeyAndTwoAreKept() {
		assertEquals(1, CurveSimplify.simplify(listOf(0f to 3f), 1f, CurveSimplify.Shape.LINEAR).keys.size)
		assertEquals(2, CurveSimplify.simplify(listOf(0f to 3f, 1f to 9f), 1f, CurveSimplify.Shape.BEZIER).keys.size)
	}

	@Test fun aSpikeSurvivesATolerancePastItsHeightOnlyWhenItFitsInside() {
		val samples = (0..30).map { it / 30f to if (it == 15) 10f else 0f }
		assertTrue(CurveSimplify.simplify(samples, 1f, CurveSimplify.Shape.LINEAR).keys.size >= 3)
		assertEquals(2, CurveSimplify.simplify(samples, 11f, CurveSimplify.Shape.LINEAR).keys.size)
	}

	@Test fun negativeToleranceIsRejected() {
		assertFailsWith<IllegalArgumentException> { CurveSimplify.simplify(wave(), -1f, CurveSimplify.Shape.LINEAR) }
	}
}
