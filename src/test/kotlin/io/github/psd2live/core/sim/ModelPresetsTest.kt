package io.github.psd2live.core.sim

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.withRigEdits
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.VertexGroupKind
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPresetsTest {
    /** An opaque silhouette: each row is filled where [inside] holds. */
    private fun raster(width: Int, height: Int, inside: (x: Int, y: Int) -> Boolean): LayerRaster {
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) if (inside(x, y)) rgba[(y * width + x) * 4 + 3] = 255.toByte()
        return LayerRaster(width, height, rgba)
    }

    /** A flared skirt: a 12-row waistband 50 px wide, then widening to 110 px at the hem. */
    private val skirt = raster(120, 120) { x, y ->
        val half = if (y < 12) 25f else 25f + (y - 12) * 30f / 108f
        abs(x + 0.5f - 60f) < half
    }

    /** Trousers: hips 70 px wide down to row 50, then two 26 px legs with a gap of 18 px between them. */
    private fun trousers(legRows: Int) = raster(80, 50 + legRows) { x, y ->
        if (y < 50) x in 5 until 75 else x in 5 until 31 || x in 49 until 75
    }

    @Test fun flaredSkirtIsASkirtWithItsWaistAtTheBandEdge() {
        val profile = ModelPresets.garmentProfile(skirt, 200f, "Layer 7")
        assertEquals(ModelPresets.Garment.SKIRT, profile.garment)
        assertEquals("silhouette", profile.decidedBy)
        assertNull(profile.crotch)
        assertTrue(abs(profile.waist - (200f + 11f)) <= 120 * 0.03f, "waist ${profile.waist}")
        assertEquals(320f, profile.hem)
    }

    @Test fun legsSplitFromTheCrotchToTheHemAreTrousers() {
        for (legRows in listOf(150, 40)) {
            val profile = ModelPresets.garmentProfile(trousers(legRows), 0f, "bottom")
            assertEquals(ModelPresets.Garment.TROUSERS, profile.garment, "legs of $legRows rows")
            val crotch = assertNotNull(profile.crotch)
            assertTrue(abs(crotch - 50f) <= (50 + legRows) * 0.03f, "crotch $crotch for legs of $legRows rows")
        }
    }

    @Test fun aGarmentNameWinsOverTheSilhouette() {
        assertEquals(ModelPresets.Garment.SKIRT, ModelPresets.garmentProfile(trousers(100), 0f, "スカート").garment)
        val named = ModelPresets.garmentProfile(skirt, 0f, "裤子")
        assertEquals(ModelPresets.Garment.TROUSERS, named.garment)
        assertEquals("name", named.decidedBy)
        // Legs drawn together: the crotch is assumed.
        assertTrue(named.crotch!! > named.waist && named.crotch!! < named.hem)
    }

    /** A grid mesh over [x0]..[x1] × [y0]..[y1], canvas px, [cols] × [rows] cells. */
    private fun grid(x0: Float, y0: Float, x1: Float, y1: Float, cols: Int, rows: Int, base: Int = 0): Pair<FloatArray, IntArray> {
        val points = FloatArray((cols + 1) * (rows + 1) * 2)
        for (r in 0..rows) for (c in 0..cols) {
            val i = r * (cols + 1) + c
            points[i * 2] = x0 + (x1 - x0) * c / cols
            points[i * 2 + 1] = y0 + (y1 - y0) * r / rows
        }
        val indices = ArrayList<Int>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = base + r * (cols + 1) + c
            val b = a + 1
            val d = a + cols + 1
            val e = d + 1
            indices += listOf(a, b, e, a, e, d)
        }
        return points to indices.toIntArray()
    }

    private fun mesh(vararg parts: Pair<FloatArray, IntArray>): Pair<DrawableMesh, FloatArray> {
        val points = parts.map { it.first }.reduce { a, b -> a + b }
        val indices = parts.map { it.second }.reduce { a, b -> a + b }
        return DrawableMesh(points.copyOf(), FloatArray(points.size), indices) to points
    }

    @Test fun skirtWeightsHoldTheWaistAndFreeTheHem() {
        val profile = ModelPresets.garmentProfile(skirt, 0f, "skirt")
        val field = ClothFit.analyze(skirt, 0f, 0f, ClothFit.Wear.SKIRT, profile.waist)
        val (mesh, canvas) = mesh(grid(5f, 0f, 115f, 120f, 6, 12))
        val weights = ModelPresets.clothWeights(mesh, canvas, field)
        val n = mesh.vertexCount
        val topRow = (0 until n).filter { canvas[it * 2 + 1] == 0f }
        val hemRow = (0 until n).filter { canvas[it * 2 + 1] == 120f }
        assertTrue(topRow.all { weights.pin[it] == 1f && weights.wind!![it] == 0f && weights.mass!![it] == 0.6f }, "waist pinned")
        assertTrue(hemRow.all { weights.pin[it] < 0.1f && weights.wind!![it] > 0.9f && weights.mass!![it] > 0.95f }, "hem free")
        assertTrue(ModelPresets.hangsLoose(weights))
    }

    @Test fun eachStrandIsRootedAtItsOwnTop() {
        val (mesh, canvas) = mesh(grid(0f, 0f, 10f, 100f, 1, 10), grid(30f, 40f, 40f, 90f, 1, 5, base = 22))
        val weights = ModelPresets.strandWeights(mesh, canvas, 0.06f, 0.12f)
        for ((top, bottom) in listOf(0f to 100f, 40f to 90f)) {
            val strand = (0 until mesh.vertexCount).filter { canvas[it * 2 + 1] in top..bottom && (canvas[it * 2] < 20f) == (top == 0f) }
            assertTrue(strand.filter { canvas[it * 2 + 1] == top }.all { weights.pin[it] == 1f }, "root of strand at $top")
            assertTrue(strand.filter { canvas[it * 2 + 1] == bottom }.all { weights.pin[it] == 0f }, "tip of strand at $top")
        }
        assertEquals(setOf(VertexGroupKind.PIN), weights.groups().keys, "hair writes only its pins")
    }

    private val psd = Path.of("examples/tml/psd-input/tml.psd")

    @Test fun simulatedFrontHairDropsTheLegacySwayWithoutMovingTheArt() {
        val pipeline = PSD2LivePipeline()
        val classic = pipeline.buildPreview(psd)
        val simulated = pipeline.buildPreview(classic.analysis, classic.config.copy(hairSimulationFront = true))
        val puppet = simulated.rig.puppet
        assertTrue(classic.rig.puppet.parameters.any { it.id == StandardParameters.HAIR_FRONT })
        assertTrue(puppet.parameters.none { it.id == StandardParameters.HAIR_FRONT })
        assertTrue(puppet.parameters.any { it.id == StandardParameters.HAIR_BACK })
        assertTrue(puppet.deformers.none { it.id.raw == "DeformHairFrontPhysics" })
        val layers = simulated.analysis.layers.associateBy { it.source.id.raw }
        val front = puppet.drawables.filter { layers[simulated.rig.layerIdByDrawableId[it.id.raw]]?.semantic?.tag == SemanticTag.FRONT_HAIR }
        assertTrue(front.isNotEmpty() && front.all { it.parentDeformerId?.raw == "DeformHairFrontFollow" })
        val before = ModelPresets.restCanvas(classic.rig.puppet)
        val after = ModelPresets.restCanvas(puppet)
        for (drawable in front) {
            val a = before.getValue(drawable.id.raw)
            val b = after.getValue(drawable.id.raw)
            val drift = a.indices.maxOf { abs(a[it] - b[it]) }
            assertTrue(drift < 0.5f, "${drawable.name} stays where it is drawn, moved $drift px")
        }
        val available = puppet.parameters.mapTo(HashSet()) { it.id.raw }
        val groups = PhysicsCatalog.groups(simulated.analysis, simulated.config, available).map { it.id }
        assertTrue(PhysicsGenerator.FRONT_HAIR_ID !in groups && PhysicsGenerator.BACK_HAIR_ID in groups, groups.toString())
    }

    @Test fun hairPresetIsOneSimulationThatReapplyingUpdatesAndPersists() {
        val pipeline = PSD2LivePipeline()
        val initial = pipeline.buildPreview(psd)
        val config = initial.config.copy(hairSimulationBack = true)
        val base = pipeline.buildPreview(initial.analysis, config)
        val applied = ModelPresets.apply(config.rigEdits, base.rig.puppet, base.analysis, base.rig.layerIdByDrawableId, ModelPresets.Preset.BACK_HAIR)
        assertEquals(listOf(ModelPresets.BACK_HAIR_SIM), applied.simulationIds)
        val sim = applied.overlay.simEdits.single()
        assertEquals(SimKind.HAIR, sim.kind)
        assertEquals("preset_pin", sim.groups[VertexGroupKind.PIN])
        val rebuilt = base.baseRig.withRigEdits(applied.overlay).puppet
        for (target in sim.targets) {
            val pin = rebuilt.vertexGroups.single { it.drawableId.raw == target && it.kind == VertexGroupKind.PIN }
            assertTrue(pin.weights.max() == 1f && pin.weights.min() == 0f, "$target has a root and a free tip")
        }
        // Applying again refreshes the same simulation and journals nothing new.
        val again = ModelPresets.apply(applied.overlay, rebuilt, base.analysis, base.rig.layerIdByDrawableId, ModelPresets.Preset.BACK_HAIR)
        assertEquals(applied.overlay.simEdits, again.overlay.simEdits)
        assertEquals(applied.overlay.authoringJournal, again.overlay.authoringJournal)

        val temp = Files.createTempDirectory("model-presets")
        try {
            val document = io.github.psd2live.agent.AgentWorkspaceDocument(
                io.github.psd2live.agent.WorkspaceSourceArt(30, 20, emptyList(), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(),
                applied.overlay, kotlinx.serialization.json.buildJsonObject { put("hairSimulationBack", kotlinx.serialization.json.JsonPrimitive(true)) })
            val store = io.github.psd2live.agent.AgentWorkspaceStore(temp)
            store.persistHistory("presets", io.github.psd2live.history.WorkspaceHistoryTree(document, "revision", "snapshot").state())
            val restored = assertNotNull(store.loadHistory("presets")).head().snapshot
            assertEquals(applied.overlay.simEdits, restored.rigEdits.simEdits)
            assertEquals(applied.overlay.authoringJournal, restored.rigEdits.authoringJournal)
            assertTrue(io.github.psd2live.project.WorkspaceStateCodec.decode(restored.settings).hairSimulationBack)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test fun clothingSimulatesOnlyWhatHangsLooseAndLetsATightGarmentGo() {
        val pipeline = PSD2LivePipeline()
        val base = pipeline.buildPreview(psd)
        val layers = base.analysis.layers.associateBy { it.source.id.raw }
        fun tagOf(mesh: String) = layers[base.rig.layerIdByDrawableId[mesh]]?.semantic?.tag
        val skirt = base.rig.puppet.drawables.first { it.mesh != null && tagOf(it.id.raw) == SemanticTag.BOTTOMWEAR }.id.raw
        val top = base.rig.puppet.drawables.first { it.mesh != null && tagOf(it.id.raw) == SemanticTag.TOPWEAR }.id.raw
        // An earlier preset had put the fitted top into a simulation of its own.
        val earlier = SimAuthoring.put(base.config.rigEdits, base.rig.puppet, RigSimEdit(ModelPresets.TOP_SIM, "Top", SimKind.CLOTH, listOf(top)))
        val applied = ModelPresets.apply(earlier, base.rig.puppet, base.analysis, base.rig.layerIdByDrawableId, ModelPresets.Preset.CLOTHING)
        // tml: the A-line skirt swings; the fitted top, the sleeves and the bare legs are worn tight.
        assertEquals(listOf(ModelPresets.SKIRT_SIM), applied.simulationIds)
        assertEquals(listOf(skirt), applied.overlay.simEdits.single().targets)
        val garments = applied.garments
        assertEquals("skirt", garments.getValue(skirt)["garment"]?.jsonPrimitive?.content)
        assertEquals(true, garments.getValue(skirt)["simulated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("top", garments.getValue(top)["garment"]?.jsonPrimitive?.content)
        assertEquals(false, garments.getValue(top)["simulated"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(garments.values.count { it["simulated"]?.jsonPrimitive?.booleanOrNull == true } == 1, garments.toString())
        // Applying again changes nothing.
        val rebuilt = base.baseRig.withRigEdits(applied.overlay).puppet
        val again = ModelPresets.apply(applied.overlay, rebuilt, base.analysis, base.rig.layerIdByDrawableId, ModelPresets.Preset.CLOTHING)
        assertEquals(applied.overlay.simEdits, again.overlay.simEdits)
        assertEquals(applied.overlay.authoringJournal, again.overlay.authoringJournal)
    }

    @Test fun aMeshInAUserSimulationIsRefused() {
        val pipeline = PSD2LivePipeline()
        val base = pipeline.buildPreview(psd)
        val layers = base.analysis.layers.associateBy { it.source.id.raw }
        val back = base.rig.puppet.drawables.first { it.mesh != null && layers[base.rig.layerIdByDrawableId[it.id.raw]]?.semantic?.tag == SemanticTag.BACK_HAIR }
        val overlay = SimAuthoring.put(base.config.rigEdits, base.rig.puppet, RigSimEdit("mine", "Mine", SimKind.HAIR, listOf(back.id.raw)))
        val failure = runCatching {
            ModelPresets.apply(overlay, base.rig.puppet, base.analysis, base.rig.layerIdByDrawableId, ModelPresets.Preset.BACK_HAIR)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && "Mine" in failure.message.orEmpty(), failure.toString())
    }
}
