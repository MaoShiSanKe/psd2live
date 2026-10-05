package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Generated motion curves as cubic Hermite splines in time.
 *
 * Every Bezier written here keeps its handles a third of the way along the segment in time, so time runs
 * evenly along it and each segment is a plain cubic of time. Sums and linear maps of such segments are
 * cubics again, which is what lets [SkeletonMotions.played] move a pose curve onto the bones exactly
 * while keeping the keys sparse. A key may carry a different slope on either side, for a kink.
 */
internal object MotionCurveMath {
	private const val THIRD = 1f / 3f

	/** A key with the slope (value per second) the curve leaves it with and the one it arrives with. */
	class Knot(val time: Float, val value: Float, val inSlope: Float, val outSlope: Float = inSlope)

	/** [knots] as a curve of time-even Beziers. */
	fun curve(parameterId: String, knots: List<Knot>): MotionCurve {
		require(knots.isNotEmpty())
		val keys = knots.mapIndexed { i, knot ->
			val before = if (i > 0) knot.time - knots[i - 1].time else 0f
			val after = if (i < knots.lastIndex) knots[i + 1].time - knot.time else 0f
			MotionKey(
				time = knot.time,
				value = knot.value,
				interpolation = MotionInterpolation.BEZIER,
				outHandle = MotionHandle(THIRD, knot.outSlope * after * THIRD),
				inHandle = MotionHandle(THIRD, -knot.inSlope * before * THIRD),
			)
		}
		return MotionCurve(parameterId, keys)
	}

	/** Straight lines through [points], for the hand-keyed blinks and the like. */
	fun linear(parameterId: String, points: List<Pair<Float, Float>>): MotionCurve =
		MotionCurve(parameterId, points.map { (time, value) -> MotionKey(time, value, MotionInterpolation.LINEAR) })

	/**
	 * A smooth curve through [points] that never overshoots them: a key that turns back, holds or ends the
	 * curve is eased with a flat slope, and any other passes through with the gentler of its two sides'
	 * slopes (Fritsch-Carlson). This is what makes every move ease out of one pose and into the next.
	 * A [loop] carries the slope across the seam instead of easing at the ends.
	 */
	fun eased(parameterId: String, points: List<Pair<Float, Float>>, loop: Boolean = false): MotionCurve =
		curve(parameterId, easedKnots(points, loop))

	/** The knots of [eased], for a caller that sets some slopes itself. */
	fun easedKnots(points: List<Pair<Float, Float>>, loop: Boolean = false): List<Knot> {
		val p = points.sortedBy { it.first }
		if (p.size == 1) return listOf(Knot(p[0].first, p[0].second, 0f))
		val secants = p.zipWithNext { a, b -> (b.second - a.second) / max(b.first - a.first, 1e-6f) }
		fun slope(left: Float, right: Float): Float =
			if (left * right <= 0f) 0f else 2f * left * right / (left + right)
		return p.indices.map { i ->
			val s = when {
				i in 1 until p.lastIndex -> slope(secants[i - 1], secants[i])
				loop -> slope(secants.last(), secants.first())
				else -> 0f
			}
			Knot(p[i].first, p[i].second, s)
		}
	}

	/** The value of [curve] at [time], held before its first key and after its last. */
	fun value(curve: MotionCurve, time: Float): Float = MotionClips.sample(curve, time)

	/** The value of [curve] at [time] in a loop of [duration]. */
	fun looped(curve: MotionCurve, time: Double, duration: Float): Float {
		if (duration <= 1e-6f) return value(curve, 0f)
		val t = (time % duration).toFloat().let { if (it < 0f) it + duration else it }
		return value(curve, t)
	}

	/**
	 * The slope of [curve] just after [time] when [after], else just before it; flat outside the keys.
	 */
	fun slope(curve: MotionCurve, time: Float, after: Boolean): Float {
		val keys = curve.keys
		if (keys.size < 2) return 0f
		if (after && time >= keys.last().time - MotionClips.TIME_EPSILON) return 0f
		if (!after && time <= keys.first().time + MotionClips.TIME_EPSILON) return 0f
		if (time < keys.first().time || time > keys.last().time) return 0f
		val index = if (after) keys.indexOfLast { it.time <= time + MotionClips.TIME_EPSILON }
		else keys.indexOfFirst { it.time >= time - MotionClips.TIME_EPSILON } - 1
		val i = index.coerceIn(0, keys.lastIndex - 1)
		return segmentSlope(keys[i], keys[i + 1], time)
	}

	private fun segmentSlope(a: MotionKey, b: MotionKey, time: Float): Float {
		val span = b.time - a.time
		if (span < MotionClips.TIME_EPSILON) return 0f
		return when (a.interpolation) {
			MotionInterpolation.LINEAR -> (b.value - a.value) / span
			MotionInterpolation.STEPPED, MotionInterpolation.INVERSE_STEPPED -> 0f
			MotionInterpolation.BEZIER -> {
				val (c1, c2) = MotionClips.controlPoints(a, b)
				var lo = 0f
				var hi = 1f
				repeat(28) {
					val mid = (lo + hi) / 2f
					if (cubic(a.time, c1.first, c2.first, b.time, mid) < time) lo = mid else hi = mid
				}
				val s = (lo + hi) / 2f
				val dt = derivative(a.time, c1.first, c2.first, b.time, s)
				if (abs(dt) < 1e-9f) 0f else derivative(a.value, c1.second, c2.second, b.value, s) / dt
			}
		}
	}

	/**
	 * Every time inside [curve]'s span at which it crosses [level], found to a fraction of a millisecond.
	 * A touch without a crossing is not reported.
	 */
	fun crossings(curve: MotionCurve, level: Float): List<Float> {
		val keys = curve.keys
		val out = ArrayList<Float>()
		for ((a, b) in keys.zipWithNext()) {
			val span = b.time - a.time
			if (span < MotionClips.TIME_EPSILON) continue
			val steps = 24
			var t0 = a.time
			var v0 = value(curve, t0) - level
			for (i in 1..steps) {
				val t1 = if (i == steps) b.time else a.time + span * i / steps
				val v1 = value(curve, t1) - level
				if (v0 * v1 < 0f) {
					var lo = t0
					var hi = t1
					repeat(30) {
						val mid = (lo + hi) / 2f
						if ((value(curve, mid) - level) * v0 < 0f) hi = mid else lo = mid
					}
					val t = (lo + hi) / 2f
					if (t - a.time > MotionClips.TIME_EPSILON && b.time - t > MotionClips.TIME_EPSILON) out += t
				}
				t0 = t1
				v0 = v1
			}
		}
		return out
	}

	/** [curve] with every value and Bezier control kept inside [low]..[high]. */
	fun clamped(curve: MotionCurve, low: Float, high: Float): MotionCurve {
		val lo = min(low, high)
		val hi = max(low, high)
		return curve.copy(keys = curve.keys.map { key ->
			val value = key.value.coerceIn(lo, hi)
			fun constrain(handle: MotionHandle): MotionHandle {
				val control = key.value + handle.y
				return if (value == key.value && control in lo..hi) handle else handle.copy(y = control.coerceIn(lo, hi) - value)
			}
			key.copy(
				value = value,
				inHandle = constrain(key.inHandle), outHandle = constrain(key.outHandle),
			)
		})
	}

	private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
		val u = 1f - t
		return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
	}

	private fun derivative(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
		val u = 1f - t
		return 3f * (u * u * (p1 - p0) + 2f * u * t * (p2 - p1) + t * t * (p3 - p2))
	}
}
