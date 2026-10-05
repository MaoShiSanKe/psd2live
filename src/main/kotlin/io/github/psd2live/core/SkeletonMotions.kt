package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A parameter and the curve a motion drives it along. */
typealias MotionTrack = MotionCurve

/**
 * The body motions: the idle, and the one-shots written against the skeleton's poses.
 *
 * Every skeleton preset is its pose parameter moving (see [SkeletonPoses]): the rig already says what a
 * crouch or a tail swing does to each joint and each mesh, so a motion only says when. Besides the body
 * parameters and the poses the idle sways the upper body and lets the arms hang loose on their own
 * parameters, short of any physics drives; nothing writes geometry, so preview and export describe the
 * same motion.
 *
 * Curves are sparse eased keys (see [MotionCurveMath]); the gestures that read the figure's own body are
 * worked out by [MotionSynth].
 */
object SkeletonMotions {
	const val IDLE_DURATION = MotionSynth.IDLE_DURATION
	const val TAIL_SWING_DURATION = 3f
	const val CROUCH_DURATION = MotionSynth.CROUCH_DURATION
	const val WEIGHT_SHIFT_DURATION = MotionSynth.WEIGHT_SHIFT_DURATION
	const val SHY_DURATION = MotionSynth.SHY_DURATION
	const val WAVE_DURATION = MotionSynth.WAVE_DURATION
	const val HEAD_TILT_DURATION = MotionSynth.HEAD_TILT_DURATION
	const val CHEER_DURATION = MotionSynth.CHEER_DURATION
	const val LEG_KICK_DURATION = MotionSynth.LEG_KICK_DURATION
	const val SWAY_DURATION = MotionSynth.SWAY_DURATION

	/**
	 * A preset written against the skeleton's poses. A [loop] preset is another idle: the export puts it in
	 * the idle group beside the plain one, and the preview plays one cycle of it.
	 */
	class Preset(val name: String, val loop: Boolean = false, val tracks: (SkeletonSpec?) -> List<MotionTrack>)

	/** Every skeleton preset, in the order the panel lists and the export writes them. */
	val presets: List<Preset> = listOf(
		Preset("TailSwing", tracks = ::tailSwing),
		Preset("Crouch", tracks = ::crouch),
		Preset("WeightShift", tracks = ::weightShift),
		Preset("Shy", tracks = ::shy),
		Preset("Wave", tracks = ::wave),
		Preset("HeadTilt", tracks = ::headTilt),
		Preset("Cheer", tracks = ::cheer),
		Preset("LegKick", tracks = ::legKick),
		Preset("Sway", tracks = ::sway),
		Preset("IdleCute", loop = true) { idleCute(it) },
	)

	/**
	 * The resting idle (see [MotionSynth.idle]): breathing, the weight drifting from foot to foot and the
	 * arms hanging at ease, every part a little behind the one that carries it.
	 *
	 * The two leg poses never play together: each is solved with the other at rest, and their shapes only
	 * add, so a crouch on a shifted weight would bend the knees too far and slide the feet. The idle keeps
	 * to the weight, and each leg one-shot holds the other leg pose at rest while it plays.
	 */
	fun idle(spec: SkeletonSpec?, exclude: Set<String> = emptySet()): List<MotionTrack> = played(spec, MotionSynth.idle(spec, exclude))

	/** A deliberate tail swing, faster and wider than the one folded into the idle: [cycles] swings of [amplitude]. */
	fun tailSwing(spec: SkeletonSpec?, amplitude: Float = 1f, cycles: Int = 3): List<MotionTrack> =
		if (SkeletonPoses.tailSwing !in SkeletonPoses.available(spec)) emptyList()
		else listOf(sine(SkeletonPoses.tailSwing.id.raw, amplitude, cycles.coerceAtLeast(1), phase = 0f,
			duration = TAIL_SWING_DURATION * cycles.coerceAtLeast(1) / 3f))

	/** A dip and a recovery, worked out for the figure (see [MotionSynth.crouch]). */
	fun crouch(spec: SkeletonSpec?): List<MotionTrack> =
		synthesized(spec, CROUCH_DURATION, setOf(SkeletonPoses.crouch), MotionSynth.crouch(spec))

