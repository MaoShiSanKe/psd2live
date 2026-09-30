package io.github.psd2live.core.sim

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.VertexGroupJournal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SimPipelineIntegrationTest {
    private val psd = Path.of("examples/tml/psd-input/tml.psd")

    @Test fun bakedHairExportsItsParametersKeysAndPendulum() {
        val initial = PSD2LivePipeline().buildPreview(psd)
        val puppet = initial.rig.puppet
        val layers = initial.analysis.layers.associateBy { it.source.id.raw }
        val back = puppet.drawables.first { d ->
            d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.BACK_HAIR
        }
        // Pinned along the top tenth of the hair as it is drawn (world y is up).
        val world = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(back.id)
        val ys = (1 until world.size step 2).map { world[it] }
        val top = ys.max(); val bottom = ys.min()
        val pin = VertexGroup("pin", back.id, VertexGroupKind.PIN, FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })

        var overlay = initial.config.rigEdits.copy(authoringJournal = initial.config.rigEdits.authoringJournal + VertexGroupJournal.encode(pin))
        val grouped = overlay.applyTo(initial.baseRig.puppet)
        overlay = SimAuthoring.put(overlay, grouped, RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw)))
        val bake = SimAuthoring.bake(overlay, initial.baseRig.puppet, "back")
        assertTrue(bake.modes.isNotEmpty() && bake.physics != null)
        overlay = SimAuthoring.withBake(overlay, "back", bake)

        val config = initial.config.copy(rigEdits = overlay, generatePhysics = true)
        val preview = PSD2LivePipeline().buildPreview(initial.analysis, config)
        val baked = preview.rig.puppet
        val evaluator = CpuDeformationEvaluator()
        val rest = evaluator.evaluate(baked, emptyMap()).worldPositions.getValue(back.id)
        val swung = evaluator.evaluate(baked, mapOf(ParameterId(bake.parameters.first()) to 1f)).worldPositions.getValue(back.id)
        assertTrue(rest.indices.maxOf { abs(rest[it] - swung[it]) } > 3f, "the first mode moves the hair")

        val output = Files.createTempDirectory("sim-export")
        val result = PSD2LivePipeline().run(psd, output, config)
        val physicsFile = result.exportedFiles.map { it.path }.single { it.toString().endsWith(".physics3.json") }
        val settings = Json.parseToJsonElement(physicsFile.readText()).jsonObject.getValue("PhysicsSettings").jsonArray.map { it.jsonObject }
        val setting = settings.single { it.getValue("Id").jsonPrimitive.content == "PhysicsSim_back" }
        val outputs = setting.getValue("Output").jsonArray.map { it.jsonObject.getValue("Destination").jsonObject.getValue("Id").jsonPrimitive.content }
        assertTrue(outputs == bake.parameters, "exported outputs $outputs")
        val moc = result.exportedFiles.map { it.path }.single { it.toString().endsWith(".moc3") }.readBytes().toString(Charsets.ISO_8859_1)
        for (parameter in bake.parameters) assertTrue(parameter in moc, "$parameter is in the moc3")
        assertTrue(result.warnings.none { "Sim" in it }, result.warnings.toString())
    }
}
