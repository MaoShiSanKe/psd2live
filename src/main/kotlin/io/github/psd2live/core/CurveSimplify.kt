package io.github.psd2live.core

import kotlin.math.abs

/**
 * Thins a densely sampled curve to the keys that still reproduce it within a tolerance.
 *
 * The error is the vertical deviation at the sample times, the same thing a player sees when it samples
 * the simplified curve at those times, so the result never strays further than the tolerance from any
 * input sample. The first and last samples are always kept.
 */
internal object CurveSimplify {
	enum class Shape {
		/** Straight segments between the kept keys. */
		LINEAR,

		/** Cubic segments, which need far fewer keys on smooth motion. */
		BEZIER,
	}

	class Result(val keys: List<MotionKey>, val maxError: Float)

	/** A restricted Bezier's default handle length: with both at 1/3 the segment's time runs linearly along it. */
	private const val HANDLE_X = 1f / 3f

	/** [samples] are (time, value), in time order; [tolerance] is in the curve's own units. */
	fun simplify(samples: List<Pair<Float, Float>>, tolerance: Float, shape: Shape): Result {
		require(samples.isNotEmpty()) { "A curve needs a sample" }
		require(tolerance.isFinite() && tolerance >= 0f) { "Tolerance must not be negative" }
		if (samples.size == 1) return Result(listOf(MotionKey(samples[0].first, samples[0].second)), 0f)
		val times = DoubleArray(samples.size) { samples[it].first.toDouble() }
		val values = DoubleArray(samples.size) { samples[it].second.toDouble() }
		val segments = ArrayList<Segment>()
		val stack = ArrayDeque<IntArray>()
		stack.addLast(intArrayOf(0, samples.lastIndex))
		while (stack.isNotEmpty()) {
			val (from, to) = stack.removeLast().let { it[0] to it[1] }
			val segment = fit(times, values, from, to, tolerance.toDouble(), shape)
			if (segment.split < 0) segments += segment
			else {
				// Depth-first from the right keeps the accepted segments in time order once sorted.
				stack.addLast(intArrayOf(segment.split, to))
				stack.addLast(intArrayOf(from, segment.split))
			}
		}
		segments.sortBy { it.from }
		val keys = ArrayList<MotionKey>(segments.size + 1)
		var maxError = 0.0
		for ((index, segment) in segments.withIndex()) {
			maxError = maxOf(maxError, segment.error)
			val start = keys.lastOrNull()?.takeIf { index > 0 } ?: MotionKey(samples[segment.from].first, samples[segment.from].second)
			val end = MotionKey(samples[segment.to].first, samples[segment.to].second)
			val shaped = when (segment.kind) {
				Kind.LINEAR -> start to end
				Kind.BEZIER -> start.copy(interpolation = MotionInterpolation.BEZIER, outHandle = MotionHandle(HANDLE_X, segment.out.toFloat())) to
					end.copy(inHandle = MotionHandle(HANDLE_X, segment.into.toFloat()))
			}
			if (index == 0) keys += shaped.first else keys[keys.lastIndex] = shaped.first
			keys += shaped.second
		}
		return Result(keys, maxError.toFloat())
	}

	private enum class Kind { LINEAR, BEZIER }

	/** [split] is the sample to split at, or -1 when the segment from..to is accepted as it is. */
	private class Segment(val from: Int, val to: Int, val kind: Kind, val out: Double, val into: Double, val error: Double, val split: Int)

	private fun fit(times: DoubleArray, values: DoubleArray, from: Int, to: Int, tolerance: Double, shape: Shape): Segment {
		if (to - from <= 1) return Segment(from, to, Kind.LINEAR, 0.0, 0.0, 0.0, -1)
		val span = times[to] - times[from]
		if (span <= MotionClips.TIME_EPSILON) return Segment(from, to, Kind.LINEAR, 0.0, 0.0, 0.0, -1)
		// Straight first: it is the cheapest key pair, so take it whenever it holds.
		var worst = 0.0
		var worstAt = from
		for (i in from + 1 until to) {
			val line = values[from] + (values[to] - values[from]) * ((times[i] - times[from]) / span)
			val error = abs(values[i] - line)
			if (error > worst) {
				worst = error
				worstAt = i
			}
		}
		if (worst <= tolerance) return Segment(from, to, Kind.LINEAR, 0.0, 0.0, worst, -1)
		if (shape == Shape.BEZIER) {
			val (out, into) = leastSquaresHandles(times, values, from, to)
			var error = 0.0
			var at = from
			for (i in from + 1 until to) {
				val s = (times[i] - times[from]) / span
				val e = abs(values[i] - cubic(values[from], out, into, values[to], s))
				if (e > error) {
					error = e
					at = i
				}
			}
			if (error <= tolerance) return Segment(from, to, Kind.BEZIER, out, into, error, -1)
			return Segment(from, to, Kind.LINEAR, 0.0, 0.0, error, at)
		}
		return Segment(from, to, Kind.LINEAR, 0.0, 0.0, worst, worstAt)
	}

	/** The value at [s] (0..1) of a segment whose handles sit [out] above its start and [into] above its end, both a third along. */
	private fun cubic(v0: Double, out: Double, into: Double, v1: Double, s: Double): Double {
		val u = 1.0 - s
		return u * u * u * v0 + 3 * u * u * s * (v0 + out) + 3 * u * s * s * (v1 + into) + s * s * s * v1
	}

	/**
	 * The two handle offsets that make the segment from..to fit the samples between best.
	 *
	 * With handles a third of the way along, time is linear in the Bezier's parameter, so each sample's parameter
	 * is known and the fit is linear in the two offsets: a 2x2 normal system.
	 */
	private fun leastSquaresHandles(times: DoubleArray, values: DoubleArray, from: Int, to: Int): Pair<Double, Double> {
		val span = times[to] - times[from]
		var a11 = 0.0
		var a12 = 0.0
		var a22 = 0.0
		var b1 = 0.0
		var b2 = 0.0
		for (i in from + 1 until to) {
			val s = (times[i] - times[from]) / span
			val u = 1.0 - s
			val c1 = 3 * u * u * s
			val c2 = 3 * u * s * s
			// What the curve holds at s with both offsets zero; the offsets explain the rest.
			val base = u * u * u * values[from] + c1 * values[from] + c2 * values[to] + s * s * s * values[to]
			val r = values[i] - base
			a11 += c1 * c1
			a12 += c1 * c2
			a22 += c2 * c2
			b1 += c1 * r
			b2 += c2 * r
		}
		val det = a11 * a22 - a12 * a12
		if (abs(det) < 1e-12) return 0.0 to 0.0
		return (b1 * a22 - b2 * a12) / det to (a11 * b2 - a12 * b1) / det
	}
}