	/** A shift onto one foot and back (see [MotionSynth.weightShift]). */
	fun weightShift(spec: SkeletonSpec?): List<MotionTrack> =
		synthesized(spec, WEIGHT_SHIFT_DURATION, setOf(SkeletonPoses.weight), MotionSynth.weightShift(spec))

	/** Knees in, hands together, the head ducked and tilted away (see [MotionSynth.shy]). */
	fun shy(spec: SkeletonSpec?): List<MotionTrack> =
		synthesized(spec, SHY_DURATION, setOf(SkeletonPoses.kneesIn, SkeletonPoses.arms), MotionSynth.shy(spec))

	/** A hand raised beside the face and waved, worked out for the figure (see [MotionSynth.wave]). */
	fun wave(spec: SkeletonSpec?): List<MotionTrack> = synthesized(spec, WAVE_DURATION, emptySet(), MotionSynth.wave(spec))

	/** A big head tilt held for a beat (see [MotionSynth.headTilt]). */
	fun headTilt(spec: SkeletonSpec?): List<MotionTrack> =
		synthesized(spec, HEAD_TILT_DURATION, setOf(SkeletonPoses.weight, SkeletonPoses.arms), MotionSynth.headTilt(spec))

	/** Two little hops with the arms thrown up (see [MotionSynth.cheer]), the tail and the wings going. */
	fun cheer(spec: SkeletonSpec?): List<MotionTrack> = synthesized(spec, CHEER_DURATION, setOf(SkeletonPoses.crouch, SkeletonPoses.arms),
		MotionSynth.cheer(spec) + listOf(
			sine(SkeletonPoses.tailSwing.id.raw, amplitude = 0.8f, cycles = 3, phase = 0f, duration = CHEER_DURATION),
			sine(SkeletonPoses.wingFlap.id.raw, amplitude = 1f, cycles = 4, phase = 0f, duration = CHEER_DURATION),
		))

	/** One foot kicked up behind, the arms out for balance (see [MotionSynth.legKick]). */
	fun legKick(spec: SkeletonSpec?): List<MotionTrack> =
		synthesized(spec, LEG_KICK_DURATION, setOf(SkeletonPoses.legLift), MotionSynth.legKick(spec))

	/** A happy side-to-side sway, bigger than the idle's (see [MotionSynth.sway]). */
	fun sway(spec: SkeletonSpec?): List<MotionTrack> =
		synthesized(spec, SWAY_DURATION, setOf(SkeletonPoses.weight, SkeletonPoses.armSway), MotionSynth.sway(spec))

	/** The [crouch] and the like: [tracks] played as a one-shot, nothing when the synthesizer had nothing to move. */
	private fun synthesized(spec: SkeletonSpec?, duration: Float, needs: Set<SkeletonPose>, tracks: List<MotionTrack>): List<MotionTrack> =
		if (tracks.isEmpty()) emptyList() else oneShotOf(spec, duration, needs, *tracks.toTypedArray())

	/**
	 * The idle standing knock-kneed with the hands drawn a little together (see [MotionSynth.tucked]). The
	 * knees hold [SkeletonPoses.kneesIn], so the weight cycle - another leg pose - drops out and the body
	 * tracks carry the sway; the arms still hang loose about the tucked pose.
	 */
	fun idleCute(spec: SkeletonSpec?, exclude: Set<String> = emptySet(), tuck: Float = 0.3f): List<MotionTrack> {
		val available = SkeletonPoses.available(spec)
		if (SkeletonPoses.kneesIn !in available) return emptyList()
		val hold = available.filter { it.legs }.associate { it.id.raw to if (it == SkeletonPoses.kneesIn) tuck else 0f } +
			MotionSynth.tucked(spec, tuck)
		return played(spec, MotionSynth.idle(spec, exclude, hold))
	}

	/**
	 * A one-shot of [tracks] across [duration], empty unless [spec] can play one of the poses it [needs]
	 * (any, when it needs none). Tracks of poses [spec] cannot play are dropped, and every leg pose the
	 * motion leaves out is held at rest: the leg poses only add, so two of them never play together.
	 */
	private fun oneShotOf(spec: SkeletonSpec?, duration: Float, needs: Set<SkeletonPose>, vararg tracks: MotionTrack): List<MotionTrack> {
		val available = SkeletonPoses.available(spec)
		if (needs.isNotEmpty() && available.none { it in needs }) return emptyList()
		val playable = available.mapTo(HashSet()) { it.id.raw }
		val kept = tracks.filter { track -> SkeletonPoses.all.none { it.id.raw == track.parameterId } || track.parameterId in playable }
		val held = available.filter { pose -> pose.legs && kept.none { it.parameterId == pose.id.raw } }
			.map { MotionCurveMath.linear(it.id.raw, listOf(0f to 0f, duration to 0f)) }
		return played(spec, (kept + held).clamped())
	}

