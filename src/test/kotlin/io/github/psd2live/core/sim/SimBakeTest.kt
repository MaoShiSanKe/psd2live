package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOrigin
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigEditOverlay
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

class SimBakeTest {
    private val angle = ParameterId("ParamAngleX")

    /**
     * A strand [rows] cells long hanging from its top row, which ParamAngleX carries 30 px left or right
     * (the whole mesh is keyed to follow; the simulation makes the rest lag behind and swing).
     */
    private fun strand(rows: Int = 8, cell: Float = 10f): PuppetModel {
        val positions = ArrayList<Float>()
        for (r in 0..rows) for (c in 0..1) { positions += c * cell; positions += r * cell }
        val indices = ArrayList<Int>()
        for (r in 0 until rows) { val a = r * 2; indices += listOf(a, a + 1, a + 3, a, a + 3, a + 2) }
        val p = positions.toFloatArray()
        val mesh = DrawableMesh(p, FloatArray(p.size) { p[it] / 1000f }, indices.toIntArray())
        fun shift(dx: Float) = MeshDeltaForm(FloatArray(p.size) { if (it % 2 == 0) dx else 0f })
        val grid = KeyformGrid(listOf(KeyformAxis(angle, floatArrayOf(-30f, 0f, 30f))),
            listOf(KeyformCell(intArrayOf(0), shift(-30f)), KeyformCell(intArrayOf(1), shift(0f)), KeyformCell(intArrayOf(2), shift(30f))))
        val drawable = Drawable(DrawableId("hair"), "hair", null, BlendMode.Normal, emptyList(), mesh, grid)
        val pin = VertexGroup("pin", drawable.id, VertexGroupKind.PIN, FloatArray(mesh.vertexCount) { if (it < 2) 1f else 0f })
        return PuppetModel(listOf(Parameter(angle, "Angle X", -30f, 30f, 0f)), emptyList(), emptyList(), listOf(drawable),
            listOf(OrgChild.Drawable(drawable.id)), null, vertexGroups = listOf(pin))
    }

    private fun edit() = RigSimEdit("hair", "Hair", SimKind.HAIR, listOf("hair"),
        inputs = listOf(PhysicsInput(angle.raw, 100f, PhysicsSourceType.X)))

    private val quick = SimBaker.Options(duration = 0.6f)

