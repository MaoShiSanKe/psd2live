package io.github.psd2live.core.sim

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceStore

import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.VertexGroupJournal
import kotlinx.serialization.json.*
import org.umamo.edit.MeshRefinementOps
import org.umamo.edit.withMeshTopologyEdit
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*

class SimulationTest {
    /** A grid [columns] x [rows] cells of [cell] px, top-left at ([left], [top]), rows top to bottom. */
    private fun grid(columns: Int, rows: Int, cell: Float, left: Float = 0f, top: Float = 0f): DrawableMesh {
        val positions = ArrayList<Float>()
        for (r in 0..rows) for (c in 0..columns) { positions += left + c * cell; positions += top + r * cell }
        val indices = ArrayList<Int>()
        for (r in 0 until rows) for (c in 0 until columns) {
            val a = r * (columns + 1) + c
            val b = a + 1
            val d = a + columns + 1
            val e = d + 1
            indices += listOf(a, b, e, a, e, d)
        }
        val p = positions.toFloatArray()
        return DrawableMesh(p, FloatArray(p.size) { p[it] / 1000f }, indices.toIntArray())
    }

    /** [mesh]'s rest vertices in the evaluator's world space, where the simulation runs (y negated). */
    private fun world(mesh: DrawableMesh) = FloatArray(mesh.positions.size) { if (it % 2 == 0) mesh.positions[it] else -mesh.positions[it] }