	/**
	 * [tracks] as the rig plays them: every gesture, and the limb turns of a rig pose, moved onto the
	 * bones' own parameters and added to whatever the motion already writes there (see [SkeletonPoses]).
	 *
	 * Each pose turns a bone linearly between two of its keys, and every segment of a generated curve is a
	 * cubic in time, so between the keys of every track and the moments a pose crosses one of its keys,
	 * each bone curve is a cubic too. A bone takes a key at each of those times, with the value and the
	 * slopes either side that the poses and its own track give it there, and follows them exactly.
	 */
	internal fun played(spec: SkeletonSpec?, tracks: List<MotionTrack>): List<MotionTrack> {
		if (spec?.enabled != true) return tracks
		val poses = tracks.mapNotNull { track -> SkeletonPoses.all.firstOrNull { it.id.raw == track.parameterId }?.let { it to track } }
		val gestures = poses.filterNot { it.first.rig }.mapTo(HashSet()) { it.first.id.raw }
		val bones = SkeletonRig.limbBones(spec)
		val parameterOf = bones.associate { it.id to it.parameterId }
		// The bone parameters each pose turns; a pose held at rest turns none.
		val reachedBy = poses.associate { (pose, curve) ->
			pose to if (curve.keys.all { it.value == 0f }) emptySet()
			else pose.keys.flatMap { SkeletonPoses.gestureTurns(spec, pose, it).keys }.mapNotNullTo(HashSet()) { parameterOf[it] }
		}
		val reached = poses.flatMapTo(LinkedHashSet()) { (pose) ->
			pose.keys.flatMap { SkeletonPoses.gestureTurns(spec, pose, it).keys }.mapNotNull { parameterOf[it] }
		}
		val direct = tracks.filter { it.parameterId in reached }.associateBy { it.parameterId }

		// Bone parameter turn per unit of the pose around [value], by parameter; none past the pose's ends.
		fun gain(pose: SkeletonPose, value: Float): Map<String, Float> {
			if (value <= pose.keys.first() || value >= pose.keys.last()) return emptyMap()
			val (a, b) = SkeletonPoses.bracket(pose, value).map { it.first }
			if (b <= a) return emptyMap()
			val at = SkeletonPoses.gestureTurns(spec, pose, a)
			val bt = SkeletonPoses.gestureTurns(spec, pose, b)
			return (at.keys + bt.keys).mapNotNull { boneId ->
				parameterOf[boneId]?.let { it to ((bt[boneId] ?: 0f) - (at[boneId] ?: 0f)) / (b - a) }
			}.toMap()
		}

		val turned = reached.mapNotNull { id ->
			val moving = poses.filter { id in reachedBy.getValue(it.first) }
			val own = direct[id]
			// The bone's curve is a cubic between its own keys and the keys and key crossings of the poses that turn it.
			val raw = sortedSetOf<Float>()
			own?.keys?.forEach { raw += it.time }
			for ((pose, curve) in moving) {
				for (key in curve.keys) raw += key.time
				for (level in pose.keys) raw += MotionCurveMath.crossings(curve, level)
			}
			if (raw.isEmpty()) return@mapNotNull null
			val times = ArrayList<Float>()
			for (time in raw) if (times.isEmpty() || time - times.last() > MotionClips.TIME_EPSILON) times += time
			val knots = times.mapIndexed { i, time ->
				var value = 0f
				var slopeIn = 0f
				var slopeOut = 0f
				own?.let {
					value += MotionCurveMath.value(it, time)
					slopeIn += MotionCurveMath.slope(it, time, after = false)
					slopeOut += MotionCurveMath.slope(it, time, after = true)
				}
				for ((pose, curve) in moving) {
					val turns = SkeletonPoses.turnsAt(spec, pose, MotionCurveMath.value(curve, time), SkeletonPoses::gestureTurns)
					for ((boneId, turn) in turns) if (parameterOf[boneId] == id) value += turn
					if (i > 0) {
						val mid = MotionCurveMath.value(curve, (times[i - 1] + time) / 2f)
						slopeIn += (gain(pose, mid)[id] ?: 0f) * MotionCurveMath.slope(curve, time, after = false)
					}
					if (i < times.lastIndex) {
						val mid = MotionCurveMath.value(curve, (time + times[i + 1]) / 2f)
						slopeOut += (gain(pose, mid)[id] ?: 0f) * MotionCurveMath.slope(curve, time, after = true)
					}
				}
				MotionCurveMath.Knot(time, value, slopeIn, slopeOut)
			}
			val bone = bones.first { it.parameterId == id }
			MotionCurveMath.clamped(MotionCurveMath.curve(id, knots), bone.minAngle, bone.maxAngle)
		}
		return tracks.filterNot { it.parameterId in gestures || it.parameterId in reached } + turned
	}

