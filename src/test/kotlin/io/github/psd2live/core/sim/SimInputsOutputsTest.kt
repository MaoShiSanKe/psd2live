package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigPhysicsEdit
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class SimInputsOutputsTest {
    private val names = listOf("ParamAngleX", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleY")

    /** A 1 x 2 strip whose parameters are [names], enough to validate and fingerprint a body on. */
    private fun model(parameters: List<String> = names): PuppetModel {
        val p = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f, 0f, 20f, 10f, 20f)
        val mesh = DrawableMesh(p, FloatArray(p.size) { p[it] / 100f }, intArrayOf(0, 1, 3, 0, 3, 2, 2, 3, 5, 2, 5, 4))
        val drawable = Drawable(DrawableId("cloth"), "cloth", null, BlendMode.Normal, emptyList(), mesh, null)
        return PuppetModel(parameters.map { Parameter(ParameterId(it), it, -30f, 30f, 0f) }, emptyList(), emptyList(), listOf(drawable),
            listOf(OrgChild.Drawable(drawable.id)), null)
    }

    private fun create(arguments: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", "s"); put("kind", "cloth"); putJsonArray("targets") { add("cloth") }; arguments()
    }

    @Test fun aNewBodyListsTheDefaultInputsTheModelHas() {
        val overlay = SimAuthoring.put(RigEditOverlay(), model(), create {})
        val inputs = overlay.simEdits.single().inputs.map { it.parameter }
        // Only what the model has: no ParamAngleZ-less guesses, ParamBodyAngleY among the vertical inputs.
        assertEquals(names, inputs)
        assertEquals(RigSimEdit.defaultInputs(names.toSet()), overlay.simEdits.single().inputs)
    }

    @Test fun anEmptyListMeansNoInputsAndCannotBake() {
        val overlay = SimAuthoring.put(RigEditOverlay(), model(), create { putJsonArray("inputs") {} })
        val edit = overlay.simEdits.single()
        assertTrue(edit.inputs.isEmpty())
        // Saved and reopened, none stays none.
        assertTrue(RigSimEdit.fromJson(edit.toJson()).inputs.isEmpty())
        val failure = assertFailsWith<IllegalArgumentException> { SimBaker.bake(model(), edit, SimBaker.Options(duration = 1f)) }
        assertTrue("add inputs" in failure.message.orEmpty(), failure.message)
    }

    @Test fun aBodySavedWithoutInputsTakesTheDefaultsAndStaysBaked() {
        val legacy = RigSimEdit("s", "s", SimKind.CLOTH, listOf("cloth"))
        val json = JsonObject(legacy.toJson() - "inputs")
        val reopened = RigSimEdit.fromJson(json)
        assertEquals(RigSimEdit.defaultInputs(null), reopened.inputs)
        // Its bake was fingerprinted when no inputs meant the defaults: the defaults, listed in full or as far
        // as the model has them, hash alike, and anything else differs.
        val old = SimBake.fingerprint(model(), reopened)
        assertEquals(old, SimBake.fingerprint(model(), reopened.copy(inputs = RigSimEdit.defaultInputs(names.toSet()))))
        assertNotEquals(old, SimBake.fingerprint(model(), reopened.copy(inputs = reopened.inputs.take(1))))
        assertNotEquals(old, SimBake.fingerprint(model(), reopened.copy(inputs = emptyList())))
    }

    @Test fun inputsWhoseParameterIsGoneDropOutButANewOneMustExist() {
        val legacy = RigSimEdit.fromJson(JsonObject(RigSimEdit("s", "s", SimKind.CLOTH, listOf("cloth")).toJson() - "inputs"))
        val overlay = SimAuthoring.put(RigEditOverlay(simEdits = listOf(legacy)), model(), legacy.copy(keys = 7))
        assertEquals(names, overlay.simEdits.single().inputs.map { it.parameter })
        val added = overlay.simEdits.single().let { it.copy(inputs = it.inputs + PhysicsInput("ParamNowhere")) }
        assertFailsWith<IllegalArgumentException> { SimAuthoring.put(overlay, model(), added) }
    }

    @Test fun outputsTakeTheirNamesWithoutBakingAgain() {
        val x = "ParamSims_1"; val y = "ParamSims_2"
        val pendulum = RigPhysicsEdit("PhysicsSim_s", "Cloth", inputs = listOf(PhysicsInput("ParamAngleX", 50f, PhysicsSourceType.ANGLE)),
            outputs = listOf(PhysicsOutput(x, 1, 1f), PhysicsOutput(y, 1, 1f)), segments = listOf(PhysicsSegment(10f, 0.9f, 0.8f, 1f)))
        val sim = RigSimEdit("s", "Cloth", SimKind.CLOTH, listOf("cloth"), blendShapes = false,
            inputs = RigSimEdit.defaultInputs(names.toSet()),
            bake = SimBakeResult("f", emptyMap(), emptyList(), listOf(x, y).map {
                SimBakedMode(SimBakedAxis(it, floatArrayOf(-30f, 0f, 30f), emptyMap()), 1f, 1f)
            }, physics = pendulum))
        val renamed = sim.copy(outputNames = mapOf(x to "Skirt sway", "PhysicsSim_s" to "Skirt"))
        val built = SimGenerator.apply(model(), listOf(renamed))
        assertEquals("Skirt sway", built.parameters.single { it.id.raw == x }.name)
        assertEquals("Cloth 2", built.parameters.single { it.id.raw == y }.name)
        val rules = SimGenerator.physicsRules(listOf(renamed), names.toSet() + x + y)
        assertEquals("Skirt", rules.single().name)
        assertEquals(SimBake.fingerprint(model(), sim), SimBake.fingerprint(model(), renamed))
        assertEquals(renamed, RigSimEdit.fromJson(renamed.toJson()))
        // Patching replaces the names; leaving one out goes back to the name after the body.
        assertEquals(mapOf("PhysicsSim_s" to "Skirt"), renamed.patched(buildJsonObject {
            putJsonObject("output_names") { put("PhysicsSim_s", "Skirt") }
        }).outputNames)
    }
}
