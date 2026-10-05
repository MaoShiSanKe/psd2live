package io.github.psd2live.ui.state

import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.sim.ModelPresets

/** How a hair moves: simulated, the legacy sway, or still. */
internal enum class HairMode(val key: String) {
	SIMULATION("presets.hairMode.simulate"),
	CLASSIC("presets.classicSway"),
	OFF("presets.hairMode.off"),
}

/** Which preset parts the art has; a part it lacks is left alone whatever the choice says. */
internal data class PresetParts(val frontHair: Boolean, val backHair: Boolean, val clothing: Boolean, val eyeJelly: Boolean) {
	companion object {
		fun of(analysis: PipelineAnalysis?): PresetParts {
			val present = PhysicsGenerator.Presets.present(analysis)
			val clothing = analysis?.layers.orEmpty().any { it.semantic.tag in ModelPresets.CLOTHING_TAGS && it.opaquePixels > 0 }
			return PresetParts(present.frontHair, present.backHair, clothing, present.eyeJelly)
		}
	}
}

/** The model presets the start screen sets: rig strength and facial switches, motion groups, physics and simulation. */
internal data class StartPresetChoices(
	val headStrength: Float = 1f,
	val bodyStrength: Float = 1f,
	val featureDisplacement: Boolean = false,
	val mouthOutline: Boolean = true,
	val motionBasic: Boolean = true,
	val motionSkeleton: Boolean = true,
	val frontHair: HairMode = HairMode.CLASSIC,
	val backHair: HairMode = HairMode.CLASSIC,
	val clothing: Boolean = true,
	val eyeJelly: Boolean = true,
) {
	/** The choices as far as [parts] lets them matter, so two that differ only on a missing part compare equal. */
	fun within(parts: PresetParts) = copy(
		frontHair = if (parts.frontHair) frontHair else HairMode.OFF,
		backHair = if (parts.backHair) backHair else HairMode.OFF,
		clothing = clothing && parts.clothing,
		eyeJelly = eyeJelly && parts.eyeJelly,
	)

	companion object {
		/** What the model in [state] has now. */
		fun of(state: PSD2LiveState) = StartPresetChoices(
			headStrength = state.headStrength,
			bodyStrength = state.bodyStrength,
			featureDisplacement = state.featureDisplacementEnabled,
			mouthOutline = state.mouthOutlineEnabled,
			motionBasic = state.motionBasic,
			motionSkeleton = state.motionSkeleton,
			frontHair = hairMode(state.hairSimulationFront, state.physicsFrontHair),
			backHair = hairMode(state.hairSimulationBack, state.physicsBackHair),
			clothing = state.rigEdits.simEdits.any { it.id in ModelPresets.CLOTHING_SIMS.values },
			eyeJelly = state.physicsEyeJelly,
		)

		private fun hairMode(simulated: Boolean, sway: Boolean) = when {
			simulated -> HairMode.SIMULATION
			sway -> HairMode.CLASSIC
			else -> HairMode.OFF
		}
	}
}

/** One-click sets of [StartPresetChoices] on the start screen. */
internal enum class StartQuickPreset(val key: String, val choices: StartPresetChoices) {
	/** The rig alone: no generated motions, physics or simulation, no mouth outline. */
	MINIMAL("start.quick.minimal", StartPresetChoices(
		mouthOutline = false, motionBasic = false, motionSkeleton = false,
		frontHair = HairMode.OFF, backHair = HairMode.OFF, clothing = false, eyeJelly = false,
	)),
	/** Both motion groups, classic hair sway, loose clothing simulated and the eye jelly. */
	DEFAULT("start.quick.default", StartPresetChoices()),
	/** The default with simulated hair and feature displacement. */
	FULL("start.quick.full", StartPresetChoices(
		featureDisplacement = true, frontHair = HairMode.SIMULATION, backHair = HairMode.SIMULATION,
	)),
}