	private class PreparedIdle(val spec: SkeletonSpec?, val settings: MotionPresetSettings, val tracks: List<MotionTrack>)

	@Volatile private var preparedIdle: PreparedIdle? = null

	/**
	 * The values the idle, tuned by [settings], holds at [elapsed] seconds, body parameters included. The
	 * preview evaluates the same tracks the export writes; it blinks on its own clock, so the idle's blink
	 * stays out.
	 */
	fun liveIdle(spec: SkeletonSpec?, elapsed: Double, settings: MotionPresetSettings = MotionPresetSettings()): Map<ParameterId, Float> {
		// Building a skeleton idle expands pose tracks into joint curves. It depends only on the
		// immutable skeleton and the settings, while this sampler runs on every preview frame.
		val cached = preparedIdle
		val tracks = if (cached != null && cached.spec === spec && cached.settings == settings) cached.tracks else synchronized(this) {
			val current = preparedIdle
			if (current != null && current.spec === spec && current.settings == settings) current.tracks else
				MotionPresets.tracks("Idle", spec, settings.copy(values = settings.values + (MotionPresets.BLINK to 0f)))
					.also { preparedIdle = PreparedIdle(spec, settings, it) }
		}
		return tracks.associate { ParameterId(it.parameterId) to sample(it, elapsed, loop = true) }
	}

	/** The values a one-shot motion holds [elapsed] seconds in; null once it has finished. */
	fun oneShot(tracks: List<MotionTrack>, elapsed: Double): Map<ParameterId, Float>? {
		val duration = tracks.maxOfOrNull { it.keys.last().time } ?: return null
		if (elapsed > duration) return null
		return tracks.associate { ParameterId(it.parameterId) to sample(it, elapsed, loop = false) }
	}

	/** Every key inside the range of the pose it drives. */
	private fun List<MotionTrack>.clamped(): List<MotionTrack> = map { track ->
		val pose = SkeletonPoses.all.firstOrNull { it.id.raw == track.parameterId } ?: return@map track
		MotionCurveMath.clamped(track, pose.min, pose.max)
	}

	/**
	 * A sine as eased keys four to a cycle, each with the sine's own slope, so the cubic between them
	 * follows it to a fraction of a percent. [cycles] whole cycles across [duration] close the loop exactly.
	 */
	private fun sine(
		parameterId: String,
		amplitude: Float,
		cycles: Int,
		phase: Float,
		bias: Float = 0f,
		duration: Float = IDLE_DURATION,
	): MotionTrack {
		val count = (4 * cycles).coerceAtLeast(4)
		val omega = 2.0 * PI * cycles / duration
		val knots = (0..count).map { index ->
			val time = duration * index / count
			val angle = omega * time + phase
			MotionCurveMath.Knot(time, bias + amplitude * sin(angle).toFloat(), (amplitude * omega * cos(angle)).toFloat())
		}
		return MotionCurveMath.curve(parameterId, knots)
	}

	/** The value [curve] holds at [elapsed] seconds, optionally looping over its length. */
	internal fun sample(curve: MotionTrack, elapsed: Double, loop: Boolean): Float {
		val duration = curve.keys.last().time
		return if (loop) MotionCurveMath.looped(curve, elapsed, duration)
		else MotionCurveMath.value(curve, elapsed.toFloat().coerceIn(0f, duration))
	}
}
