package io.github.psd2live.core

import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyBreathWarpTest {
	private val character = Bounds(0f, 0f, 400f, 1000f)
	private val torso = RigBuilder.TorsoFrame(centerX = 200f, shoulderY = 250f, waistY = 450f, halfWidth = 70f)

	/** Canvas displacement of the canvas point ([x], [y]) at Body Z [z] and Breath [breath]. */
	private fun moved(x: Float, y: Float, z: Float, breath: Float): Pair<Float, Float> {
		val (u, v) = RigBuilder.bodySecondaryWarpPoint(character, torso, x / 400f, y / 1000f, z, breath, 1f)
		return (u * 400f - x) to (v * 1000f - y)
	}

	@Test fun breathLiftsTheShouldersAndWhatTheyCarryAndLeavesTheHipsAlone() {
		// Below the waist nothing moves, however deep the breath or the lean.
		for (y in listOf(450f, 600f, 1000f)) for (x in listOf(0f, 200f, 400f)) for (z in listOf(-10f, 0f, 10f)) {
			val (dx, dy) = moved(x, y, z, 1f)
			assertEquals(0f, dx, 1e-3f, "x at $x,$y z=$z")
			assertEquals(0f, dy, 1e-3f, "y at $x,$y z=$z")
		}
		// The lift grows from the waist up to the shoulders, and the head above rides with them.
		val lifts = listOf(440f, 400f, 330f, 250f).map { -moved(200f, it, 0f, 1f).second }
		assertTrue(lifts.zipWithNext().all { (a, b) -> b > a }, "lift grows toward the shoulders: $lifts")
		assertEquals(0.03f * torso.length, lifts.last(), 0.05f)
		assertEquals(moved(200f, 250f, 0f, 1f).second, moved(200f, 60f, 0f, 1f).second, 1e-3f)
		// The chest widens about the centre line, and anything beside the torso moves with its edge.
		val (left, _) = moved(150f, 310f, 0f, 1f)
		val (right, _) = moved(250f, 310f, 0f, 1f)
		assertTrue(left < 0f && right > 0f)
		assertEquals(-left, right, 1e-3f)
		assertEquals(moved(270f, 310f, 0f, 1f).first, moved(390f, 310f, 0f, 1f).first, 1e-3f)
	}

	@Test fun bodyZLeansTheUpperBodyAboutTheWaistKeepingEveryRowItsWidth() {
		// The shift grows with the height over the waist, a mirror either way, the same across a row.
		val shifts = listOf(430f, 380f, 250f, 50f).map { moved(200f, it, 10f, 0f).first }
		assertTrue(shifts.zipWithNext().all { (a, b) -> b > a }, "lean grows up the body: $shifts")
		for (y in listOf(380f, 50f)) {
			assertEquals(-moved(200f, y, 10f, 0f).first, moved(200f, y, -10f, 0f).first, 1e-3f)
			assertEquals(moved(0f, y, 10f, 0f).first, moved(400f, y, 10f, 0f).first, 1e-3f)
			assertEquals(0f, moved(0f, y, 10f, 0f).second, 1e-4f)
		}
	}

	@Test fun breathIsPlacedOnTheSampleTorsoAndPassesTheWarpAudit() {
		val preview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val frame = RigBuilder.torsoFrame(preview.analysis, preview.analysis.anchors.character, null)
		val face = preview.analysis.anchors.face
		assertTrue(frame.shoulderY > face.centerY && frame.waistY > frame.shoulderY, "shoulders below the face, waist below them")
		assertTrue(abs(frame.centerX - face.centerX) < face.width * 0.5f, "torso under the face")
		val warnings = RigIntegrityValidator.validateDirectionalWarpDimensions("tml", preview.rig.puppet)
		assertTrue(warnings.none { "DeformBodyZBreath" in it }, warnings.joinToString("\n"))
	}
}
