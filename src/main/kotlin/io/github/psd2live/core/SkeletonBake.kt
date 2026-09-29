package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/** How a pose key eases into the next one. */
enum class PoseEase { LINEAR, SMOOTH, STEPPED }

/** An IK target: the tip of [boneId] is pulled to canvas point ([x], [y]). */
data class IkTarget(val boneId: String, val x: Float, val y: Float) {
	init {
		require(boneId.isNotBlank()) { "An IK target needs a bone" }
		require(x.isFinite() && y.isFinite()) { "An IK target must be finite" }
	}
}

/**
 * A pose of the skeleton at [time]: parameter [values] for FK, and [ik] targets for the bones whose tips
 * should land on a point. A parameter a key leaves out is not keyed there, so the neighbouring keys that do
 * carry it are joined across the gap. A bone with a target on some key but not on this one has its tip where
 * this key's FK pose puts it. [ease] shapes the way to the next key.
 */
data class PoseKey(
	val time: Float,
	val values: Map<String, Float> = emptyMap(),
	val ik: List<IkTarget> = emptyList(),
	val ease: PoseEase = PoseEase.SMOOTH,
) {
	init {
		require(time.isFinite() && time >= 0f) { "A pose key needs a time from 0" }
		require(values.values.all { it.isFinite() }) { "A pose value must be finite" }
		require(ik.map { it.boneId }.distinct().size == ik.size) { "A pose key holds one IK target per bone" }
	}
}

/** What to bake: the frame rate to solve at, how far the result may stray, and which parameters and times. */
data class BakeOptions(
	val fps: Float = 30f,
	/** The most the baked curve may deviate from the solved frames, in the parameter's units (degrees for a bone). */
	val tolerance: Float = 0.5f,
	val shape: BakeShape = BakeShape.LINEAR,
	/** The parameters to bake; null bakes every parameter the keys drive. */
	val parameterIds: Set<String>? = null,
	/** The time range to bake; null bounds are the first and last pose key. */
	val start: Float? = null,
	val end: Float? = null,
) {
	init {
		require(fps.isFinite() && fps in 1f..120f) { "Bake FPS must be within 1..120" }
		require(tolerance.isFinite() && tolerance >= 0f) { "Bake tolerance must not be negative" }
		require(start == null || start.isFinite() && start >= 0f) { "Bake start must not be negative" }
		require(end == null || end.isFinite() && end >= 0f) { "Bake end must not be negative" }
	}
}

enum class BakeShape { LINEAR, BEZIER }

/** How baked curves land in a clip that already drives some of the same parameters. */
enum class BakeWrite {
	/** A baked parameter's curve is replaced whole. */
	REPLACE,

	/** A baked parameter keeps its keys outside the baked time range. */
	MERGE,
}

class BakeResult(
	val curves: List<MotionCurve>,
	val start: Float,
	val end: Float,
	/** Frames solved. */
	val frameCount: Int,
	/** Samples across all curves before thinning. */
	val sampleCount: Int,
	val keyCount: Int,
	/** The largest deviation of any curve from its solved frames. */
	val maxError: Float,
) {
	val keysByParameter: Map<String, Int> get() = curves.associate { it.parameterId to it.keys.size }
}

/**
 * Bakes a sequence of skeleton poses into parameter curves.
 *
 * Every frame of the range is solved on its own: FK values ease between the keys that carry them, then each
 * IK target, eased the same way, pulls its bone's chain, warm-started from the frame's FK pose so the chain
 * does not flip between solutions. The frames of each parameter are then thinned by [CurveSimplify] to the
 * tolerance. The rig is only read: the result is curves for a motion, not a change to the rig.
 */
internal object SkeletonBake {
	/** Frames a bake may solve, so one request cannot run away with the CPU. */
	const val MAX_FRAMES = 20_000

	/** Passes of IK per frame; each pass already runs the solver to convergence, so more only matter for long reaches. */
	private const val IK_PASSES = 3

	/** Canvas pixels the tip may miss its target by before another pass runs. */
	private const val IK_REACH = 0.25f