    @Test fun bakeTurnsTheSwingIntoAParameterKeyformsAndAPendulum() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit()))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        val mode = bake.modes.first()
        assertEquals("ParamSimhair_1", mode.axis.parameter)
        assertTrue(mode.amplitude > 5f, "the tip should lag visibly, lagged ${mode.amplitude} px")
        assertTrue(bake.fit > 0.65f, "the pendulum and keys should reproduce the simulation, R² ${bake.fit}")
        val physics = assertNotNull(bake.physics)
        assertEquals(bake.parameters, physics.outputParameters)
        assertEquals(2, physics.segments.size)
        // The root is pinned: nothing is keyed there.
        val offsets = mode.axis.offsets.getValue("hair")
        for (key in offsets) { assertEquals(0f, key[0], 1e-4f); assertEquals(0f, key[2], 1e-4f) }

        // By default the modes are blend shapes: they add to the grid instead of multiplying it.
        val baked = SimAuthoring.withBake(overlay, "hair", bake)
        val model = baked.applyTo(base)
        val parameter = model.parameters.single { it.id.raw == mode.axis.parameter }
        assertEquals(ParameterKind.BLEND_SHAPE, parameter.kind)
        assertEquals(-1f to 1f, parameter.min to parameter.max)
        val drawable = model.drawables.single()
        assertEquals(3, drawable.geometryGrid!!.cells.size)
        assertEquals(bake.parameters.toSet(), drawable.blendShapes.map { it.parameterId.raw }.toSet())
        assertContentEquals(floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f), drawable.blendShapes.first().keys)
        // Rebuilding twice gives the same rig: the bake is written back, never simulated again.
        val rebuilt = baked.applyTo(base).drawables.single().blendShapes
        assertEquals(drawable.blendShapes.flatMap { b -> b.forms.flatMap { it?.positionDeltas?.toList().orEmpty() } },
            rebuilt.flatMap { b -> b.forms.flatMap { it?.positionDeltas?.toList().orEmpty() } })

        // As keyform axes each mode multiplies the grid by its keys, and the rig moves the same.
        val axes = SimAuthoring.withBake(RigEditOverlay(simEdits = listOf(edit().copy(blendShapes = false))), "hair", bake)
        val gridModel = axes.applyTo(base)
        val grid = gridModel.drawables.single().geometryGrid!!
        assertTrue(grid.axisIndexOf(parameter.id) >= 0 && grid.axisIndexOf(angle) >= 0)
        assertEquals(3 * bake.modes.fold(1) { n, _ -> n * 5 }, grid.cells.size)
        val evaluator = CpuDeformationEvaluator()
        for (pose in listOf(mapOf(parameter.id to 0.7f), mapOf(parameter.id to -1f, angle to 20f))) {
            val a = evaluator.evaluate(model, pose).worldPositions.getValue(DrawableId("hair"))
            val b = evaluator.evaluate(gridModel, pose).worldPositions.getValue(DrawableId("hair"))
            for (i in a.indices) assertEquals(a[i], b[i], 0.05f, "blend shapes and keyform axes agree at $pose")
        }

        val groups = PhysicsCatalog.groups(PhysicsGenerator.Presets(false, false, false), PhysicsGenerator.Presets(false, false, false), baked, model.parameters.mapTo(HashSet()) { it.id.raw })
        val group = groups.single { it.origin == PhysicsOrigin.SIMULATION }
        assertTrue(group.active, "the fitted pendulum exports: ${group.issue?.message}")
        assertEquals(bake.parameters, group.setting.outputParameters)
        assertEquals("hair", SimGenerator.simulationOf(group.id, baked.simEdits)?.id)
    }

    @Test fun theBakedModelFollowsTheSimulation() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit()))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        val model = SimAuthoring.withBake(overlay, "hair", bake).applyTo(base)
        val physics = listOfNotNull(bake.physics)
        val engine = PhysicsEngine(physics, PhysicsEngine.ranges(model.parameters))
        val scene = SimScene.build(base, edit()).also { it.calibrate(base); it.reset(base, emptyMap()) }
        val evaluator = CpuDeformationEvaluator()
        val tip = 17
        // A head turn over 0.2 s held for a second and turned back: the tip's lag behind the root, simulated and baked.
        val simulated = ArrayList<Float>(); val played = ArrayList<Float>()
        fun ease(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        for (f in 0 until 150) {
            val value = 30f * (ease((f - 10) / 12f) - ease((f - 70) / 12f))
            val pose = mapOf(angle to value)
            scene.drive(base, pose, 1f / 60f)
            simulated += scene.state.x[tip] - scene.state.goalX[tip]
            val driven = engine.step(mapOf(angle.raw to value), 1f / 60f)
            val world = evaluator.evaluate(model, pose + driven.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId("hair"))
            val rig = evaluator.evaluate(base, pose).worldPositions.getValue(DrawableId("hair"))
            played += world[tip * 2] - rig[tip * 2]
        }
        val peak = simulated.maxOf { abs(it) }
        val error = sqrt(simulated.indices.sumOf { ((simulated[it] - played[it]) * (simulated[it] - played[it])).toDouble() } / simulated.size).toFloat()
        assertTrue(peak > 5f, "the simulated tip should lag, peaked $peak px")
        assertTrue(error < peak * 0.35f, "baked tip off by $error px RMS against a $peak px swing")
    }

    @Test fun bakeRoundTripsAndGoesStaleWhenTheSetupChanges() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit()))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        val baked = SimAuthoring.withBake(overlay, "hair", bake)
        val sim = baked.simEdits.single()
        assertEquals(sim, RigSimEdit.fromJson(Json.parseToJsonElement(sim.toJson().toString()).jsonObject))
        val tuned = sim.copy(keys = 7, blendShapes = false, autoBake = false)
        assertEquals(tuned, RigSimEdit.fromJson(Json.parseToJsonElement(tuned.toJson().toString()).jsonObject))
        // How the modes are written is not part of what the bake depends on.
        assertEquals(SimBake.fingerprint(base, sim), SimBake.fingerprint(base, sim.copy(blendShapes = false, autoBake = false)))
        // The bake's own keys do not change what it depends on.
        val model = baked.applyTo(base)
        assertFalse(SimBake.stale(model, sim))
        assertTrue(SimBake.stale(model, sim.copy(material = sim.material.copy(bend = 0.9f))))
        // A patch without a bake keeps it; bake: null clears it.
        assertEquals(bake, sim.patched(buildJsonObject { put("name", "Hair 2") }).bake)
        assertNull(sim.patched(buildJsonObject { put("bake", JsonNull) }).bake)
        // A remeshed target is skipped and reported, not a failed rebuild.
        val remeshed = base.copy(drawables = base.drawables.map { d ->
            val mesh = d.mesh!!
            val positions = mesh.positions + floatArrayOf(5f, 5f)
            d.copy(mesh = DrawableMesh(positions, mesh.uvs + floatArrayOf(0f, 0f), mesh.indices), geometryGrid = null)
        })
        assertTrue(SimGenerator.issues(remeshed, sim).any { "remeshed" in it })
    }

    @Test fun trajectoryStepsEveryInputInPiecesThatEndAtRest() {
        val inputs = listOf(Parameter(angle, "x", -30f, 30f, 0f), Parameter(ParameterId("ParamAngleZ"), "z", -30f, 30f, 0f))
        val pieces = SimBaker.trajectory(inputs, 60)
        assertEquals(5, pieces.size)
        for (piece in pieces) {
            assertEquals(2, piece.size)
            for (values in piece) assertEquals(0f, values.last(), 1e-6f)
        }
        for (i in inputs.indices) {
            val all = pieces.flatMap { it[i].asList() }
            assertEquals(30f, all.max(), 1e-3f); assertEquals(-30f, all.min(), 1e-3f)
        }
    }

    @Test fun keysSetHowManyKeysModesAndStaticAxesGet() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit().copy(keys = 3)))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        for (mode in bake.modes) assertContentEquals(floatArrayOf(-1f, 0f, 1f), mode.axis.keys)
        assertContentEquals(floatArrayOf(-30f, -15f, 0f, 15f, 30f), SimBaker.staticKeys(Parameter(angle, "x", -30f, 30f, 0f), 5))
        assertContentEquals(floatArrayOf(0f, 0.5f, 1f), SimBaker.staticKeys(Parameter(angle, "x", 0f, 1f, 0f), 5))
        assertFailsWith<IllegalArgumentException> { edit().copy(keys = 4) }
    }

    @Test fun keysAreSolvedTogetherAndFollowACurve() {
        // One mode swinging -1..1 whose vertex moves along a parabola: straight keys would cut the arc.
        val played = FloatArray(400) { kotlin.math.sin(it * 0.05f) }
        val residuals = played.map { u -> floatArrayOf(10f * u, 6f * u * u) }
        val keys = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val shapes = SimBaker.solveKeys(residuals, listOf(played), keys, 2).single()
        // The least-squares keys, not the curve's values: between the keys the lines pass under the curve.
        for ((k, key) in keys.withIndex()) assertEquals(10f * key, shapes[k][0], 0.3f)
        assertEquals(5.87f, shapes[0][1], 0.05f); assertEquals(1.15f, shapes[1][1], 0.05f)
        assertEquals(1.15f, shapes[3][1], 0.05f); assertEquals(5.86f, shapes[4][1], 0.05f)
    }

    @Test fun principalDirectionsComeLargestFirst() {
        val random = java.util.Random(3)
        val rows = List(300) { floatArrayOf(0f, 3f * random.nextFloat() - 1.5f, 0f, 0.5f * random.nextFloat() - 0.25f) }
        val found = SimBaker.principal(rows, 2)
        assertEquals(1f, abs(found[0].first[1]), 1e-3f)
        assertEquals(1f, abs(found[1].first[3]), 1e-3f)
        assertTrue(found[0].second > found[1].second)
    }

    @Test fun autoBakeBakesAgainOnlyWhenStale() {
        val base = strand()
        val first = SimAuthoring.rebaked(RigEditOverlay(simEdits = listOf(edit())), base, "hair")
        assertNull(first.second)
        val baked = first.first
        val bake = assertNotNull(baked.simEdits.single().bake)
        // Fresh: nothing to do.
        assertSame(baked, SimAuthoring.rebaked(baked, base, "hair").first)
        // A setup change bakes again, from the old pendulum.
        val changed = baked.copy(simEdits = baked.simEdits.map { it.copy(material = it.material.copy(bend = 0.7f)) })
        val again = SimAuthoring.rebaked(changed, base, "hair").first.simEdits.single()
        assertNotEquals(bake.fingerprint, again.bake?.fingerprint)
        assertFalse(SimBake.stale(SimAuthoring.unbakedModel(changed, base, "hair"), again))
        // Turned off, or failing, the old bake stays, stale.
        val off = changed.copy(simEdits = changed.simEdits.map { it.copy(autoBake = false) })
        assertSame(off, SimAuthoring.rebaked(off, base, "hair").first)
        val broken = changed.copy(simEdits = changed.simEdits.map { it.copy(inputs = listOf(PhysicsInput("ParamMissing"))) })
        val (kept, reason) = SimAuthoring.rebaked(broken, base, "hair")
        assertEquals(bake, kept.simEdits.single().bake)
        assertNotNull(reason)
    }

    @Test fun nelderMeadFindsAQuadraticMinimum() {
        val p = NelderMead.minimize(doubleArrayOf(0.0, 0.0), 1.0, { (x, y) -> (x - 3) * (x - 3) + 2 * (y + 1) * (y + 1) })
        assertEquals(3.0, p[0], 1e-3); assertEquals(-1.0, p[1], 1e-3)
    }
}
