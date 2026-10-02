package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionPresetsTest {
	private fun settings(vararg values: Pair<String, Float>) = MotionPresetSettings(values.toMap())

	@Test fun defaultSettingsPlayTheGeneratedMotions() {
		assertEquals(MotionGenerator.nodTracks, MotionPresets.tracks("Nod", null))
		assertEquals(MotionGenerator.shakeTracks, MotionPresets.tracks("Shake", null))
		assertEquals(MotionGenerator.blinkTracks, MotionPresets.tracks("Blink", null))
		assertEquals(SkeletonMotions.idle(null) + MotionGenerator.idleBlinkTracks, MotionPresets.tracks("Idle", null))
		for (name in MotionClips.BUILTIN_NAMES) assertTrue(MotionPresets.knobs(name).isNotEmpty(), name)
	}

	@Test fun amplitudeScalesAGestureAboutItsRestAndLeavesTheEyes() {
		val half = MotionPresets.tracks("Nod", null, settings(MotionPresets.AMPLITUDE to 0.5f))
		for ((plain, scaled) in MotionGenerator.nodTracks.zip(half)) {
			val eyes = plain.parameterId.startsWith("ParamEye")
			for ((a, b) in plain.keys.zip(scaled.keys)) assertEquals(if (eyes) a.value else a.value * 0.5f, b.value, 1e-5f)
		}
	}

	@Test fun speedShortensTheMotionAndCountRepeatsIt() {
		val fast = MotionPresets.tracks("Shake", null, settings(MotionPresets.SPEED to 2f))
		assertEquals(MotionPresets.duration("Shake", MotionGenerator.shakeTracks) / 2f, MotionPresets.duration("Shake", fast), 1e-5f)
		val twice = MotionPresets.tracks("Nod", null, settings(MotionPresets.COUNT to 2f))
		val head = twice.first { it.parameterId == "ParamAngleY" }
		assertEquals(2, head.keys.count { it.value < -10f })
		val double = MotionPresets.tracks("Blink", null, settings(MotionPresets.DOUBLE to 1f, MotionPresets.CLOSURE to 0.5f))
		assertEquals(2, double.first().keys.count { it.value < 0.9f })
		assertEquals(0.5f, double.first().keys.minOf { it.value }, 1e-5f)
	}

	@Test fun idleKnobsTuneEachPartAndKeepTheLoopClosed() {
		val plain = MotionPresets.tracks("Idle", null).associateBy { it.parameterId }
		val tuned = MotionPresets.tracks("Idle", null, settings(
			MotionPresets.HEAD to 2f, MotionPresets.BREATH to 0f, MotionPresets.BLINK to 0f, MotionPresets.SPEED to 0.5f,
		)).associateBy { it.parameterId }
		fun span(track: MotionTrack) = track.keys.maxOf { it.value } - track.keys.minOf { it.value }
		assertEquals(span(plain.getValue("ParamAngleX")) * 2f, span(tuned.getValue("ParamAngleX")), 1e-3f)
		assertEquals(0f, span(tuned.getValue("ParamBreath")), 1e-5f)
		assertTrue("ParamEyeLOpen" !in tuned)
		assertEquals(SkeletonMotions.IDLE_DURATION * 2f, MotionPresets.duration("Idle", tuned.values.toList(), settings(MotionPresets.SPEED to 0.5f)))
		for (track in tuned.values) {
			assertEquals(SkeletonMotions.IDLE_DURATION * 2f, track.keys.last().time, 1e-3f)
			assertEquals(track.keys.first().value, track.keys.last().value, 1e-3f)
		}
	}

	@Test fun settingsRoundTripWithoutDefaults() {
		val saved = mapOf(
			"Idle" to settings(MotionPresets.AMPLITUDE to 1.6f),
			"Nod" to MotionPresetSettings(deleted = true),
			"Shake" to MotionPresetSettings(),
		)
		assertEquals(saved - "Shake", MotionPresets.fromJson(MotionPresets.toJson(saved)))
	}
}
