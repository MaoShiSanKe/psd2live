package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOrigin
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigPhysicsEdit
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

    /** [strand] that ParamBodyAngleY also carries 30 px up or down. */
    private fun bouncingStrand(): PuppetModel {
        val base = strand()
        val drawable = base.drawables.single()
        val count = drawable.mesh!!.positions.size
        fun shift(dx: Float, dy: Float) = MeshDeltaForm(FloatArray(count) { if (it % 2 == 0) dx else dy })
        val keys = floatArrayOf(-30f, 0f, 30f)
        val ys = floatArrayOf(10f, 0f, -10f)
        val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
        for (j in 0..2) for (i in 0..2) cells += KeyformCell(intArrayOf(i, j), shift(keys[i], keys[2 - j]))
        val grid = KeyformGrid(listOf(KeyformAxis(angle, keys), KeyformAxis(bodyY, floatArrayOf(-10f, 0f, 10f))), cells)
        return base.copy(parameters = base.parameters + Parameter(bodyY, "Body Y", -10f, 10f, 0f),
            drawables = listOf(drawable.copy(geometryGrid = grid)))
    }
    private val bodyY = ParameterId("ParamBodyAngleY")

    @Test fun theBodyMovingUpAndDownBakesToAPendulumOfItsOwnFedAsATranslation() {
        val base = bouncingStrand()
        val edit = edit().copy(modes = 1, inputs = listOf(PhysicsInput(angle.raw, 100f, PhysicsSourceType.X), PhysicsInput(bodyY.raw, 100f, PhysicsSourceType.X)))
        val overlay = RigEditOverlay(simEdits = listOf(edit))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), edit, quick)
        // The swing stays with Angle X alone; up and down is a parameter of its own, driven by a pendulum
        // that takes Body Y as a translation, since a hanging pendulum's angles never answer it otherwise.
        assertEquals(listOf("ParamSimhair_1", "ParamSimhair_Y"), bake.parameters)
        assertEquals(listOf(angle.raw), bake.physics!!.inputs.map { it.parameter })
        val bounce = bake.extraPhysics.single { it.id == SimGenerator.verticalPhysicsId(edit) }
        assertEquals(listOf(bodyY.raw), bounce.inputs.map { it.parameter })
        assertTrue(bounce.inputs.all { it.type == PhysicsSourceType.X })
        assertEquals(listOf("ParamSimhair_Y"), bounce.outputs.map { it.parameter })
        val vertical = bake.modes.single { it.axis.parameter == "ParamSimhair_Y" }
        assertTrue(vertical.amplitude > 2f, "the strand lags up and down by ${vertical.amplitude} px")
        // Written back, it is named for the simulation and moves the strand.
        val baked = SimAuthoring.withBake(overlay, "hair", bake).applyTo(base)
        assertEquals("Hair Y", baked.parameters.single { it.id.raw == "ParamSimhair_Y" }.name)
    }

    private fun edit() = RigSimEdit("hair", "Hair", SimKind.HAIR, listOf("hair"),
        inputs = listOf(PhysicsInput(angle.raw, 100f, PhysicsSourceType.X)), exaggeration = 1f)

    private val quick = SimBaker.Options(duration = 1f)

    @Test fun bakeTurnsTheSwingIntoAParameterKeyformsAndAPendulum() {
        val base = strand()
        // Two modes: a strand this long whips, which one vertex's angle cannot carry alone.
        val overlay = RigEditOverlay(simEdits = listOf(edit().copy(modes = 2)))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        val mode = bake.modes.first()
        assertEquals("ParamSimhair_1", mode.axis.parameter)
        assertTrue(mode.amplitude > 5f, "the tip should lag visibly, lagged ${mode.amplitude} px")
        assertTrue(bake.fit > 0.55f, "the pendulum and keys should reproduce the simulation, R² ${bake.fit}")
        // The second mode adds the lag and bend the first cannot show instead of cancelling it.
        assertEquals(2, bake.modes.size)
        val single = RigEditOverlay(simEdits = listOf(edit().copy(modes = 1)))
        val one = SimBaker.bake(SimAuthoring.unbakedModel(single, base, "hair"), single.simEdits.single(), quick)
        assertTrue(bake.fit > one.fit + 0.03f, "two modes should follow the whip better than one: ${bake.fit} against ${one.fit}")
        assertTrue(bake.peak in 0.3f..0.999f && bake.clipped == 0f, "the modes use their range without stalling: ${bake.peak}, ${bake.clipped}")
        assertTrue(bake.jerk in 0.2f..1.6f, "the baked motion should be about as smooth as the simulation: ${bake.jerk}")
        val physics = assertNotNull(bake.physics)
        // Each mode is driven once: by a vertex of the pendulum, or by a pendulum of its own.
        assertEquals(bake.parameters.toSet(), bake.pendulums.flatMap { it.outputParameters }.toSet())
        assertEquals(bake.parameters.size, bake.pendulums.sumOf { it.outputParameters.size })
        assertEquals(2 - bake.extraPhysics.size, physics.segments.size)
        // The root is pinned: nothing is keyed there.
        val offsets = mode.axis.offsets.getValue("hair")
        for (key in offsets) { assertEquals(0f, key[0], 1e-4f); assertEquals(0f, key[2], 1e-4f) }

        // As blend shapes the modes add to the grid instead of multiplying it.
        val baked = SimAuthoring.withBake(RigEditOverlay(simEdits = listOf(edit().copy(modes = 2, blendShapes = true))), "hair", bake)
        val model = baked.applyTo(base)
        val parameter = model.parameters.single { it.id.raw == mode.axis.parameter }
        assertEquals(ParameterKind.BLEND_SHAPE, parameter.kind)
        assertEquals(-SimGenerator.MODE_RANGE to SimGenerator.MODE_RANGE, parameter.min to parameter.max)
        val drawable = model.drawables.single()
        assertEquals(3, drawable.geometryGrid!!.cells.size)
        assertEquals(bake.parameters.toSet(), drawable.blendShapes.map { it.parameterId.raw }.toSet())
        assertContentEquals(floatArrayOf(-30f, -15f, 0f, 15f, 30f), drawable.blendShapes.first().keys)
        // Rebuilding twice gives the same rig: the bake is written back, never simulated again.
        val rebuilt = baked.applyTo(base).drawables.single().blendShapes
        assertEquals(drawable.blendShapes.flatMap { b -> b.forms.flatMap { it?.positionDeltas?.toList().orEmpty() } },
            rebuilt.flatMap { b -> b.forms.flatMap { it?.positionDeltas?.toList().orEmpty() } })

        // As keyform axes each mode multiplies the grid by its keys, and the rig moves the same.
        val axes = SimAuthoring.withBake(RigEditOverlay(simEdits = listOf(edit().copy(modes = 2, blendShapes = false))), "hair", bake)
        val gridModel = axes.applyTo(base)
        val grid = gridModel.drawables.single().geometryGrid!!
        assertTrue(grid.axisIndexOf(parameter.id) >= 0 && grid.axisIndexOf(angle) >= 0)
        assertEquals(3 * bake.modes.fold(1) { n, _ -> n * 5 }, grid.cells.size)
        val evaluator = CpuDeformationEvaluator()
        for (pose in listOf(mapOf(parameter.id to 21f), mapOf(parameter.id to -30f, angle to 20f))) {
            val a = evaluator.evaluate(model, pose).worldPositions.getValue(DrawableId("hair"))
            val b = evaluator.evaluate(gridModel, pose).worldPositions.getValue(DrawableId("hair"))
            for (i in a.indices) assertEquals(a[i], b[i], 0.05f, "blend shapes and keyform axes agree at $pose")
        }

        val groups = PhysicsCatalog.groups(PhysicsGenerator.Presets(false, false, false), PhysicsGenerator.Presets(false, false, false), baked, model.parameters.mapTo(HashSet()) { it.id.raw })
        val group = groups.single { it.origin == PhysicsOrigin.SIMULATION }
        assertTrue(group.active, "the fitted pendulum exports: ${group.issue?.message}")
        assertEquals(physics.outputParameters, group.setting.outputParameters)
        assertEquals("hair", SimGenerator.simulationOf(group.id, baked.simEdits)?.id)
    }

    @Test fun theBakedModelFollowsTheSimulation() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit()))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        val model = SimAuthoring.withBake(overlay, "hair", bake).applyTo(base)
        val engine = PhysicsEngine(bake.pendulums, PhysicsEngine.ranges(model.parameters))
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
        val tuned = sim.copy(keys = 7, blendShapes = false, autoBake = false, exaggeration = 1.75f)
        assertEquals(tuned.copy(blendShapes = true), RigSimEdit.fromJson(Json.parseToJsonElement(tuned.copy(blendShapes = true).toJson().toString()).jsonObject))
        assertNull(tuned.patched(buildJsonObject { put("blend_shapes", JsonNull) }).blendShapes)
        assertEquals(tuned, RigSimEdit.fromJson(Json.parseToJsonElement(tuned.toJson().toString()).jsonObject))
        // How the modes are written is not part of what the bake depends on.
        assertEquals(SimBake.fingerprint(base, sim), SimBake.fingerprint(base, sim.copy(blendShapes = false, autoBake = false, exaggeration = 2f)))
        assertEquals(bake, SimBakeResult.fromJson(Json.parseToJsonElement(bake.toJson().toString()).jsonObject))
        assertFailsWith<IllegalArgumentException> { sim.copy(exaggeration = 3f) }
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

    @Test fun theMotionLibraryMovesEveryInputFullyInPiecesThatEndAtRest() {
        val pieces = SimMotionLibrary.training(2, 60)
        for (piece in pieces) {
            assertEquals(2, piece.size)
            for (values in piece) { assertEquals(0f, values.first(), 0.1f); assertEquals(0f, values.last(), 1e-6f) }
        }
        for (i in 0 until 2) {
            val all = pieces.flatMap { it[i].asList() }
            assertEquals(1f, all.max(), 1e-3f); assertEquals(-1f, all.min(), 1e-3f)
            // Everyday use is quick: somewhere an input crosses half its range within a fifth of a second.
            val fastest = pieces.maxOf { piece -> (12 until piece[i].size).maxOf { abs(piece[i][it] - piece[i][it - 12]) } }
            assertTrue(fastest > 0.5f, "the library should move quickly, fastest ${fastest}")
        }
        val heldOut = SimMotionLibrary.heldOut(2, 60)
        assertTrue(heldOut.all { abs(it.first()) < 0.1f && abs(it.last()) < 1e-6f })
        assertTrue(pieces.none { piece -> piece[0].contentEquals(heldOut[0]) })
    }

    @Test fun exaggerationScalesTheModesAsTheyAreWrittenBack() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit().copy(exaggeration = 1f, blendShapes = false)))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        val plain = SimAuthoring.withBake(overlay, "hair", bake)
        val louder = plain.copy(simEdits = plain.simEdits.map { it.copy(exaggeration = 1.5f) })
        // Not part of what the bake depends on: changing it needs no new bake.
        assertFalse(SimBake.stale(louder.applyTo(base), louder.simEdits.single()))
        val parameter = ParameterId(bake.parameters.first())
        val evaluator = CpuDeformationEvaluator()
        val rest = evaluator.evaluate(base, emptyMap()).worldPositions.getValue(DrawableId("hair"))
        fun moved(o: RigEditOverlay) = evaluator.evaluate(o.applyTo(base), mapOf(parameter to 24f)).worldPositions.getValue(DrawableId("hair"))
        val a = moved(plain); val b = moved(louder)
        for (i in rest.indices) assertEquals(1.5f * (a[i] - rest[i]), b[i] - rest[i], 0.05f)
    }

    @Test fun hardMotionDoesNotPinTheModesAtTheirEnds() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit()))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        val model = SimAuthoring.withBake(overlay, "hair", bake).applyTo(base)
        val engine = PhysicsEngine(listOfNotNull(bake.physics), PhysicsEngine.ranges(model.parameters))
        // The head flung from side to side over 0.15 s every half second: a parameter stuck at ±1 stalls the hair.
        var most = 0f
        for (f in 0 until 300) {
            val target = if ((f / 30) % 2 == 0) 30f else -30f
            val t = ((f % 30) / 9f).coerceAtMost(1f)
            val value = -target + 2f * target * t * t * (3f - 2f * t)
            most = maxOf(most, engine.step(mapOf(angle.raw to value), 1f / 60f).values.maxOf { abs(it) })
        }
        assertTrue(most < 0.999f * SimGenerator.MODE_RANGE, "the modes should keep room for hard motion, reached $most")
    }

    @Test fun modesAreBlendShapesOnlyWhereAxesWouldMultiplyTooFar() {
        val base = strand()
        // 3 keyforms × 5 keys stays small; 3 × 9 × 9 × 9 does not.
        assertFalse(SimGenerator.usesBlendShapes(base, edit().copy(modes = 1)))
        assertTrue(SimGenerator.usesBlendShapes(base, edit().copy(modes = 3, keys = 9)))
        assertTrue(SimGenerator.usesBlendShapes(base, edit().copy(modes = 1, blendShapes = true)))
        assertFalse(SimGenerator.usesBlendShapes(base, edit().copy(modes = 3, keys = 9, blendShapes = false)))
        assertFalse(SimGenerator.usesBlendShapes(base.copy(runtimeTarget = RuntimeTarget.Cubism40), edit().copy(modes = 1, blendShapes = true)))
    }

    @Test fun keysSetHowManyKeysModesAndStaticAxesGet() {
        val base = strand()
        val overlay = RigEditOverlay(simEdits = listOf(edit().copy(keys = 3)))
        val bake = SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "hair"), overlay.simEdits.single(), quick)
        for (mode in bake.modes) assertContentEquals(floatArrayOf(-30f, 0f, 30f), mode.axis.keys)
        assertContentEquals(floatArrayOf(-30f, -15f, 0f, 15f, 30f), SimBaker.staticKeys(Parameter(angle, "x", -30f, 30f, 0f), 5))
        assertContentEquals(floatArrayOf(0f, 0.5f, 1f), SimBaker.staticKeys(Parameter(angle, "x", 0f, 1f, 0f), 5))
        assertFailsWith<IllegalArgumentException> { edit().copy(keys = 4) }
    }

    @Test fun keysAreSolvedTogetherAndFollowACurve() {
        // One mode swinging -1..1 whose vertex moves along a parabola: straight keys would cut the arc.
        val played = FloatArray(400) { kotlin.math.sin(it * 0.05f) }
        val residuals = played.map { u -> floatArrayOf(10f * u, 6f * u * u) }
        val keys = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val shapes = SimBaker.solveKeys(residuals, listOf(played), listOf(keys), 2).single()
        // The straight part is kept exactly; the arc is kept too, a little flattened so the shapes turn no sharp corner at a key.
        for ((k, key) in keys.withIndex()) assertEquals(10f * key, shapes[k][0], 0.3f)
        assertEquals(shapes[0][1], shapes[4][1], 0.2f); assertEquals(shapes[1][1], shapes[3][1], 0.2f)
        assertTrue(shapes[4][1] > 4f && shapes[3][1] in 0.5f..2f, "the keys follow the arc: ${shapes.map { it[1] }}")
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

    @Test fun aLaterModeWithAPendulumOfItsOwnRoundTripsAndExports() {
        fun pendulum(id: String, output: String) = RigPhysicsEdit(id, "Hair", inputs = listOf(PhysicsInput(angle.raw, 50f, PhysicsSourceType.ANGLE)),
            outputs = listOf(PhysicsOutput(output, 1, 1f)), segments = listOf(PhysicsSegment(10f, 0.9f, 0.8f, 1f)))
        val axis = { parameter: String -> SimBakedAxis(parameter, floatArrayOf(-30f, 0f, 30f), mapOf("hair" to List(3) { FloatArray(4) })) }
        val bake = SimBakeResult("f", mapOf("hair" to 2), emptyList(),
            listOf(SimBakedMode(axis("ParamSimhair_1"), 5f, 0.5f), SimBakedMode(axis("ParamSimhair_2"), 2f, 0.1f)),
            physics = pendulum("PhysicsSim_hair", "ParamSimhair_1"), extraPhysics = listOf(pendulum("PhysicsSim_hair_2", "ParamSimhair_2")))
        assertEquals(bake, SimBakeResult.fromJson(bake.toJson()))
        assertEquals(listOf("PhysicsSim_hair_2"), SimBakeResult.fromJson(bake.toJson()).extraPhysics.map { it.id })
        // Older bakes carry none.
        assertTrue(SimBakeResult.fromJson(JsonObject(bake.toJson() - "extra_physics")).extraPhysics.isEmpty())
        val sims = listOf(edit().copy(bake = bake))
        val rules = SimGenerator.physicsRules(sims, setOf(angle.raw, "ParamSimhair_1", "ParamSimhair_2"))
        assertEquals(listOf("PhysicsSim_hair", "PhysicsSim_hair_2"), rules.map { it.id })
        assertEquals("hair", SimGenerator.simulationOf("PhysicsSim_hair_2", sims)?.id)
        assertTrue(SimGenerator.physicsRules(sims.map { it.copy(enabled = false) }, setOf(angle.raw, "ParamSimhair_1", "ParamSimhair_2")).isEmpty())
    }

    @Test fun backfittingTakesBackWhatTheFirstModeTookFromTheSecond() {
        // Two modes playing nearly alike; solved one after the other, the first takes some of the second's share.
        val first = FloatArray(600) { kotlin.math.sin(it * 0.05f) }
        val second = FloatArray(600) { kotlin.math.sin(it * 0.05f + 0.6f) }
        val residuals = first.indices.map { f -> floatArrayOf(10f * first[f] + 6f * second[f], 0f) }
        val keys = floatArrayOf(-1f, 0f, 1f)
        fun error(shapes: List<List<FloatArray>>) = residuals.indices.sumOf { f ->
            val v = SimBaker.at(shapes[0], keys, first[f])[0] + SimBaker.at(shapes[1], keys, second[f])[0]
            ((residuals[f][0] - v) * (residuals[f][0] - v)).toDouble()
        }
        val sequential = SimBaker.solveModes(residuals, listOf(first, second), keys, 2)
        val refit = SimBaker.backfit(residuals, listOf(first, second), keys, sequential, 2)
        assertTrue(error(refit) < error(sequential) * 0.8, "backfit ${error(refit)} against ${error(sequential)}")
    }

    @Test fun nelderMeadFindsAQuadraticMinimum() {
        val p = NelderMead.minimize(doubleArrayOf(0.0, 0.0), 1.0, { (x, y) -> (x - 3) * (x - 3) + 2 * (y + 1) * (y + 1) })
        assertEquals(3.0, p[0], 1e-3); assertEquals(-1.0, p[1], 1e-3)
    }
}
