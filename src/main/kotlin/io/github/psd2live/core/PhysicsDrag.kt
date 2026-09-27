package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dragging a model the way Cubism's samples do: the pointer sets a target in -1..1, which the face follows
 * with Cubism's speed- and acceleration-limited CubismTargetPoint, and CubismLook turns the followed point
 * into parameter offsets. Letting go returns the target to 0 and the point eases back at the same limits.
 */
class PhysicsDrag {
	var x = 0f
		private set
	var y = 0f
		private set
	private var targetX = 0f
	private var targetY = 0f
	private var vx = 0f
	private var vy = 0f
	private var userTime = 0f
	private var lastTime = 0f

	fun target(x: Float, y: Float) {
		targetX = x.coerceIn(-1f, 1f)
		targetY = y.coerceIn(-1f, 1f)
	}

	fun release() = target(0f, 0f)

	val idle: Boolean get() = targetX == 0f && targetY == 0f && abs(x) <= EPSILON && abs(y) <= EPSILON

	fun reset() {
		x = 0f; y = 0f; targetX = 0f; targetY = 0f; vx = 0f; vy = 0f; userTime = 0f; lastTime = 0f
	}

	/** CubismTargetPoint::Update. */
	fun update(dt: Float) {
		userTime += dt
		if (lastTime == 0f) { lastTime = userTime; return }
		val weight = (userTime - lastTime) * FRAME_RATE
		lastTime = userTime
		val maxA = weight * MAX_V / (TIME_TO_MAX_SPEED * FRAME_RATE)
		val dx = targetX - x
		val dy = targetY - y
		if (abs(dx) <= EPSILON && abs(dy) <= EPSILON) return
		val d = sqrt(dx * dx + dy * dy)
		var ax = MAX_V * dx / d - vx
		var ay = MAX_V * dy / d - vy
		val a = sqrt(ax * ax + ay * ay)
		if (a > maxA) { ax *= maxA / a; ay *= maxA / a }
		vx += ax
		vy += ay
		// The speed from which the limited deceleration still stops at the target (Cubism's formula, t = 1).
		val maxV = 0.5f * (sqrt(maxA * maxA + 16f * maxA * d - 8f * maxA * d) - maxA)
		val v = sqrt(vx * vx + vy * vy)
		if (v > maxV) { vx *= maxV / v; vy *= maxV / v }
		x += vx
		y += vy
	}

	/**
	 * The offsets the followed point adds to [parameters]: CubismLook's head, body and eye factors from the
	 * samples, and for any other parameter the horizontal drag across half its range.
	 */
	fun offsets(parameters: Collection<String>, ranges: Map<String, PhysicsEngine.Range>): Map<String, Float> =
		parameters.mapNotNull { p ->
			val look = LOOK[p]
			val range = ranges[p] ?: return@mapNotNull null
			p to (look?.let { (fx, fy, fxy) -> fx * x + fy * y + fxy * x * y } ?: (x * (range.max - range.min) / 2f))
		}.toMap()

	/** [base] with the drag added and clamped, as Cubism's AddParameterValue does. */
	fun apply(base: Map<String, Float>, parameters: Collection<String>, ranges: Map<String, PhysicsEngine.Range>): Map<String, Float> {
		if (x == 0f && y == 0f) return base
		val out = base.toMutableMap()
		for ((p, delta) in offsets(parameters, ranges)) {
			val range = ranges.getValue(p)
			out[p] = ((base[p] ?: range.default) + delta).coerceIn(minOf(range.min, range.max), maxOf(range.min, range.max))
		}
		return out
	}

	companion object {
		private const val FRAME_RATE = 30f
		private const val EPSILON = 0.01f
		private const val MAX_V = 40f / 10f / FRAME_RATE
		private const val TIME_TO_MAX_SPEED = 0.15f

		/** CubismLook's factors in the SDK samples: (x, y, xy) per parameter. */
		private val LOOK = mapOf(
			"ParamAngleX" to Triple(30f, 0f, 0f),
			"ParamAngleY" to Triple(0f, 30f, 0f),
			"ParamAngleZ" to Triple(0f, 0f, -30f),
			"ParamBodyAngleX" to Triple(10f, 0f, 0f),
			"ParamEyeBallX" to Triple(1f, 0f, 0f),
			"ParamEyeBallY" to Triple(0f, 1f, 0f),
		)
	}
}
