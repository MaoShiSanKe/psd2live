package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The body a skeleton draws, as the motions read it: where each arm points at rest, how far a human
 * joint would comfortably go from there, and the proportions that set how big and how lively a motion
 * should be.
 *
 * A bone's own limits are deliberately wide (see [BoneRole]) so the pose tool never stops short. These
 * are not: they are what keeps a generated motion inside what a person does. Angles are elevations,
 * degrees from hanging straight down, positive away from the body on either side, which is also the
 * sense of every limb parameter - so a parameter value is the change in elevation it makes.
 */
internal class SkeletonAnatomy private constructor(val spec: SkeletonSpec) {
	/** One arm: the elevations its bones are drawn at, and their lengths. */
	class Arm(val side: Side, val upper: SkeletonBone, val fore: SkeletonBone, val hand: SkeletonBone?) {
		val restUpper: Float = elevation(upper)
		val restFore: Float = elevation(fore)
		val restHand: Float = hand?.let(::elevation) ?: restFore
		val shoulderX: Float get() = upper.headX
		val shoulderY: Float get() = upper.headY

		/** Which way an elevation turns on the canvas: +1 when away from the body is toward -x. */
		val outward: Float = if (upper.direction > 0f) -1f else 1f

		/** The elbow and the wrist with the upper arm at elevation [upperE] and the forearm at [foreE], canvas pixels. */
		fun reach(upperE: Float, foreE: Float): FloatArray {
			val ex = shoulderX + outward * sin(rad(upperE)) * upper.length
			val ey = shoulderY + cos(rad(upperE)) * upper.length
			return floatArrayOf(ex, ey, ex + outward * sin(rad(foreE)) * fore.length, ey + cos(rad(foreE)) * fore.length)
		}
	}

	val arms: List<Arm>
	val legs: List<SkeletonRig.Leg> = SkeletonRig.legs(spec)
	private val head: SkeletonBone? = spec.bones.firstOrNull { it.role == BoneRole.HEAD }

	/** The head's height in canvas pixels, from the mouth-line pivot to the crown it reaches. */
	val headHeight: Float

	/** Canvas y of the crown, of the eyes and of the ground the feet stand on (null without legs). */
	val crownY: Float
	val eyeY: Float
	val groundY: Float?

	/** Canvas x of the face's centre line. */
	val faceX: Float

	/** Canvas x of the body's centre line, and canvas y of the waist. */
	val midX: Float
	val waistY: Float

	/**
	 * How chibi the figure is, 0 for ordinary proportions (six heads or more) to 1 for three heads or
	 * fewer. Small bodies move with more bounce and bigger relative gestures.
	 */
	val chibi: Float

	init {
		val bones = SkeletonRig.limbBones(spec)
		arms = listOf(Side.RIGHT, Side.LEFT).mapNotNull { side ->
			val upper = bones.firstOrNull { it.role == BoneRole.UPPER_ARM && it.side == side } ?: return@mapNotNull null
			val fore = bones.firstOrNull { it.role == BoneRole.FOREARM && it.parentId == upper.id } ?: return@mapNotNull null
			val hand = bones.firstOrNull { it.role == BoneRole.HAND && it.parentId == fore.id }
			Arm(side, upper, fore, hand)
		}
		val torso = spec.bones.firstOrNull { it.role == BoneRole.UPPER_BODY }
		val torsoLength = torso?.length ?: arms.firstOrNull()?.upper?.length?.times(1.6f) ?: 100f
		// The face bone runs from the mouth line up to the top of the face, about two thirds of the head.
		headHeight = head?.let { hypot(it.tailX - it.headX, it.tailY - it.headY) * 1.5f }?.takeIf { it > 1f } ?: (torsoLength * 0.9f)
		crownY = head?.let { minOf(it.headY, it.tailY) - headHeight * 0.25f } ?: ((torso?.tailY ?: 0f) - headHeight)
		eyeY = head?.let { it.headY - (it.headY - it.tailY) * 0.45f } ?: (crownY + headHeight * 0.5f)
		faceX = head?.headX ?: torso?.headX ?: 0f
		midX = torso?.headX ?: faceX
		// The upper body bone rises from the waist; without one, the waist is about an upper arm and a half
		// below the shoulders, and never above the elbows.
		val shoulderY = arms.takeIf { it.isNotEmpty() }?.map { it.shoulderY }?.average()?.toFloat() ?: (crownY + headHeight * 1.2f)
		val upperArm = arms.firstOrNull()?.upper?.length ?: (headHeight * 0.6f)
		waistY = (torso?.let { maxOf(it.headY, it.tailY) } ?: (shoulderY + upperArm * 1.5f)).coerceAtLeast(shoulderY + upperArm * 0.8f)
		groundY = legs.takeIf { it.isNotEmpty() }?.maxOf { leg ->
			maxOf(leg.ankleY.toFloat(), leg.foot?.let { maxOf(it.headY, it.tailY) } ?: leg.ankleY.toFloat())
		}
		val tall = groundY?.let { (it - crownY) / headHeight }
			?: (arms.firstOrNull()?.let { (it.shoulderY - crownY) / headHeight * 3.2f } ?: 6f)
		chibi = ((6f - tall) / 3f).coerceIn(0f, 1f)
	}

