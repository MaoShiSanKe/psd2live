package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhysicsAuthoringTest {
	private val parameters = listOf("ParamAngleX", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleZ").map {
		Parameter(ParameterId(it), it, -30f, 30f, 0f)
	} + (1..3).map { Parameter(ParameterId("ParamTail$it"), "Tail $it", -1f, 1f, 0f) }
	private val available = parameters.mapTo(HashSet()) { it.id.raw }
	private val ranges = PhysicsEngine.ranges(parameters)
	private val none = PhysicsGenerator.Presets(false, false, false)

	private fun group(id: String, vararg outputs: String, segments: Int = outputs.size.coerceAtLeast(1)) =
		RigPhysicsEdit(id, id, PhysicsGenerator.headAndBodyInputs(available),
			outputs.mapIndexed { k, p -> PhysicsOutput(p, k + 1, 1.5f) }, List(segments) { PhysicsSegment(5f + it, 0.9f, 0.9f, 1.2f) })

	private fun catalog(overlay: RigEditOverlay) = PhysicsCatalog.groups(none, none, overlay, available)

	@Test
	fun insertingAndMovingPendulumsKeepsOutputsOnTheirPendulum() {
		val base = group("Tail", "ParamTail1", "ParamTail2", "ParamTail3")
		val inserted = base.withSegmentInserted(0)
		assertEquals(listOf(5f, 5f, 6f, 7f), inserted.segments.map { it.length })
		assertEquals(listOf(1, 3, 4), inserted.outputs.map { it.vertex })
		val moved = base.withSegmentMoved(2, 0)
		assertEquals(listOf(7f, 5f, 6f), moved.segments.map { it.length })
		assertEquals(listOf(2, 3, 1), moved.outputs.map { it.vertex })
		assertEquals(base, moved.withSegmentMoved(0, 2))
	}

	@Test
	fun theOrderDecidesEvaluationAndOutlivesRemovedGroups() {
		var overlay = RigEditOverlay(physicsEdits = listOf(group("A", "ParamTail1"), group("B", "ParamTail2"), group("C", "ParamTail3")))
		assertEquals(listOf("A", "B", "C"), catalog(overlay).map { it.id })
		overlay = PhysicsAuthoring.move(overlay, catalog(overlay), "C", -2)
		assertEquals(listOf("C", "A", "B"), catalog(overlay).map { it.id })
		overlay = PhysicsAuthoring.order(overlay, catalog(overlay), listOf("B"))
		assertEquals(listOf("B", "C", "A"), catalog(overlay).map { it.id })
		// A new group runs after the ordered ones; a removed one leaves the order.
		overlay = PhysicsAuthoring.put(overlay, group("D"))
		assertEquals("D", catalog(overlay).last().id)
		overlay = PhysicsAuthoring.remove(overlay, "C", generated = false)
		assertTrue("C" !in overlay.physicsOrder)
		assertFailsWith<IllegalArgumentException> { PhysicsAuthoring.order(overlay, catalog(overlay), listOf("Missing")) }
	}

	@Test
	fun aChainedGroupReadsWhatAnEarlierGroupWroteInTheSameStep() {
		// B follows A's output; run first, it sees the value from the step before.
		val a = group("A", "ParamTail1")
		val b = RigPhysicsEdit("B", "B", listOf(PhysicsInput("ParamTail1", 100f, PhysicsSourceType.X)),
			listOf(PhysicsOutput("ParamTail2", 1, 2f)), listOf(PhysicsSegment(5f, 0.9f, 0.9f, 1.2f)))
		fun run(order: List<RigPhysicsEdit>): Float {
			val engine = PhysicsEngine(order, ranges)
			var out = emptyMap<String, Float>()
			repeat(20) { out = engine.step(mapOf("ParamAngleX" to 30f), 1f / 60f) }
			return out.getValue("ParamTail2")
		}
		assertTrue(kotlin.math.abs(run(listOf(a, b)) - run(listOf(b, a))) > 1e-4f)
	}

	@Test
	fun importTakesTheFileGroupsFpsAndReportsWhatItChanged() {
		val existing = RigEditOverlay(physicsEdits = listOf(group("Old", "ParamTail1"), group("Kept", "ParamTail3")))
		val file = Physics3Json.write(listOf(group("New", "ParamTail1"), group("Odd", "ParamMissing"), group("New", "ParamTail2")), 30)!!
		val imported = PhysicsAuthoring.import(existing, catalog(existing), file, available)
		assertEquals(listOf("New", "Odd", "New_2"), imported.ids)
		assertEquals(listOf("Old"), imported.disabled)
		assertEquals(mapOf("Odd" to listOf("ParamMissing")), imported.missing)
		assertEquals(30, imported.fps)
		assertEquals(30, imported.overlay.physicsFps)
		val groups = catalog(imported.overlay)
		assertEquals(listOf("Old", "Kept", "New", "Odd", "New_2"), groups.map { it.id })
		assertEquals(setOf("Kept", "New", "New_2"), groups.filter { it.active }.map { it.id }.toSet())
		assertFailsWith<IllegalArgumentException> { PhysicsAuthoring.import(existing, catalog(existing), """{"Version":3,"Meta":{},"PhysicsSettings":[]}""", available) }
	}

	@Test
	fun fittingScalesEachOutputToReachTheParameterEnd() {
		val tail = group("Tail", "ParamTail1", "ParamTail2")
		val before = PhysicsResponse.trace(tail, ranges).reach
		assertTrue(before.values.all { it > 0.01f })
		val fitted = PhysicsAuthoring.fitScales(tail, before)
		PhysicsResponse.trace(fitted, ranges).reach.values.forEach { assertEquals(1f, it, 0.01f) }
		val half = PhysicsAuthoring.fitScales(tail, before, target = 0.5f)
		PhysicsResponse.trace(half, ranges).reach.values.forEach { assertEquals(0.5f, it, 0.01f) }
		// An output that did not move keeps its scale.
		assertEquals(tail, PhysicsAuthoring.fitScales(tail, mapOf(0 to 0f)))
	}

	@Test
	fun presetsRoundTripAndApplyToAGroup() {
		val tail = group("Tail", "ParamTail1", "ParamTail2", "ParamTail3")
		val inputs = PhysicsPresets.capture(PhysicsPresets.Kind.INPUT, " Mine ", tail)
		assertEquals("Mine", inputs.name)
		val pendulums = PhysicsPresets.capture(PhysicsPresets.Kind.PENDULUM, "Two", tail.withSegmentCount(2))
		assertEquals(listOf(inputs, pendulums), PhysicsPresets.listFromJson(PhysicsPresets.listToJson(listOf(inputs, pendulums))))
		// Fewer pendulums: outputs past the tip read the tip, one output per parameter.
		val shorter = PhysicsPresets.apply(pendulums, tail, available)
		assertEquals(2, shorter.segments.size)
		assertEquals(listOf(1, 2, 2), shorter.outputs.map { it.vertex })
		// Inputs skip parameters the model lacks and the group's own outputs.
		val eyes = PhysicsPresets.builtins(PhysicsPresets.Kind.INPUT).last()
		assertTrue(PhysicsPresets.apply(eyes, tail, available).inputs.isEmpty())
		assertEquals(emptyList(), PhysicsPresets.listFromJson("not json"))
	}

	@Test
	fun theRateIsWrittenAndBounded() {
		val json = Json.parseToJsonElement(Physics3Json.write(listOf(group("Tail", "ParamTail1")), 120)!!).jsonObject
		assertEquals(120, json.getValue("Meta").jsonObject.getValue("Fps").jsonPrimitive.int)
		assertEquals(120f, Physics3Json.read(json.toString()).fps)
		assertFailsWith<IllegalArgumentException> { PhysicsAuthoring.setFps(RigEditOverlay(), -1) }
		assertEquals(30, PhysicsAuthoring.setFps(RigEditOverlay(), 30).physicsFps)
	}
}
