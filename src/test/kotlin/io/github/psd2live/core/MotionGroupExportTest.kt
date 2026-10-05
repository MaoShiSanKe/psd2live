package io.github.psd2live.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionGroupExportTest {
	@Test fun modelPresetGroupsAndSkeletonSwitchesDecideTheExportedMotions() {
		val pipeline = PSD2LivePipeline()
		val initial = pipeline.buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val skeleton = SkeletonAutoBuilder.build(initial.analysis, initial.rig)
		val config = initial.config.copy(rigEdits = initial.config.rigEdits.copy(skeleton = skeleton))
		fun motions(config: PipelineConfig): Set<String> =
			pipeline.buildPreview(initial.analysis, config).runtimeBundle.assets.map { it.path }
				.filter { it.endsWith(".motion3.json") }.map { it.removeSuffix(".motion3.json").substringAfterLast('.') }.toSet()

		val basic = setOf("idle", "blink", "nod", "shake")
		val all = motions(config)
		assertTrue(all.containsAll(basic))
		val skeletal = all - basic
		assertTrue(skeletal.isNotEmpty())

		assertEquals(skeletal, motions(config.copy(motionBasic = false)))
		assertEquals(basic, motions(config.copy(motionSkeleton = false)))

		// One skeleton preset switched off in the animation panel leaves only itself out.
		val off = SkeletonMotions.presets.first { it.name.replaceFirstChar(Char::lowercase) in skeletal }.name
		val disabled = config.copy(rigEdits = config.rigEdits.copy(motionPresets = mapOf(off to MotionPresetSettings(disabled = true))))
		assertEquals(all - off.replaceFirstChar(Char::lowercase), motions(disabled))
	}
}