    private fun drawable(id: String, mesh: DrawableMesh) = Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), mesh, null)

    private fun model(vararg drawables: Drawable, glues: List<Glue> = emptyList(), groups: List<VertexGroup> = emptyList()) =
        PuppetModel(emptyList(), emptyList(), emptyList(), drawables.toList(), drawables.map { OrgChild.Drawable(it.id) }, null,
            glues = glues, vertexGroups = groups)

    /** Top row of a [columns]-wide grid mesh fully pinned. */
    private fun topPin(id: String, mesh: DrawableMesh, columns: Int) =
        VertexGroup("pin", DrawableId(id), VertexGroupKind.PIN, FloatArray(mesh.vertexCount) { if (it <= columns) 1f else 0f })

    // Vertex groups

    @Test fun vertexGroupFollowsATopologyEdit() {
        val mesh = grid(1, 1, 10f)
        val group = VertexGroup("pin", DrawableId("a"), VertexGroupKind.PIN, floatArrayOf(1f, 1f, 0f, 0f))
        val source = model(drawable("a", mesh), groups = listOf(group))
        // A point in the middle of the quad: its weight is interpolated from the corners around it.
        val inserted = requireNotNull(MeshRefinementOps.insertPoints(mesh, mesh.positions, listOf(5f to 2.5f), extend = false, edgeSnap = 0f))
        val edited = source.withMeshTopologyEdit(DrawableId("a"), requireNotNull(inserted.result?.edit))
        val weights = edited.vertexGroups.single().weights
        assertEquals(5, weights.size)
        assertContentEquals(floatArrayOf(1f, 1f, 0f, 0f), weights.copyOf(4))
        assertTrue(abs(weights[4] - 0.75f) < 0.02f, "interpolated weight ${weights[4]}")
    }

    @Test fun vertexGroupJournalRoundTripsAndCompilesRules() {
        val mesh = grid(2, 4, 10f)
        val source = model(drawable("skirt", mesh))
        val rule = buildJsonObject {
            put("op", "vertex_group_rule"); put("target", "mesh:skirt"); put("name", "stiff"); put("kind", "stiffness")
            put("rule", "gradient"); putJsonArray("from") { add(0f); add(0f) }; putJsonArray("to") { add(0f); add(40f) }
            put("start", 1f); put("end", 0.2f)
        }
        val (compiled, journal) = RigAuthoringJournal.compile(source, JsonArray(listOf(rule)))
        assertEquals(VertexGroupJournal.PUT, journal.single()["op"]!!.jsonPrimitive.content)
        val weights = compiled.vertexGroups.single().weights
        assertEquals(1f, weights[0]); assertEquals(0.2f, weights.last(), 1e-4f)
        // Replaying the materialized put gives the same model; a second identical put is a no-op.
        val replayed = RigAuthoringJournal.apply(source, journal.single())
        assertEquals(compiled.vertexGroups, replayed.vertexGroups)
        val (_, again) = RigAuthoringJournal.compile(compiled, JsonArray(journal))
        assertTrue(again.isEmpty())
        val deleted = RigAuthoringJournal.apply(compiled, VertexGroupJournal.delete("skirt", "stiff"))
        assertTrue(deleted.vertexGroups.isEmpty())
    }

    @Test fun vertexGroupsResampleOntoARebuiltMesh() {
        val coarse = grid(1, 2, 20f)
        val fine = grid(2, 4, 10f)
        val group = VertexGroup("pin", DrawableId("a"), VertexGroupKind.PIN, FloatArray(coarse.vertexCount) { if (it < 2) 1f else 0f })
        val resampled = VertexGroupJournal.resample(group, coarse, fine)
        assertEquals(fine.vertexCount, resampled.weights.size)
        assertEquals(1f, resampled.weights[0]); assertEquals(0.5f, resampled.weights[3], 1e-4f); assertEquals(0f, resampled.weights.last())
    }

    // Solver

    private fun hangingScene(source: PuppetModel, kind: SimKind = SimKind.CLOTH, edit: (RigSimEdit) -> RigSimEdit = { it }) =
        SimScene.build(source, edit(RigSimEdit("s", "s", kind, listOf("cloth")))).also { it.reset(source, emptyMap()) }

    @Test fun pinnedClothHangsWithoutStretchingAndIsDeterministic() {
        val mesh = grid(3, 6, 10f)
        val source = model(drawable("cloth", mesh), groups = listOf(topPin("cloth", mesh, 3)))
        fun run(): Pair<FloatArray, Float> {
            val scene = hangingScene(source)
            scene.calibrate(source)
            var worst = 0f
            // Blow it sideways, then let it settle back to hanging as drawn.
            scene.solver.settings = scene.solver.settings.copy(windX = 2000f)
            repeat(30) { scene.drive(source, emptyMap(), 1f / 60f); worst = maxOf(worst, scene.solver.maxStretch()) }
            scene.solver.settings = scene.solver.settings.copy(windX = 0f)
            repeat(240) { scene.drive(source, emptyMap(), 1f / 60f); worst = maxOf(worst, scene.solver.maxStretch()) }
            return scene.positions(DrawableId("cloth"))!! to worst
        }
        val (first, stretch) = run()
        val (second, _) = run()
        assertContentEquals(first, second, "two runs must be bit-identical")
        assertTrue(stretch < 0.03f, "cloth stretched by ${stretch * 100}%")
        // The pinned row did not move and the hem hangs straight below it once settled.
        val rest = world(mesh)
        for (c in 0..3) { assertEquals(rest[c * 2], first[c * 2], 1e-6f); assertEquals(rest[c * 2 + 1], first[c * 2 + 1], 1e-6f) }
        val hem = 6 * 4 + 1
        assertTrue(abs(first[hem * 2] - rest[hem * 2]) < 2f, "hem settled at x=${first[hem * 2]}")
    }

    @Test fun aTautHangingStrandNeedsNoGoalToStayAsDrawn() {
        val mesh = grid(1, 8, 10f)
        val source = model(drawable("cloth", mesh), groups = listOf(topPin("cloth", mesh, 1)))
        val scene = hangingScene(source, SimKind.HAIR) { it.copy(material = it.material.copy(goal = 0f)) }
        repeat(60) { scene.drive(source, emptyMap(), 1f / 60f) }
        val still = scene.positions(DrawableId("cloth"))!!
        val rest = world(mesh)
        val worst = (0 until mesh.vertexCount * 2).maxOf { abs(still[it] - rest[it]) }
        assertTrue(worst < 0.5f, "a hanging strand is already in equilibrium, moved $worst px")
    }

    @Test fun calibrationHoldsAStrandDrawnSidewaysAndItSpringsBack() {
        // Sticking out to the right from a pin on its left end: gravity alone would fold it down.
        val mesh = grid(8, 1, 10f)
        val pin = VertexGroup("pin", DrawableId("cloth"), VertexGroupKind.PIN, FloatArray(mesh.vertexCount) { if (it % 9 == 0) 1f else 0f })
        val source = model(drawable("cloth", mesh), groups = listOf(pin))
        val scene = hangingScene(source, SimKind.HAIR) { it.copy(material = it.material.copy(goal = 0.4f)) }
        val rest = world(mesh)
        val tip = 8
        repeat(90) { scene.drive(source, emptyMap(), 1f / 60f) }
        val sag = rest[tip * 2 + 1] - scene.state.y[tip]
        assertTrue(sag > 5f, "without calibration the tip should droop, drooped $sag px")

        val residual = scene.calibrate(source)
        assertTrue(residual < 1f, "calibration left $residual px")
        repeat(90) { scene.drive(source, emptyMap(), 1f / 60f) }
        assertTrue(abs(rest[tip * 2 + 1] - scene.state.y[tip]) < 1.5f, "calibrated tip settled at ${scene.state.y[tip]}, drawn at ${rest[tip * 2 + 1]}")

        scene.solver.settings = scene.solver.settings.copy(windY = -3000f)
        repeat(20) { scene.drive(source, emptyMap(), 1f / 60f) }
        assertTrue(rest[tip * 2 + 1] - scene.state.y[tip] > 5f, "wind should push the tip down")
        scene.solver.settings = scene.solver.settings.copy(windY = 0f)
        repeat(300) { scene.drive(source, emptyMap(), 1f / 60f) }
        assertTrue(abs(rest[tip * 2 + 1] - scene.state.y[tip]) < 1.5f, "the tip springs back, at ${scene.state.y[tip]}")
    }

    @Test fun longRangeLimitHoldsTheTipUnderAHardJerk() {
        val mesh = grid(1, 10, 10f)
        val source = model(drawable("cloth", mesh), groups = listOf(topPin("cloth", mesh, 1)))
        val scene = hangingScene(source, SimKind.HAIR) { it.copy(material = it.material.copy(stretch = 0.3f, goal = 0f, slack = 0.02f)) }
        scene.solver.settings = scene.solver.settings.copy(windY = -20000f)
        repeat(20) { scene.drive(source, emptyMap(), 1f / 60f) }
        val tip = scene.positions(DrawableId("cloth"))!!
        val tipIndex = 10 * 2
        val distance = hypot(tip[tipIndex * 2] - mesh.positions[0], tip[tipIndex * 2 + 1] + mesh.positions[1])
        assertTrue(distance <= 100f * 1.02f + 0.5f, "tip reached $distance px from the root, limit ${100f * 1.02f}")
    }

    // Material model

    /** The hem's mean sideways offset per frame as a gust blows a [columns] x [rows] cloth 40 x 120 px and lets it go. */
    private fun gust(columns: Int, rows: Int, jitter: Float = 0f): FloatArray {
        val mesh = grid(columns, rows, 40f / columns).let { m ->
            if (jitter == 0f) m else {
                // Inner vertices moved off the grid, the outline kept: an irregular cut of the same cloth.
                val random = java.util.Random(3L)
                val p = m.positions.copyOf()
                for (r in 1 until rows) for (c in 1 until columns) {
                    val v = r * (columns + 1) + c
                    p[v * 2] += (random.nextFloat() - 0.5f) * jitter * 40f / columns
                    p[v * 2 + 1] += (random.nextFloat() - 0.5f) * jitter * 40f / columns
                }
                DrawableMesh(p, m.uvs, m.indices)
            }
        }
        val source = model(drawable("cloth", mesh), groups = listOf(topPin("cloth", mesh, columns)))
        val scene = hangingScene(source)
        scene.calibrate(source)
        val hem = (0..columns).map { rows * (columns + 1) + it }
        val rest = hem.sumOf { mesh.positions[it * 2].toDouble() } / hem.size
        return FloatArray(150) { f ->
            scene.solver.settings = scene.solver.settings.copy(windX = if (f < 20) 1500f else 0f)
            scene.drive(source, emptyMap(), 1f / 60f)
            (hem.sumOf { scene.state.x[it].toDouble() } / hem.size - rest).toFloat()
        }
    }

    @Test fun aFinerOrIrregularMeshIsTheSameCloth() {
        val coarse = gust(2, 6)
        val peak = coarse.maxOf(::abs)
        assertTrue(peak > 5f, "the gust should move the hem, moved $peak px")
        for ((name, other) in listOf("fine" to gust(4, 12), "irregular" to gust(4, 12, jitter = 0.5f))) {
            val rms = kotlin.math.sqrt(coarse.indices.sumOf { ((coarse[it] - other[it]) * (coarse[it] - other[it])).toDouble() } / coarse.size)
            assertTrue(rms < 0.2f * peak, "the $name mesh moves differently: ${"%.2f".format(rms)} px RMS against a ${"%.1f".format(peak)} px swing")
        }
    }

    @Test fun trianglesNeverTurnOverAndAreaHoldsAsMuchAsAsked() {
        val mesh = grid(3, 6, 10f)
        val source = model(drawable("cloth", mesh), groups = listOf(topPin("cloth", mesh, 3)))
        fun squeeze(area: Float): Float {
            val scene = hangingScene(source) { it.copy(material = it.material.copy(area = area, goal = 0f)) }
            // Blown up into its own pins and across: the sheet folds hard on itself.
            scene.solver.settings = scene.solver.settings.copy(windX = 4000f, windY = 6000f)
            var least = 1f
            repeat(60) { scene.drive(source, emptyMap(), 1f / 60f); least = minOf(least, scene.solver.minAreaRatio()) }
            return least
        }
        val soft = squeeze(0f)
        val firm = squeeze(0.9f)
        assertTrue(soft > 0f, "a triangle turned over: area ratio $soft")
        assertTrue(firm > soft, "keeping area should keep it: $firm against $soft")
    }

    @Test fun theRestShapeFollowsTheRig() {
        // ParamScale stretches the strand to 1.3 times its length; hanging taut, it should just take that length.
        val mesh = grid(1, 8, 10f)
        val scale = ParameterId("ParamScale")
        fun stretched(k: Float) = MeshDeltaForm(FloatArray(mesh.positions.size) { if (it % 2 == 1) mesh.positions[it] * (k - 1f) else 0f })
        val keyed = Drawable(DrawableId("cloth"), "cloth", null, BlendMode.Normal, emptyList(), mesh,
            KeyformGrid(listOf(KeyformAxis(scale, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), stretched(1f)), KeyformCell(intArrayOf(1), stretched(1.3f)))))
        val source = PuppetModel(listOf(Parameter(scale, "Scale", 0f, 1f, 0f)), emptyList(), emptyList(), listOf(keyed),
            listOf(OrgChild.Drawable(keyed.id)), null, vertexGroups = listOf(topPin("cloth", mesh, 1)))
        val scene = hangingScene(source, SimKind.HAIR) { it.copy(material = it.material.copy(goal = 0f, slack = 0.5f)) }
        val pose = mapOf(scale to 1f)
        repeat(240) { scene.drive(source, pose, 1f / 60f) }
        val worst = (0 until mesh.vertexCount).maxOf { hypot(scene.state.x[it] - scene.state.goalX[it], scene.state.y[it] - scene.state.goalY[it]) }
        assertTrue(worst < 3f, "the strand should hang at the rig's length, $worst px off")
    }

    @Test fun bendGradientMatchesFiniteDifferences() {
        val state = SimState(4)
        state.reset(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f))
        val solver = XpbdSolver(state, triangles = TriangleConstraints(intArrayOf(0, 1), intArrayOf(1, 3), intArrayOf(2, 2), floatArrayOf(1f, 1f)))
        // Bent and squashed a little away from rest.
        val moved = floatArrayOf(0.5f, -0.3f, 10.4f, 1.2f, -0.8f, 9.1f, 9.3f, 11.7f)
        for (i in 0 until 4) { state.x[i] = moved[i * 2]; state.y[i] = moved[i * 2 + 1] }
        for (t in 0..1) {
            val (angle, grad) = solver.turnOf(t)
            val corners = if (t == 0) intArrayOf(0, 1, 2) else intArrayOf(1, 3, 2)
            for ((slot, v) in corners.withIndex()) for (axis in 0..1) {
                val eps = 1e-2f
                val coords = if (axis == 0) state.x else state.y
                coords[v] += eps
                val plus = solver.turnOf(t).first
                coords[v] -= 2 * eps
                val minus = solver.turnOf(t).first
                coords[v] += eps
                val numeric = (plus - minus) / (2 * eps)
                assertEquals(numeric, grad[slot * 2 + axis], 2e-3f, "triangle $t corner $v axis $axis at angle $angle")
            }
        }
    }

    @Test fun theGrainIsStifferAlongThanAcross() {
        val mesh = grid(3, 6, 10f)
        val source = model(drawable("cloth", mesh), groups = listOf(topPin("cloth", mesh, 3)))
        val scene = hangingScene(source) { it.copy(material = it.material.copy(anisotropy = 1f)) }
        val stretch = scene.solver.stretch
        val world = world(mesh)
        fun mean(vertical: Boolean) = (0 until stretch.size).filter { k ->
            val dx = abs(world[stretch.a[k] * 2] - world[stretch.b[k] * 2]); val dy = abs(world[stretch.a[k] * 2 + 1] - world[stretch.b[k] * 2 + 1])
            if (vertical) dx < 1e-3f else dy < 1e-3f
        }.map { stretch.compliance[it] }.average()
        // The grain runs down from the pinned top: hanging edges keep their length, the ones across give.
        assertTrue(mean(vertical = false) > 10 * mean(vertical = true), "across ${mean(false)}, along ${mean(true)}")
    }

    @Test fun anOlderMaterialTakesTheNewValuesFromItsKind() {
        val old = buildJsonObject {
            put("id", "h"); put("kind", "hair"); putJsonArray("targets") { add("a") }
            putJsonObject("material") { put("mass", 1f); put("stretch", 1f); put("bend", 0.4f); put("damping", 2f); put("goal", 0.15f); put("slack", 0.01f) }
        }
        val material = RigSimEdit.fromJson(old).material
        assertEquals(SimMaterial.preset(SimKind.HAIR).area, material.area)
        assertEquals(SimMaterial.preset(SimKind.HAIR).anisotropy, material.anisotropy)
        assertEquals(0.4f, material.bend)
    }

    @Test fun materialPresetsFillInTheirValuesAndFieldsOverrideThem() {
        for (kind in SimKind.entries) {
            assertEquals(SimMaterialPreset.default(kind).material, SimMaterial.preset(kind))
            assertTrue(SimMaterialPreset.default(kind) in SimMaterialPreset.of(kind))
        }
        assertEquals(SimMaterialPreset.entries.size, SimMaterialPreset.entries.map { it.material }.distinct().size, "presets must be told apart")
        val edit = RigSimEdit("skirt", "Skirt", SimKind.CLOTH, listOf("a"))
        assertEquals(SimMaterialPreset.SILK.material, edit.patched(buildJsonObject { put("material_preset", "silk") }).material)
        val leather = edit.patched(buildJsonObject { put("material_preset", "leather"); putJsonObject("material") { put("goal", 0.5f) } }).material
        assertEquals(SimMaterialPreset.LEATHER.material.copy(goal = 0.5f), leather)
        assertNull(SimMaterialPreset.matching(leather))
        // Only the values persist: a reopened edit names the preset again by matching them.
        val reopened = RigSimEdit.fromJson(edit.patched(buildJsonObject { put("material_preset", "chiffon") }).toJson())
        assertEquals(SimMaterialPreset.CHIFFON, SimMaterialPreset.matching(reopened.material))
        assertFailsWith<IllegalArgumentException> { edit.patched(buildJsonObject { put("material_preset", "velvet") }) }
    }

    @Test fun silkBillowsFurtherThanLeatherInTheSameWind() {
        val mesh = grid(3, 6, 10f)
        val source = model(drawable("cloth", mesh), groups = listOf(topPin("cloth", mesh, 3)))
        fun hemDrift(preset: SimMaterialPreset): Float {
            val scene = hangingScene(source) { it.copy(material = preset.material) }
            scene.calibrate(source)
            scene.solver.settings = scene.solver.settings.copy(windX = 1500f)
            repeat(45) { scene.drive(source, emptyMap(), 1f / 60f) }
            val hem = 6 * 4 + 1
            return scene.positions(DrawableId("cloth"))!![hem * 2] - world(mesh)[hem * 2]
        }
        val silk = hemDrift(SimMaterialPreset.SILK)
        val leather = hemDrift(SimMaterialPreset.LEATHER)
        assertTrue(silk > leather * 1.2f, "silk hem moved $silk px, leather $leather px")
    }

    // Glue roles

    private fun gluedPair(role: GlueRole): Pair<PuppetModel, SimScene> {
        // A waistband above a skirt; they share their seam row through a glue.
        val band = grid(3, 1, 10f, top = -10f)
        val skirt = grid(3, 5, 10f)
        val glue = Glue(DrawableId("band"), DrawableId("skirt"), (0..3).map { GluePair(4 + it, it, 0.5f, 0.5f) })
        val source = model(drawable("band", band), drawable("skirt", skirt), glues = listOf(glue))
        val edit = RigSimEdit("s", "s", SimKind.CLOTH, listOf("skirt"), glueRoles = mapOf(glueKey(glue) to role))
        return source to SimScene.build(source, edit).also { it.reset(source, emptyMap()) }
    }

    @Test fun aGlueIsNoPinUnlessToldSo() {
        val (source, scene) = gluedPair(GlueRole.IGNORE)
        assertTrue(scene.notes.any { it.startsWith("Nothing is pinned") })
        scene.solver.settings = scene.solver.settings.copy(windX = 2000f)
        repeat(30) { scene.drive(source, emptyMap(), 1f / 60f) }
        assertTrue(scene.positions(DrawableId("skirt"))!![0] > 5f, "an ignored glue must not hold the skirt")
    }

    @Test fun aGlueSetToPinHoldsTheSeamOnTheOtherMesh() {
        val (source, scene) = gluedPair(GlueRole.PIN)
        assertTrue(scene.notes.none { it.startsWith("Nothing is pinned") })
        scene.solver.settings = scene.solver.settings.copy(windX = 2000f)
        repeat(120) { scene.drive(source, emptyMap(), 1f / 60f) }
        val skirt = scene.positions(DrawableId("skirt"))!!
        for (c in 0..3) assertTrue(hypot(skirt[c * 2] - c * 10f, skirt[c * 2 + 1]) < 0.01f, "seam vertex $c drifted to ${skirt[c * 2]}, ${skirt[c * 2 + 1]}")
    }

    @Test fun simEditJsonRoundTrips() {
        val edit = RigSimEdit("skirt", "Skirt", SimKind.CLOTH, listOf("a", "b"),
            groups = mapOf(VertexGroupKind.PIN to "waist"), glueRoles = mapOf("a|c" to GlueRole.PIN, "a|b" to GlueRole.CONSTRAINT))
        assertEquals(edit, RigSimEdit.fromJson(edit.toJson()))
        assertEquals(SimMaterial.preset(SimKind.HAIR), edit.patched(buildJsonObject { put("kind", "hair") }).material)
    }

    @Test fun retiredCollisionSettingsAreDroppedOnLoad() {
        val old = buildJsonObject {
            put("id", "skirt"); put("name", "Skirt"); put("kind", "cloth"); putJsonArray("targets") { add("cloth") }
            putJsonObject("groups") { put("pin", "waist"); put("collide", "sides") }
            putJsonArray("colliders") { addJsonObject { put("mesh", "leg"); put("margin", 2f) } }
        }
        assertEquals(mapOf(VertexGroupKind.PIN to "waist"), RigSimEdit.fromJson(old).groups)
        val cloth = grid(2, 2, 10f)
        val source = model(drawable("cloth", cloth))
        val put = buildJsonObject {
            put("op", "vertex_group_put"); put("target", "mesh:cloth"); put("name", "sides"); put("kind", "collide")
            putJsonArray("weights") { repeat(cloth.vertexCount) { add(1f) } }
        }
        val loaded = VertexGroupJournal.apply(source, put)
        assertTrue(loaded.vertexGroups.isEmpty())
        assertTrue(VertexGroupJournal.apply(loaded, VertexGroupJournal.delete("cloth", "sides")).vertexGroups.isEmpty())
    }

    // Authoring and persistence

    @Test fun authoringValidatesAndReports() {
        val band = grid(3, 1, 10f, top = -10f)
        val skirt = grid(3, 5, 10f)
        val glue = Glue(DrawableId("band"), DrawableId("skirt"), (0..3).map { GluePair(4 + it, it, 0.5f, 0.5f) })
        val source = model(drawable("band", band), drawable("skirt", skirt), glues = listOf(glue))
        val request = buildJsonObject {
            put("id", "skirt"); put("kind", "cloth"); putJsonArray("targets") { add("skirt") }
            putJsonObject("glue_roles") { put("band|skirt", "pin") }
        }
        val overlay = SimAuthoring.put(io.github.psd2live.core.RigEditOverlay(), source, request)
        assertEquals(GlueRole.PIN, overlay.simEdits.single().glueRoles["band|skirt"])
        assertFailsWith<IllegalArgumentException> {
            SimAuthoring.put(overlay, source, buildJsonObject { put("id", "skirt"); putJsonObject("glue_roles") { put("nope|skirt", "pin") } })
        }
        val report = SimAuthoring.report(source, overlay.simEdits.single(), wind = 3000f to 0f)
        assertEquals(4, report.getValue("pinned").jsonPrimitive.int)
        val wind = report.getValue("phases").jsonArray.single().jsonObject
        assertTrue(wind.getValue("peak_px").jsonPrimitive.float > 1f)
        assertTrue(report.getValue("rest_drift_px").jsonPrimitive.float < 1f)
        assertTrue(SimAuthoring.remove(overlay, "skirt").simEdits.isEmpty())
    }

    @Test fun simulationsAndVertexGroupsSurviveAProjectReopen() {
        val temp = kotlin.io.path.createTempDirectory("sim-store")
        try {
            val put = VertexGroupJournal.encode(VertexGroup("pin", DrawableId("skirt"), VertexGroupKind.PIN, floatArrayOf(1f, 0.25f, 0f)))
            val edits = io.github.psd2live.core.RigEditOverlay(
                authoringJournal = listOf(put),
                simEdits = listOf(RigSimEdit("skirt", "Skirt", SimKind.CLOTH, listOf("skirt"), glueRoles = mapOf("band|skirt" to GlueRole.PIN))),
            )
            val document = io.github.psd2live.project.WorkspaceDocument(
                io.github.psd2live.project.WorkspaceSourceArt(30, 20, emptyList(), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(), edits)
            val store = io.github.psd2live.project.WorkspaceStore(temp)
            store.persistHistory("sim", io.github.psd2live.history.WorkspaceHistoryTree(document, "revision", "snapshot").state())
            val restored = assertNotNull(store.loadHistory("sim")).head().snapshot.rigEdits
            assertEquals(edits.simEdits, restored.simEdits)
            assertEquals(listOf(put), restored.authoringJournal)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
