package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.umamo.format.moc3.Moc3
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhysicsTest {
	private val parameters = listOf(
		Parameter(ParameterId("ParamAngleX"), "Angle X", -30f, 30f, 0f),
		Parameter(ParameterId("ParamAngleZ"), "Angle Z", -30f, 30f, 0f),
		Parameter(ParameterId("ParamBodyAngleX"), "Body X", -10f, 10f, 0f),
		Parameter(ParameterId("ParamBodyAngleZ"), "Body Z", -10f, 10f, 0f),
		Parameter(ParameterId("ParamHairBack"), "Hair Back", -1f, 1f, 0f),
		Parameter(ParameterId("ParamHairFront"), "Hair Front", -1f, 1f, 0f),
		Parameter(ParameterId("ParamTail1"), "Tail 1", -1f, 1f, 0f),
		Parameter(ParameterId("ParamTail2"), "Tail 2", -1f, 1f, 0f),
	)
	private val available = parameters.mapTo(HashSet()) { it.id.raw }
	private val ranges = PhysicsEngine.ranges(parameters)
	private val all = PhysicsGenerator.Presets.All

	private fun tail(segments: Int = 2) = RigPhysicsEdit("Tail", "Tail", PhysicsGenerator.headAndBodyInputs(available),
		(1..segments).map { PhysicsOutput("ParamTail$it", it, 1.5f) }, List(segments) { PhysicsSegment(5f, 0.9f, 0.9f, 1.2f) })

	@Test
	fun theFirstVersionReadsAsOneAngleInputAndOneOutput() {
		val legacy = RigPhysicsEdit.fromJson(Json.parseToJsonElement("""
			{"id":"Mine","name":"Mine","input_parameter":"ParamAngleX","output_parameter":"ParamTail1","length":12,"mobility":0.5,"delay":0.7,"acceleration":2,"output_scale":1.3}
		""").jsonObject)
		assertEquals(listOf(PhysicsInput("ParamAngleX", 100f, PhysicsSourceType.ANGLE)), legacy.inputs)
		assertEquals(listOf(PhysicsOutput("ParamTail1", 1, 1.3f)), legacy.outputs)
		assertEquals(listOf(PhysicsSegment(12f, 0.5f, 0.7f, 2f)), legacy.segments)
		assertEquals(30f, legacy.normalization.angleMax)
		// And the current shape reads back exactly.
		assertEquals(tail(3), RigPhysicsEdit.fromJson(tail(3).toJson()))
	}

	@Test
	fun patchesChangeOnlyWhatTheyName() {
		val base = tail(2)
		val longer = base.patched(buildJsonObject { put("length", 20f); put("mobility", 0.5f) })
		assertEquals(20f, longer.totalLength, 1e-4f)
		assertTrue(longer.segments.all { it.mobility == 0.5f && it.delay == 0.9f })
		assertEquals(base.outputs, longer.outputs)
		// An output deeper than the strand grows it.
		val deeper = base.patched(buildJsonObject { putJsonArray("outputs") { add(buildJsonObject { put("parameter", "ParamTail2"); put("vertex", 4) }) } })
		assertEquals(4, deeper.segments.size)
		// Removing a segment moves the outputs that read it and below.
		val shorter = tail(2).withoutSegment(0)
		assertEquals(listOf(1, 1), shorter.outputs.map { it.vertex })
		assertFailsWith<IllegalArgumentException> { tail(1).withoutSegment(0) }
	}

	@Test
	fun normalizationFollowsCubism() {
		val range = PhysicsEngine.Range(-30f, 30f, 0f)
		// Not reflected, a positive parameter pushes toward the negative side.
		assertEquals(-10f, PhysicsEngine.normalize(30f, range, -10f, 10f, 0f, false), 1e-5f)
		assertEquals(5f, PhysicsEngine.normalize(-15f, range, -10f, 10f, 0f, false), 1e-5f)
		assertEquals(10f, PhysicsEngine.normalize(99f, range, -10f, 10f, 0f, true), 1e-5f)
		assertEquals(0f, PhysicsEngine.normalize(0f, range, -10f, 10f, 0f, true), 1e-5f)
	}

	@Test
	fun aPendulumSwingsWhenTheHeadTurnsAndSettlesBack() {
		val engine = PhysicsEngine(listOf(tail(2)), ranges)
		val rest = engine.settle(emptyMap())
		assertEquals(0f, rest.getValue("ParamTail1"), 1e-4f)
		var peak = 0f
		repeat(60) { peak = maxOf(peak, abs(engine.step(mapOf("ParamAngleX" to 30f), 1f / 60f).getValue("ParamTail1"))) }
		assertTrue(peak > 0.05f, "peak $peak")
		val after = engine.settle(emptyMap(), 6f)
		assertEquals(0f, after.getValue("ParamTail1"), 0.02f)
		assertEquals(0f, after.getValue("ParamTail2"), 0.02f)

		// Reflecting the output mirrors it.
		val mirrored = tail(2).let { it.copy(outputs = it.outputs.map { o -> o.copy(reflect = true) }) }
		val a = PhysicsEngine(listOf(tail(2)), ranges)
		val b = PhysicsEngine(listOf(mirrored), ranges)
		repeat(20) {
			val x = a.step(mapOf("ParamAngleX" to 30f), 1f / 60f).getValue("ParamTail1")
			val y = b.step(mapOf("ParamAngleX" to 30f), 1f / 60f).getValue("ParamTail1")
			assertEquals(x, -y, 1e-5f)
		}
	}

	@Test
	fun theCatalogResolvesPresetsOverridesAndSwitches() {
		val overlay = RigEditOverlay()
		val groups = PhysicsCatalog.groups(all, all, overlay, available)
		assertEquals(listOf(PhysicsGenerator.BACK_HAIR_ID, PhysicsGenerator.FRONT_HAIR_ID, PhysicsGenerator.EYE_JELLY_ID), groups.map { it.id })
		// The eye preset has neither its inputs nor its output here.
		assertEquals(PhysicsIssue.Code.MISSING_PARAMETER, groups.last().issue?.code)
		assertTrue(groups.take(2).all { it.active })

		val back = groups.first()
		val edited = back.setting.withSegmentCount(3)
		val replaced = PhysicsAuthoring.put(overlay, edited, back.generated)
		val row = PhysicsCatalog.groups(all, all, replaced, available).first()
		assertTrue(row.overridden)
		assertEquals(3, row.setting.segments.size)
		// Editing back to the generated values drops the replacement.
		assertTrue(PhysicsAuthoring.put(replaced, back.generated!!, back.generated).physicsEdits.isEmpty())

		val off = PhysicsCatalog.groups(all, PhysicsGenerator.Presets(true, false, true), overlay, available)
		assertFalse(off.first().active)
		val custom = PhysicsAuthoring.put(overlay, tail())
		assertFalse(PhysicsCatalog.groups(all, all, PhysicsAuthoring.setEnabled(custom, "Tail", false), available).last().active)
		assertFailsWith<IllegalArgumentException> { PhysicsAuthoring.setEnabled(overlay, PhysicsGenerator.BACK_HAIR_ID, false) }
		// Two user groups may not drive the same parameter.
		assertFailsWith<IllegalArgumentException> { PhysicsAuthoring.put(custom, tail().copy(id = "Other")) }
	}

	@Test
	fun agentRequestsPatchTheExistingGroup() {
		val groups = PhysicsCatalog.groups(all, all, RigEditOverlay(), available)
		val request = PhysicsAuthoring.request(groups, buildJsonObject {
			put("id", PhysicsGenerator.BACK_HAIR_ID); put("segment_count", 2)
		}, available)
		assertEquals(2, request.edit!!.segments.size)
		assertEquals(groups.first().setting.inputs, request.edit.inputs)
		val toggle = PhysicsAuthoring.request(groups, buildJsonObject { put("id", PhysicsGenerator.FRONT_HAIR_ID); put("enabled", false) }, available)
		assertNull(toggle.edit)
		assertEquals(false, toggle.enabled)
		// A new group starts from the template and must name an output that exists.
		val created = PhysicsAuthoring.request(groups, buildJsonObject {
			put("id", "Tail"); putJsonArray("outputs") { add(buildJsonObject { put("parameter", "ParamTail1") }) }
		}, available)
		assertEquals(4, created.edit!!.inputs.size)
		assertFailsWith<IllegalArgumentException> {
			PhysicsAuthoring.request(groups, buildJsonObject { put("id", "X"); put("output_parameter", "ParamMissing") }, available)
		}
	}

	@Test
	fun exportWritesEverySegmentAndReadsBack() {
		val text = Physics3Json.write(listOf(tail(3)), 60)!!
		val physics = Moc3.readPhysics3(text)
		val setting = physics.physicsSettings.single()
		assertEquals(4, setting.vertices.size)
		assertEquals(listOf(0f, 5f, 10f, 15f), setting.vertices.map { it.position.y.float })
		assertEquals(listOf(0f, 5f, 5f, 5f), setting.vertices.map { it.radius.float })
		assertEquals(listOf(1, 2, 3), setting.output.map { it.vertexIndex })
	}

	@Test
	fun simulationReportsPeakAndSettling() {
		val groups = PhysicsCatalog.groups(all, all, PhysicsAuthoring.put(RigEditOverlay(), tail()), available)
		val result = PhysicsSimulation.run(groups, parameters, buildJsonObject {
			putJsonObject("inputs") { put("ParamAngleX", 30) }
			put("ids", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Tail"))))
			put("hold", 1.5f); put("duration", 8f)
		}, true)
		val first = result.getValue("outputs").jsonArray.first().jsonObject
		assertEquals("ParamTail1", first.getValue("parameter").jsonPrimitive.content)
		val released = first.getValue("after_release").jsonObject
		assertNotNull(released["peak"])
		assertTrue(released.getValue("settled").jsonPrimitive.boolean)
		assertEquals(0f, released.getValue("final").jsonPrimitive.float, 0.02f)
	}

	@Test
	fun pullingEasesInAndLettingGoEasesBack() {
		val drag = PhysicsDrag()
		var previous = 0f
		var biggestStep = 0f
		repeat(120) { f ->
			if (f < 60) drag.target(1f, 0f) else drag.release()
			drag.update(1f / 60f)
			biggestStep = maxOf(biggestStep, abs(drag.x - previous))
			previous = drag.x
		}
		// Cubism's target point never moves more than its top speed in a frame, releasing included.
		assertTrue(biggestStep <= 4f / 30f + 1e-4f, "step $biggestStep")
		assertTrue(drag.x < 0.05f)
		val offsets = drag.also { it.target(1f, 0f); repeat(90) { _ -> it.update(1f / 60f) } }.offsets(listOf("ParamAngleX", "ParamTail1"), ranges)
		assertEquals(30f, offsets.getValue("ParamAngleX"), 0.5f)
		assertEquals(1f, offsets.getValue("ParamTail1"), 0.02f)
	}

	@Test
	fun theResponseCurveSwingsAndSettles() {
		val trace = PhysicsResponse.trace(tail(2), ranges)
		assertTrue(trace.peak("ParamTail1") > 0.05f)
		assertTrue(trace.settleTime("ParamTail1") < trace.times.last())
	}

	@Test
	fun removingASwingForgetsItsPhysicsEdits() {
		val swing = RigSwingEdit.single("tail", "Tail", SwingKind.LATERAL, listOf("WarpTail"), listOf("ParamTail1"),
			physics = SwingPhysics())
		val id = PhysicsGenerator.swingPhysicsId(swing)
		val overlay = RigEditOverlay(swingEdits = listOf(swing), physicsEdits = listOf(tail(1).copy(id = id)), disabledPhysicsIds = setOf(id))
		val removed = SwingAuthoring.remove(overlay, "tail")
		assertTrue(removed.physicsEdits.isEmpty())
		assertTrue(removed.disabledPhysicsIds.isEmpty())
	}
}

/**
 * PhysicsEngine against the Cubism Native Framework 5-r.5: the fixtures in `physics-reference` were
 * produced by `native/physics_reference` running the same physics3.json and frame schedule (irregular
 * frame times, chained groups, reflection, partial weights, an X output) on an exported model.
 */
class PhysicsReferenceTest {
	private fun resource(name: String) = requireNotNull(javaClass.getResource("/physics-reference/$name")) { name }.readText()

	private fun check(variant: String) {
		val (settings, fps) = Physics3Json.read(resource("$variant.physics3.json"))
		val expectedLines = resource("$variant.expected.csv").lines().filter { it.isNotBlank() }
		val ranges = expectedLines.filter { it.startsWith("# range,") }.associate { line ->
			val c = line.removePrefix("# range,").split(",")
			c[0] to PhysicsEngine.Range(c[1].toFloat(), c[2].toFloat(), c[3].toFloat())
		}
		val header = expectedLines.first { it.startsWith("frame") }.split(",").drop(1)
		val expected = expectedLines.filter { it.first().isDigit() }.map { row -> row.split(",").drop(1).map(String::toFloat) }
		val schedule = resource("schedule.csv").lines().filter { it.isNotBlank() }
		val inputs = schedule.first().split(",").drop(1)
		val engine = PhysicsEngine(settings, ranges, fps)
		var worst = 0f
		schedule.drop(1).forEachIndexed { frame, row ->
			val cells = row.split(",").map(String::toFloat)
			val values = inputs.mapIndexed { k, id -> id to cells[k + 1].coerceIn(ranges.getValue(id).min, ranges.getValue(id).max) }.toMap()
			val out = engine.step(values, cells[0])
			header.forEachIndexed { k, id ->
				val actual = out[id] ?: ranges.getValue(id).default
				val diff = abs(actual - expected[frame][k])
				worst = maxOf(worst, diff)
				assertTrue(diff < 1e-4f, "$variant frame $frame $id: $actual vs Cubism ${expected[frame][k]}")
			}
		}
		println("$variant worst difference from Cubism: $worst")
	}

	@Test fun matchesCubismWithAFixedRate() = check("fps60")
	@Test fun matchesCubismStepPerFrame() = check("nofps")
}
