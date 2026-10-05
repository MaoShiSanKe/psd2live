package io.github.psd2live.core

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RigTuningTest {
	/** A value of [field] well away from its default, inside its range. */
	private fun other(field: RigTuning.Field): Float {
		val span = field.range.endInclusive - field.range.start
		val up = field.default + span * 0.25f
		return if (up <= field.range.endInclusive) up else field.default - span * 0.25f
	}

	@Test fun eachValueSetsItselfAloneWithinItsRange() {
		assertEquals(RigTuning.fields.size, RigTuning.fieldById.size, "ids are unique")
		val defaults = RigTuning().toMap()
		for (field in RigTuning.fields) {
			assertTrue(field.default in field.range, "${field.id} default ${field.default} in ${field.range}")
			val value = other(field)
			val tuned = RigTuning().with(field.id, value).toMap()
			assertEquals(value, tuned.getValue(field.id), field.id)
			assertEquals(defaults - field.id, tuned - field.id, "${field.id} leaves the others")
			assertEquals(field.range.endInclusive, RigTuning().with(field.id, Float.MAX_VALUE).toMap().getValue(field.id), "${field.id} clamps")
		}
		assertEquals(RigTuning.Group.entries.toSet(), RigTuning.fields.map { it.group }.toSet(), "every group has values")
		assertTrue(RigTuning.fields.any { it.advanced } && RigTuning.fields.any { !it.advanced }, "some values fold under Advanced")
	}

	@Test fun everyValueMovesASampleRig() {
		// Not every sample has every part, so a value need only move one of them; neither draws teeth or a tongue.
		val inert = listOf("tml", "ds").map { inertOn(it) }.reduce { a, b -> a intersect b } - "teethFade"
		assertTrue(inert.isEmpty(), "values that move nothing on the samples: $inert")
	}

	/** The values that leave the rig of the sample [name] as it is. */
	private fun inertOn(name: String): Set<String> {
		val pipeline = PSD2LivePipeline()
		// Feature displacement is off by default; its values only move a rig that has it.
		val config = PipelineConfig(featureDisplacementEnabled = true)
		val preview = pipeline.buildPreview(Path.of("examples/$name/psd-input/$name.psd"), config)
		val cache = PreviewMeshCache()
		fun keyforms(tuning: RigTuning) =
			fingerprint(RigBuilder.build(preview.analysis, preview.atlas, config.copy(rigTuning = tuning), cache).puppet)
		val base = keyforms(RigTuning())
		assertEquals(fingerprint(preview.baseRig.puppet), base, "a rebuild of $name gives the same rig")
		return RigTuning.fields.filter { field -> keyforms(RigTuning().with(field.id, other(field))) == base }.mapTo(HashSet()) { it.id }
	}

	/** Every keyform of [puppet]'s deformers and drawables, by id. */
	private fun fingerprint(puppet: PuppetModel): Map<String, List<Float>> {
		fun <T> floats(grid: KeyformGrid<T>?, form: (T) -> List<Float>): List<Float> =
			grid?.let { g -> g.axes.flatMap { it.keys.toList() } + g.cells.flatMap { form(it.form) } }.orEmpty()
		fun channels(grids: ChannelGrids): List<Float> = grids.gridsByChannel.entries.sortedBy { it.key.name }.flatMap { (_, grid) ->
			floats(grid) { (it as? ChannelValue.Scalar)?.let { s -> listOf(s.value) } ?: listOf(it.hashCode().toFloat()) }
		}
		val deformers = puppet.deformers.associate { deformer ->
			deformer.id.raw to when (deformer) {
				is Deformer.Warp -> floats(deformer.geometryGrid) { it.controlPoints.toList() }
				is Deformer.Rotation -> floats(deformer.geometryGrid) { listOf(it.originX, it.originY, it.angle, it.scale) }
			} + channels(deformer.channelGrids)
		}
		val drawables = puppet.drawables.associate { drawable ->
			drawable.id.raw to floats(drawable.geometryGrid) { it.positionDeltas.toList() } + channels(drawable.channelGrids)
		}
		return deformers + drawables
	}
}