	/** The arm on [side], or the other one when that side has none. */
	fun arm(side: Side): Arm? = arms.firstOrNull { it.side == side } ?: arms.firstOrNull()

	/** Average leg length in pixels, or null for a figure without legs. */
	val legLength: Float? = legs.takeIf { it.isNotEmpty() }?.map { it.reach.toFloat() }?.average()?.toFloat()

	companion object {
		fun of(spec: SkeletonSpec?): SkeletonAnatomy? = spec?.takeIf { it.enabled }?.let(::SkeletonAnatomy)

		/** Elevation of [bone] as drawn: degrees from pointing straight down, positive away from the body. */
		fun elevation(bone: SkeletonBone): Float {
			val heading = SkeletonIk.heading((bone.tailX - bone.headX).toDouble(), (bone.tailY - bone.headY).toDouble())
			return (SkeletonIk.wrap(heading - 90.0) * bone.direction).toFloat()
		}

		/** Comfortable elevations of the upper arm. */
		val UPPER_ARM_COMFORT = -30f..115f

		/**
		 * Comfortable elevations of the forearm: anywhere from across the body to straight up, and a little
		 * past upright for a waving hand, but not folded over toward the head.
		 */
		val FOREARM_COMFORT = -110f..185f

		/** Comfortable turn of the wrist from the forearm, either way. */
		const val WRIST_COMFORT = 35f

		/**
		 * How far the elbow comfortably bends away from straight with the upper arm at [upperE]. Folding
		 * outward and up is always open - a hand raised by the shoulder with the elbow down is as natural
		 * as one waved overhead - but folding inward closes as the arm rises: a raised arm whose forearm
		 * drops back down past straight reads as a joint bent the wrong way.
		 */
		fun elbowComfort(upperE: Float): ClosedFloatingPointRange<Float> {
			val e = upperE.coerceIn(-30f, 180f)
			val inward = (125f - 1.2f * max(e, 0f)).coerceIn(10f, 125f)
			return -inward..150f
		}

		/**
		 * How far the arm whose bones sit at elevations [upperE], [foreE] and [handE] is past what a person
		 * does comfortably, in degrees; zero when it is inside.
		 */
		fun armStrain(upperE: Float, foreE: Float, handE: Float): Float {
			fun past(value: Float, range: ClosedFloatingPointRange<Float>) =
				max(0f, range.start - value) + max(0f, value - range.endInclusive)
			val elbow = SkeletonIk.wrap((foreE - upperE).toDouble()).toFloat()
			val wrist = SkeletonIk.wrap((handE - foreE).toDouble()).toFloat()
			// Read on (-135, 225] so a forearm just past upright is not taken for one pointing down.
			val fore = SkeletonIk.wrap(foreE - 45.0).toFloat() + 45f
			return past(upperE, UPPER_ARM_COMFORT) + past(fore, FOREARM_COMFORT) + past(elbow, elbowComfort(upperE)) +
				max(0f, abs(wrist) - WRIST_COMFORT)
		}

		private fun rad(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()
	}
}
