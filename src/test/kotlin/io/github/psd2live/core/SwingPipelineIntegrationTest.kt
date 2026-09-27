package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingPipelineIntegrationTest {
	private val psd = Path.of("examples/tml/psd-input/tml.psd")

	@Test fun swingsOnMeshesRebuildMoveTheArtAndExportTheirPendulums() {
		val initial = PSD2LivePipeline().buildPreview(psd)
		val puppet = initial.rig.puppet
		val layers = initial.analysis.layers.associateBy { it.source.id.raw }
		fun meshOf(tag: SemanticTag) = puppet.drawables.first { d ->
			d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag
		}
		val back = meshOf(SemanticTag.BACK_HAIR)
		val front = meshOf(SemanticTag.FRONT_HAIR)

		var overlay = initial.config.rigEdits
		overlay = SwingAuthoring.put(overlay, puppet, RigSwingEdit.single("back", "Back", SwingKind.LATERAL, listOf(back.id.raw),
			listOf("ParamSwingBack"), shape = SwingShape(magnitude = 0.25f, parallel = 0.7f)), estimatePhysics = true)
		val withBack = overlay.applyTo(puppet)
		// The front hair moves both ways at once: a bounce and a side sway on one Warp.
		overlay = SwingAuthoring.put(overlay, withBack, RigSwingEdit("front", "Front", listOf(front.id.raw), listOf(
			SwingMotion(SwingKind.VERTICAL, listOf("ParamSwingFront_1", "ParamSwingFront_2"), SwingShape(magnitude = 0.1f)),
			SwingMotion(SwingKind.LATERAL, listOf("ParamSwingFrontX"), SwingShape(magnitude = 0.2f, parallel = 0.8f))),
			fulcrum = SwingFulcrum.TOP), estimatePhysics = true)
		val config = initial.config.copy(rigEdits = overlay, generatePhysics = true)
		val preview = PSD2LivePipeline().buildPreview(initial.analysis, config)
		val swung = preview.rig.puppet

		val evaluator = CpuDeformationEvaluator()
		val before = evaluator.evaluate(puppet, emptyMap()).worldPositions
		val rest = evaluator.evaluate(swung, emptyMap()).worldPositions
		for (id in listOf(back.id, front.id)) {
			val expected = before.getValue(id); val actual = rest.getValue(id)
			for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) < 0.5f, "${id.raw} moved at rest")
		}
		fun shift(id: org.umamo.runtime.model.DrawableId, parameter: String, axis: Int): Float {
			val posed = evaluator.evaluate(swung, mapOf(ParameterId(parameter) to 1f)).worldPositions.getValue(id)
			val still = rest.getValue(id)
			return (axis until posed.size step 2).maxOf { abs(posed[it] - still[it]) }
		}
		assertTrue(shift(back.id, "ParamSwingBack", 0) > 10f, "back hair swings sideways")
		assertTrue(shift(front.id, "ParamSwingFront_1", 1) > 3f, "front hair bounces vertically")
		assertTrue(shift(front.id, "ParamSwingFrontX", 0) > 5f, "front hair also sways sideways")

		val output = Files.createTempDirectory("swing-export")
		val result = PSD2LivePipeline().run(psd, output, config)
		val physicsFile = result.exportedFiles.map { it.path }.single { it.toString().endsWith(".physics3.json") }
		val physics = Json.parseToJsonElement(physicsFile.readText()).jsonObject
		val settings = physics.getValue("PhysicsSettings").jsonArray.map { it.jsonObject }
		val frontSetting = settings.single { it.getValue("Id").jsonPrimitive.content == "PhysicsSwing_front_Y" }
		assertTrue(settings.any { it.getValue("Id").jsonPrimitive.content == "PhysicsSwing_front_X" })
		assertEquals(listOf(1, 2), frontSetting.getValue("Output").jsonArray.map { it.jsonObject.getValue("VertexIndex").jsonPrimitive.int })
		assertEquals(3, frontSetting.getValue("Vertices").jsonArray.size)
		assertTrue(settings.any { it.getValue("Id").jsonPrimitive.content == "PhysicsSwing_back" })
		assertEquals(settings.sumOf { it.getValue("Output").jsonArray.size },
			physics.getValue("Meta").jsonObject.getValue("TotalOutputCount").jsonPrimitive.int)
		assertTrue(result.exportedFiles.any { it.path.toString().endsWith(".cmo3") })
		assertTrue(result.warnings.none { "PhysicsSwing" in it || "Swing" in it }, result.warnings.toString())
	}
}