	fun bake(
		model: PuppetModel,
		spec: SkeletonSpec,
		keys: List<PoseKey>,
		options: BakeOptions = BakeOptions(),
		cancelled: () -> Unit = {},
	): BakeResult {
		require(spec.enabled) { "The skeleton is not enabled" }
		require(keys.isNotEmpty()) { "Baking needs a pose key" }
		val sorted = keys.sortedBy { it.time }
		require(sorted.zipWithNext().all { (a, b) -> b.time - a.time >= MotionClips.TIME_EPSILON }) { "Pose keys must be at different times" }
		val boneIds = spec.bones.mapTo(HashSet()) { it.id }
		val limbIds = SkeletonRig.limbBones(spec).mapTo(HashSet()) { it.id }
		for (target in sorted.flatMap { it.ik }) {
			require(target.boneId in boneIds) { "IK target bone not found: ${target.boneId}" }
			require(target.boneId in limbIds) { "IK needs a limb bone, not a body half: ${target.boneId}" }
		}
		val ranges = model.parameters.associate { it.id.raw to (it.min..it.max) }
		for (parameter in sorted.flatMap { it.values.keys }.distinct()) require(parameter in ranges) { "Unknown parameter: $parameter" }
		val start = options.start ?: sorted.first().time
		val end = options.end ?: sorted.last().time
		require(end >= start) { "Bake range must end after it starts" }
		val frames = ceil(((end - start) * options.fps).toDouble() - 1e-6).toInt() + 1
		require(frames <= MAX_FRAMES) { "Bake range is too long: $frames frames at ${options.fps} FPS" }
		val times = FloatArray(frames) { if (it == frames - 1) end else start + it / options.fps }

		val fkTracks = HashMap<String, MutableList<Track<Float>>>()
		val ikTracks = HashMap<String, MutableList<Track<Pair<Float, Float>>>>()
		for (key in sorted) {
			for ((parameter, value) in key.values) fkTracks.getOrPut(parameter) { ArrayList() } += Track(key.time, value, key.ease)
			for (target in key.ik) ikTracks.getOrPut(target.boneId) { ArrayList() } += Track(key.time, target.x to target.y, key.ease)
		}
		val ikOrder = ikTracks.keys.sorted()
		// A bone with a target on any key is pulled through every key: a key without one puts the tip where its
		// FK pose does, so the tip's path runs through all the keys rather than sitting on the one target.
		for (boneId in ikOrder) {
			ikTracks[boneId] = sorted.map { key ->
				key.ik.firstOrNull { it.boneId == boneId }?.let { Track(key.time, it.x to it.y, key.ease) }
					?: Track(key.time, tipAt(model, spec, boneId, fkValues(fkTracks, key.time)), key.ease)
			}.toMutableList()
		}

		val dense = LinkedHashMap<String, MutableList<Pair<Float, Float>>>()
		val carry = HashMap<ParameterId, Float>()
		for (time in times) {
			cancelled()
			val values = fkValues(fkTracks, time)
			// The chain of an IK bone that no key poses by FK keeps where the previous frame left it.
			for ((parameter, value) in carry) values.putIfAbsent(parameter, value)
			for (boneId in ikOrder) {
				val (x, y) = sample(ikTracks.getValue(boneId), time) { a, b, t -> lerp(a.first, b.first, t) to lerp(a.second, b.second, t) }
				for (pass in 0 until IK_PASSES) {
					val posed = SkeletonPoseSolver.posed(model, spec, values)
					val tip = posed.firstOrNull { it.bone.id == boneId } ?: break
					if (pass > 0 && hypot(tip.tailX - x, tip.tailY - y) <= IK_REACH) break
					val solved = SkeletonPoseSolver.drag(spec, posed, BoneHit(boneId, tip = true), x, y, values, ik = true)
					if (solved.isEmpty()) break
					values += solved
					carry += solved
				}
			}
			for ((id, value) in values) {
				val parameter = id.raw
				if (options.parameterIds != null && parameter !in options.parameterIds) continue
				val range = ranges[parameter]
				dense.getOrPut(parameter) { ArrayList(frames) } += time to (if (range != null) value.coerceIn(range) else value)
			}
		}

		val shape = if (options.shape == BakeShape.BEZIER) CurveSimplify.Shape.BEZIER else CurveSimplify.Shape.LINEAR
		var sampleCount = 0
		var keyCount = 0
		var maxError = 0f
		val curves = dense.map { (parameter, samples) ->
			sampleCount += samples.size
			val result = CurveSimplify.simplify(samples, options.tolerance, shape)
			keyCount += result.keys.size
			maxError = maxOf(maxError, result.maxError)
			MotionCurve(parameter, MotionClips.normalized(result.keys))
		}
		return BakeResult(curves, start, end, frames, sampleCount, keyCount, maxError)
	}

	/**
	 * [clip] with [result]'s curves written in. A [BakeWrite.MERGE] keeps a curve's own keys outside the baked
	 * range; a clip too short for the range grows to hold it.
	 */
	fun applyTo(clip: MotionClip, result: BakeResult, write: BakeWrite): MotionClip {
		val baked = result.curves.associateBy { it.parameterId }
		val kept = clip.curves.filterNot { it.parameterId in baked }
		val written = result.curves.map { curve ->
			val existing = clip.curve(curve.parameterId)
			if (write == BakeWrite.MERGE && existing != null) {
				val outside = existing.keys.filter { it.time < result.start - MotionClips.TIME_EPSILON || it.time > result.end + MotionClips.TIME_EPSILON }
				MotionCurve(curve.parameterId, MotionClips.normalized(outside + curve.keys))
			} else curve
		}
		return clip.copy(
			duration = maxOf(clip.duration, result.end),
			curves = kept + written,
		)
	}

	/** Every parameter the FK tracks drive, at [time]. */
	private fun fkValues(tracks: Map<String, List<Track<Float>>>, time: Float): HashMap<ParameterId, Float> {
		val values = HashMap<ParameterId, Float>()
		for ((parameter, track) in tracks) values[ParameterId(parameter)] = sample(track, time, ::lerp)
		return values
	}

	private fun tipAt(model: PuppetModel, spec: SkeletonSpec, boneId: String, values: Map<ParameterId, Float>): Pair<Float, Float> =
		SkeletonPoseSolver.posed(model, spec, values).first { it.bone.id == boneId }.let { it.tailX to it.tailY }

	/** One key of a per-parameter or per-bone track: the value at [time] and the ease into the next. */
	private class Track<T>(val time: Float, val value: T, val ease: PoseEase)

	/** The track at [time]: held before its first and after its last key, eased between them. */
	private fun <T> sample(track: List<Track<T>>, time: Float, blend: (T, T, Float) -> T): T {
		if (time <= track.first().time) return track.first().value
		if (time >= track.last().time) return track.last().value
		val next = track.indexOfFirst { it.time > time }
		val a = track[next - 1]
		val b = track[next]
		val t = (time - a.time) / (b.time - a.time)
		val eased = when (a.ease) {
			PoseEase.LINEAR -> t
			PoseEase.SMOOTH -> t * t * (3f - 2f * t)
			PoseEase.STEPPED -> 0f
		}
		return if (abs(eased) < 1e-9f) a.value else blend(a.value, b.value, eased)
	}

	private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
