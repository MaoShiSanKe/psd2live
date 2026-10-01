package io.github.psd2live.core

import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class MotionSynthTest {
	private fun bone(id: String, parent: String?, role: BoneRole, hx: Float, hy: Float, tx: Float, ty: Float, side: Side, meshes: List<String> = emptyList()) =
		SkeletonBone(id, id, parent, role, side, hx, hy, tx, ty, meshes, direction = if (hx < 250f) 1f else -1f)

	/** A standing figure about seven heads tall, arms hanging a little away from the body. */
	private fun figure(chibi: Boolean = false): SkeletonSpec {
		val s = if (chibi) 0.45f else 1f
		val shoulderY = 160f
		fun y(v: Float) = shoulderY + (v - shoulderY) * s
		fun arm(tag: String, side: Side, x: Float, out: Float) = listOf(
			bone("upper_$tag", "chest", BoneRole.UPPER_ARM, x, shoulderY, x + out, y(270f), side, listOf("arm_$tag")),
			bone("fore_$tag", "upper_$tag", BoneRole.FOREARM, x + out, y(270f), x + out * 1.6f, y(370f), side),
			bone("hand_$tag", "fore_$tag", BoneRole.HAND, x + out * 1.6f, y(370f), x + out * 1.8f, y(410f), side),
		)
		fun leg(tag: String, side: Side, x: Float) = listOf(
			bone("thigh_$tag", "hip", BoneRole.THIGH, x, 330f, x, y(470f).coerceAtLeast(400f), side, listOf("leg_$tag")),
			bone("shin_$tag", "thigh_$tag", BoneRole.SHIN, x, y(470f).coerceAtLeast(400f), x, y(610f).coerceAtLeast(470f), side),
		)
		val headSize = if (chibi) 70f else 40f
		return SkeletonSpec(bones = listOf(
			bone("chest", null, BoneRole.UPPER_BODY, 250f, 320f, 250f, 160f, Side.NONE, listOf("torso")),
			bone("hip", null, BoneRole.LOWER_BODY, 250f, 320f, 250f, 360f, Side.NONE, listOf("skirt")),
			bone("head", "chest", BoneRole.HEAD, 250f, 120f, 250f, 120f - headSize, Side.NONE),
		) + arm("r", Side.RIGHT, 210f, -12f) + arm("l", Side.LEFT, 290f, 12f) + leg("r", Side.RIGHT, 228f) + leg("l", Side.LEFT, 272f))
	}

	/** Every arm of [spec] inside comfortable human limits at every frame of [tracks]. */
	private fun assertComfortable(spec: SkeletonSpec, tracks: List<MotionCurve>, label: String, slack: Float = 0.5f) {
		val anatomy = SkeletonAnatomy.of(spec)!!
		val byId = tracks.associateBy { it.parameterId }
		val duration = tracks.maxOf { it.keys.last().time }
		for (arm in anatomy.arms) {
			fun at(bone: SkeletonBone?, time: Float) = bone?.let { b -> byId[b.parameterId]?.let { MotionCurveMath.value(it, time) } } ?: 0f
			var worst = 0f
			var worstAt = 0f
			for (step in 0..200) {
				val time = duration * step / 200f
				val u = at(arm.upper, time)
				val f = at(arm.fore, time)
				val h = at(arm.hand, time)
				val strain = SkeletonAnatomy.armStrain(arm.restUpper + u, arm.restFore + u + f, arm.restHand + u + f + h)
				if (strain > worst) { worst = strain; worstAt = time }
			}
			assertTrue(worst <= slack, "$label ${arm.side} arm strains ${"%.1f".format(worst)} degrees at ${"%.2f".format(worstAt)}s")
		}
	}

	private fun assertSparseAndAtRest(tracks: List<MotionCurve>, label: String) {
		for (track in tracks) {
			assertTrue(track.keys.size <= 24, "$label ${track.parameterId} has ${track.keys.size} keys")
			assertTrue(abs(track.keys.first().value) < 1e-3f && abs(track.keys.last().value) < 1e-3f, "$label ${track.parameterId} starts or ends away from rest")
		}
	}

	@Test fun waveRaisesTheHandBesideTheFaceWithinComfortableLimits() {
		for (chibi in listOf(false, true)) {
			val spec = figure(chibi)
			val anatomy = SkeletonAnatomy.of(spec)!!
			val arm = anatomy.arm(Side.RIGHT)!!
			val reach = MotionSynth.waveReach(anatomy, arm)!!
			val p = arm.reach(reach.upper, reach.fore)
			// Up by the face, clear of it, not flung over the head.
			assertTrue(p[3] < arm.shoulderY, "chibi=$chibi hand below the shoulder")
			assertTrue(p[3] > anatomy.crownY, "chibi=$chibi hand above the crown")
			assertTrue(abs(p[2] - anatomy.faceX) > anatomy.headHeight * 0.4f, "chibi=$chibi hand over the face")
			val tracks = SkeletonMotions.wave(spec)
			assertTrue(tracks.isNotEmpty())
			assertComfortable(spec, tracks, "wave chibi=$chibi")
			assertSparseAndAtRest(tracks, "wave chibi=$chibi")
		}
	}

	@Test fun crouchBringsTheArmsInWithoutStrainAndEndsAtRest() {
		val spec = figure()
		val tracks = SkeletonMotions.crouch(spec)
		assertTrue(tracks.any { it.parameterId == SkeletonPoses.crouch.id.raw })
		// The body leans forward as the hips drop, so it is not a puppet sinking straight down.
		val bodyY = tracks.single { it.parameterId == StandardParameters.BODY_Y.raw }
		assertTrue(MotionCurveMath.value(bodyY, 0.6f) < -2f)
		assertComfortable(spec, tracks, "crouch")
		assertSparseAndAtRest(tracks, "crouch")
	}

	@Test fun everyPresetStaysComfortableSparseAndEndsAtRest() {
		for (chibi in listOf(false, true)) {
			val spec = figure(chibi)
			for (preset in SkeletonMotions.presets) {
				val tracks = preset.tracks(spec)
				if (preset.name != "TailSwing") assertTrue(tracks.isNotEmpty(), "${preset.name} chibi=$chibi")
				if (tracks.isEmpty()) continue
				assertComfortable(spec, tracks, "${preset.name} chibi=$chibi")
				if (!preset.loop) assertSparseAndAtRest(tracks, "${preset.name} chibi=$chibi")
			}
		}
	}

	@Test fun shyBringsTheHandsTogetherAndCheerThrowsThemUp() {
		val spec = figure()
		val anatomy = SkeletonAnatomy.of(spec)!!
		for (arm in anatomy.arms) {
			val tuck = MotionSynth.tuckReach(anatomy, arm)!!
			val hand = arm.reach(tuck.upper, tuck.fore)
			assertTrue(abs(hand[2] - anatomy.midX) < abs(arm.shoulderX - anatomy.midX) * 0.6f, "${arm.side} hand toward the middle")
			assertTrue(hand[3] > arm.shoulderY && hand[3] < anatomy.waistY + 20f, "${arm.side} hand at the belly")
			val cheer = MotionSynth.cheerReach(anatomy, arm)!!
			val up = arm.reach(cheer.upper, cheer.fore)
			assertTrue(up[3] < anatomy.eyeY, "${arm.side} hand up by the head")
			assertTrue(abs(up[2] - anatomy.faceX) > anatomy.headHeight * 0.4f, "${arm.side} hand clear of the face")
		}
		// The hop leaves the ground fast and lands fast: the flight is a parabola, not an eased float.
		val hop = MotionSynth.cheer(spec).single { it.parameterId == SkeletonPoses.hop.id.raw }
		assertTrue(MotionCurveMath.slope(hop, 0.42f, after = true) > 4f)
		assertTrue(MotionCurveMath.slope(hop, 0.66f, after = false) < -4f)
		assertTrue(MotionCurveMath.value(hop, 0.47f) > 0.15f)
	}

	@Test fun generatedMotionsReadTheSampleFigure() {
		val initial = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val spec = SkeletonAutoBuilder.build(initial.analysis, initial.rig)
		val anatomy = SkeletonAnatomy.of(spec)!!
		for (arm in anatomy.arms) {
			println("tml ${arm.side}: rest upper=${arm.restUpper} fore=${arm.restFore} hand=${arm.restHand} " +
				"wave=${MotionSynth.waveReach(anatomy, arm)?.let { "${it.upper}/${it.fore}" }}")
		}
		println("tml head=${anatomy.headHeight} chibi=${anatomy.chibi} crown=${anatomy.crownY} eye=${anatomy.eyeY} ground=${anatomy.groundY}")
		for (preset in SkeletonMotions.presets) {
			val tracks = preset.tracks(spec)
			if (tracks.isEmpty()) continue
			assertComfortable(spec, tracks, "tml ${preset.name}")
			if (!preset.loop) assertSparseAndAtRest(tracks, "tml ${preset.name}")
		}
	}
}
