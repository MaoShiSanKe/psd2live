package io.github.psd2live.core.sim

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.VertexGroupJournal
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind

/**
 * Model presets that materialize as ordinary edits, shared by the GUI and MCP: weights are journaled vertex
 * groups and simulations are overlay entries, so everything a preset makes stays editable in the simulation
 * panel. Applying a preset again updates what it made before (stable IDs and group names) instead of stacking.
 */
object ModelPresets {
    enum class Preset(val jsonName: String) {
        FRONT_HAIR("front_hair"),
        BACK_HAIR("back_hair"),
        /** Skirts and trousers among the bottomwear, each with its own weights and cloth simulation. */
        CLOTHING("clothing"),
        /** Recomputes the weights only, for the selection or every simulated mesh; no new simulation. */
        AUTO_WEIGHTS("auto_weights");

        companion object {
            fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown model preset: $text (${entries.joinToString { it.jsonName }})")
        }
    }

    enum class Garment(val jsonName: String) { SKIRT("skirt"), TROUSERS("trousers") }

    const val FRONT_HAIR_SIM = "preset_front_hair"
    const val BACK_HAIR_SIM = "preset_back_hair"
    const val SKIRT_SIM = "preset_skirt"
    const val TROUSERS_SIM = "preset_trousers"
    val PRESET_SIMS = setOf(FRONT_HAIR_SIM, BACK_HAIR_SIM, SKIRT_SIM, TROUSERS_SIM)

    /** The group each preset writes for a kind, unless the simulation already names one of its own. */
    val GROUP_NAMES = mapOf(
        VertexGroupKind.PIN to "preset_pin",
        VertexGroupKind.MASS to "preset_mass",
        VertexGroupKind.WIND to "preset_wind",
    )

    private val SKIRT_NAMES = listOf("skirt", "dress", "裙", "スカート", "ワンピース")
    private val TROUSER_NAMES = listOf("pants", "trouser", "shorts", "jeans", "裤", "褲", "ズボン", "パンツ")

    /**
     * What the silhouette of a bottomwear layer says, in canvas px (y down). [crotch] and the seam exist
     * only for trousers: below the crotch, a vertex left of [seamX] belongs to the left leg.
     */
    class GarmentProfile internal constructor(
        val garment: Garment,
        val top: Float,
        val waist: Float,
        val crotch: Float?,
        val hem: Float,
        /** How the garment was told apart: "name" or "silhouette". */
        val decidedBy: String,
        private val seamTop: Float,
        private val seams: FloatArray,
    ) {
        fun seamX(y: Float): Float =
            if (seams.isEmpty()) Float.NaN else seams[(y - seamTop).toInt().coerceIn(0, seams.lastIndex)]

        fun toJson() = buildJsonObject {
            put("garment", garment.jsonName); put("decided_by", decidedBy)
            put("waist", round(waist)); crotch?.let { put("crotch", round(it)) }; put("hem", round(hem))
        }
    }

    /**
     * Reads a garment from [raster], placed at ([left], [top]) on the canvas. Each row's opaque runs give
     * its extent and the widest interior gap with enough cloth on both sides. The waist is the narrowest
     * row of the upper third; trousers are a gap that starts at the crotch and holds down to the hem. A
     * garment [name] decides the type first, since a slit skirt or legs drawn together fool the silhouette.
     */
    fun garmentProfile(raster: LayerRaster, left: Float, top: Float, name: String, alphaThreshold: Int = 8): GarmentProfile {
        val w = raster.width
        val h = raster.height
        val minRun = maxOf(2, w / 100)
        val rowLeft = IntArray(h) { -1 }
        val rowRight = IntArray(h) { -1 }
        val gapCenter = FloatArray(h) { Float.NaN }
        val starts = IntArray(w + 1)
        val ends = IntArray(w + 1)
        for (y in 0 until h) {
            var runs = 0
            var start = -1
            for (x in 0..w) {
                val opaque = x < w && (raster.rgba[(y * w + x) * 4 + 3].toInt() and 255) > alphaThreshold
                if (opaque && start < 0) start = x
                if (!opaque && start >= 0) {
                    if (x - start >= minRun) { starts[runs] = start; ends[runs] = x; runs++ }
                    start = -1
                }
            }
            if (runs == 0) continue
            rowLeft[y] = starts[0]
            rowRight[y] = ends[runs - 1]
            val width = rowRight[y] - rowLeft[y]
            var total = 0
            for (k in 0 until runs) total += ends[k] - starts[k]
            var before = 0
            var bestGap = 0
            for (k in 0 until runs - 1) {
                before += ends[k] - starts[k]
                val gap = starts[k + 1] - ends[k]
                if (gap >= maxOf(3f, width * 0.04f) && before >= width * 0.15f && total - before >= width * 0.15f && gap > bestGap) {
                    bestGap = gap
                    gapCenter[y] = (ends[k] + starts[k + 1]) * 0.5f
                }
            }
        }
        val occupied = (0 until h).filter { rowLeft[it] >= 0 }
        require(occupied.isNotEmpty()) { "$name has no opaque pixels" }
        val first = occupied.first()
        val last = occupied.last()
        val height = (last - first).coerceAtLeast(1)
        fun width(y: Int) = if (rowLeft[y] < 0) 0 else rowRight[y] - rowLeft[y]
        val widest = occupied.maxOf(::width)
        // Averaging a few rows keeps an antialiased apex or a single notch from passing for the waist.
        val window = maxOf(1, height / 25)
        fun smoothed(y: Int) = (maxOf(first, y - window / 2)..minOf(last, y + window / 2)).map(::width).average()
        // A waistband is a run of equally narrow rows; the waist line is where it ends and the cloth starts.
        val upper = (first..first + height * 35 / 100).filter { width(it) >= widest * 0.4f }
        val narrowest = upper.minOfOrNull(::smoothed)
        val waist = if (narrowest == null) first else {
            var y = upper.first { smoothed(it) == narrowest }
            val band = y + maxOf(1, height * 10 / 100)
            while (y + 1 in upper && y + 1 <= band && smoothed(y + 1) <= narrowest * 1.03) y++
            y
        }

        val split = BooleanArray(h) { !gapCenter[it].isNaN() }
        val splitBelow = IntArray(h + 1)
        for (y in h - 1 downTo 0) splitBelow[y] = splitBelow[y + 1] + if (split[y]) 1 else 0
        val minLeg = maxOf(3, height * 12 / 100)
        val crotchRow = (first + 1..last - minLeg).firstOrNull { y ->
            split[y] && splitBelow[y] - splitBelow[last + 1] >= (last - y + 1) * 0.7f
        }?.takeIf { crotch ->
            // The gap between legs stays near the middle; a pleat gap at one edge is not a crotch.
            val rows = (crotch..last).filter { split[it] }
            rows.count { y -> (gapCenter[y] - rowLeft[y]) / width(y).coerceAtLeast(1) in 0.2f..0.8f } >= rows.size * 0.8f
        }
        val lower = name.lowercase()
        val named = when {
            SKIRT_NAMES.any { it in lower } -> Garment.SKIRT
            TROUSER_NAMES.any { it in lower } -> Garment.TROUSERS
            else -> null
        }
        val garment = named ?: if (crotchRow != null) Garment.TROUSERS else Garment.SKIRT
        if (garment == Garment.SKIRT) {
            return GarmentProfile(garment, top + first, top + waist, null, top + last + 1, if (named != null) "name" else "silhouette", 0f, FloatArray(0))
        }
        // Trousers drawn with the legs together have no gap: the crotch is assumed and the seam is the middle.
        val crotch = crotchRow ?: (waist + (last - waist) * 35 / 100)
        val seams = FloatArray(last - crotch + 1)
        var previous = (rowLeft[crotch] + rowRight[crotch]).coerceAtLeast(0) * 0.5f
        for (i in seams.indices) {
            val y = crotch + i
            previous = when {
                split[y] -> gapCenter[y]
                rowLeft[y] >= 0 && crotchRow == null -> (rowLeft[y] + rowRight[y]) * 0.5f
                else -> previous
            }
            seams[i] = left + previous
        }
        return GarmentProfile(garment, top + first, top + waist, top + crotch, top + last + 1,
            if (named != null) "name" else "silhouette", top + crotch, seams)
    }

    /** Per-vertex weights of one mesh; a null group is not written. */
    class PresetWeights(val pin: FloatArray, val mass: FloatArray? = null, val wind: FloatArray? = null) {
        fun groups(): Map<VertexGroupKind, FloatArray> = buildMap {
            put(VertexGroupKind.PIN, pin)
            mass?.let { put(VertexGroupKind.MASS, it) }; wind?.let { put(VertexGroupKind.WIND, it) }
        }
    }

    /**
     * Garment weights from [profile], with [canvas] the mesh's rest vertices in canvas px.
     * - Skirt: pinned above the waist and released over the next 30% toward the hem; mass and wind grow
     *   to the hem.
     * - Trousers: the hips hold (pin 1 to 0.6 at the crotch); below it each leg, split at the seam, releases
     *   over its own length and swings on its own.
     */
    fun garmentWeights(mesh: DrawableMesh, canvas: FloatArray, profile: GarmentProfile): PresetWeights {
        val n = mesh.vertexCount
        val pin = FloatArray(n)
        val mass = FloatArray(n)
        val wind = FloatArray(n)
        val crotch = profile.crotch
        if (profile.garment == Garment.SKIRT || crotch == null) {
            val span = (profile.hem - profile.waist).coerceAtLeast(1f)
            for (v in 0 until n) {
                val d = (canvas[v * 2 + 1] - profile.waist) / span
                pin[v] = 1f - smoothstep(0.02f, 0.3f, d)
                mass[v] = 0.6f + 0.4f * smoothstep(0f, 1f, d)
                wind[v] = smoothstep(0.1f, 1f, d)
            }
            return PresetWeights(pin, mass, wind)
        }
        val leg = IntArray(n) { v -> if (canvas[v * 2 + 1] < crotch) -1 else if (canvas[v * 2] < profile.seamX(canvas[v * 2 + 1])) 0 else 1 }
        val legHem = FloatArray(2) { side -> (0 until n).filter { leg[it] == side }.maxOfOrNull { canvas[it * 2 + 1] } ?: profile.hem }
        val hips = (crotch - profile.waist).coerceAtLeast(1f)
        for (v in 0 until n) {
            val y = canvas[v * 2 + 1]
            if (leg[v] < 0) {
                pin[v] = 1f - 0.4f * smoothstep(0.1f, 1f, (y - profile.waist) / hips)
                mass[v] = 0.6f
                continue
            }
            val t = (y - crotch) / (legHem[leg[v]] - crotch).coerceAtLeast(1f)
            pin[v] = 0.6f * (1f - smoothstep(0f, 0.7f, t))
            mass[v] = 0.6f + 0.4f * smoothstep(0f, 1f, t)
            wind[v] = 0.6f * smoothstep(0.2f, 1f, t)
        }
        return PresetWeights(pin, mass, wind)
    }

    /**
     * Strand pins for hair and other hanging parts: each connected island of the mesh is a strand rooted
     * at its own top, held for [solid] of its length and released over the next [fade]. Only the pin: on
     * tml back hair, lighter roots and wind toward the tips cut the bake's held-out R² from 0.69 to 0.56.
     */
    fun strandWeights(mesh: DrawableMesh, canvas: FloatArray, solid: Float, fade: Float): PresetWeights {
        val n = mesh.vertexCount
        val parent = IntArray(n) { it }
        fun root(v: Int): Int {
            var r = v
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        for (i in 0 until mesh.indices.size / 3 * 3 step 3) {
            val a = root(mesh.indices[i])
            parent[root(mesh.indices[i + 1])] = a
            parent[root(mesh.indices[i + 2])] = a
        }
        val pin = FloatArray(n)
        for (island in (0 until n).groupBy(::root).values) {
            val top = island.minOf { canvas[it * 2 + 1] }
            val length = (island.maxOf { canvas[it * 2 + 1] } - top).coerceAtLeast(1e-3f)
            for (v in island) pin[v] = 1f - smoothstep(solid, solid + fade, (canvas[v * 2 + 1] - top) / length)
        }
        return PresetWeights(pin)
    }

    class Applied(val overlay: RigEditOverlay, val simulationIds: List<String>, val garments: Map<String, GarmentProfile>) {
        fun toJson() = buildJsonObject {
            putJsonArray("simulations") { simulationIds.forEach { add(it) } }
            if (garments.isNotEmpty()) putJsonObject("garments") { garments.forEach { (mesh, profile) -> put(mesh, profile.toJson()) } }
        }
    }

    /**
     * [preset] applied to [model], the rig [overlay] rebuilds; [layers] narrows it to those layers, and
     * empty means every recognized part. Hair and clothing simulations are created or updated; a mesh that
     * already belongs to a simulation of the user's own is refused rather than taken over.
     */
    fun apply(
        overlay: RigEditOverlay,
        model: PuppetModel,
        analysis: PipelineAnalysis,
        layerIdByDrawableId: Map<String, String>,
        preset: Preset,
        layers: Set<String> = emptySet(),
        alphaThreshold: Int = 8,
    ): Applied {
        val layerById = analysis.layers.associateBy { it.source.id.raw }
        val canvas = restCanvas(model)
        val candidates = model.drawables.mapNotNull { drawable ->
            if (drawable.mesh == null || canvas[drawable.id.raw] == null) return@mapNotNull null
            val layer = layerById[layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: return@mapNotNull null
            if (layer.opaquePixels <= 0 || (layers.isNotEmpty() && layer.source.id.raw !in layers)) null else drawable to layer
        }
        val writer = Writer(overlay, model, canvas)
        val profiles = LinkedHashMap<String, GarmentProfile>()
        fun profileOf(layer: ClassifiedLayer) = profiles.getOrPut(layer.source.id.raw) {
            garmentProfile(layer.source.raster, layer.source.bounds.left.toFloat(), layer.source.bounds.top.toFloat(), layer.source.name, alphaThreshold)
        }
        val garments = LinkedHashMap<String, GarmentProfile>()
        when (preset) {
            Preset.FRONT_HAIR, Preset.BACK_HAIR -> {
                val front = preset == Preset.FRONT_HAIR
                val tag = if (front) SemanticTag.FRONT_HAIR else SemanticTag.BACK_HAIR
                val targets = candidates.filter { it.second.semantic.tag == tag }.map { it.first }
                require(targets.isNotEmpty()) { if (front) "No front hair meshes" else "No back hair meshes" }
                val id = if (front) FRONT_HAIR_SIM else BACK_HAIR_SIM
                for (drawable in targets) writer.weights(drawable, id, hairWeights(drawable, canvas, front))
                writer.simulation(id, tr(if (front) "presets.sim.frontHair" else "presets.sim.backHair"), SimKind.HAIR,
                    targets.map { it.id.raw }, SimMaterial.preset(SimKind.HAIR), keepOthers = layers.isNotEmpty())
            }
            Preset.CLOTHING -> {
                val targets = candidates.filter { it.second.semantic.tag == SemanticTag.BOTTOMWEAR }
                require(targets.isNotEmpty()) { "No bottomwear meshes" }
                for ((garment, members) in targets.groupBy { profileOf(it.second).garment }) {
                    val skirt = garment == Garment.SKIRT
                    val id = if (skirt) SKIRT_SIM else TROUSERS_SIM
                    for ((drawable, layer) in members) {
                        val profile = profileOf(layer)
                        garments[drawable.id.raw] = profile
                        writer.weights(drawable, id, garmentWeights(drawable.mesh!!, canvas.getValue(drawable.id.raw), profile))
                    }
                    val material = if (skirt) SimMaterial.preset(SimKind.CLOTH)
                        else SimMaterial.preset(SimKind.CLOTH).copy(bend = 0.5f, goal = 0.25f, slack = 0.015f)
                    writer.simulation(id, tr(if (skirt) "presets.sim.skirt" else "presets.sim.trousers"), SimKind.CLOTH,
                        members.map { it.first.id.raw }, material, keepOthers = layers.isNotEmpty())
                }
            }
            Preset.AUTO_WEIGHTS -> {
                val simulated = overlay.simEdits.flatMapTo(HashSet()) { it.targets }
                val targets = candidates.filter { layers.isNotEmpty() || it.first.id.raw in simulated }
                require(targets.isNotEmpty()) { "No simulated or selected meshes" }
                for ((drawable, layer) in targets) {
                    val owner = overlay.simEdits.firstOrNull { drawable.id.raw in it.targets }?.id
                    val positions = canvas.getValue(drawable.id.raw)
                    val weights = when (layer.semantic.tag) {
                        SemanticTag.FRONT_HAIR -> hairWeights(drawable, canvas, true)
                        SemanticTag.BACK_HAIR -> hairWeights(drawable, canvas, false)
                        SemanticTag.BOTTOMWEAR -> profileOf(layer).also { garments[drawable.id.raw] = it }
                            .let { garmentWeights(drawable.mesh!!, positions, it) }
                        else -> strandWeights(drawable.mesh!!, positions, 0.06f, 0.12f)
                    }
                    writer.weights(drawable, owner, weights)
                }
                writer.nameWrittenGroups()
            }
        }
        return Applied(writer.overlay, writer.simulationIds.toList(), garments)
    }

    private fun hairWeights(drawable: Drawable, canvas: Map<String, FloatArray>, front: Boolean) =
        // Back hair lies on the head further down before it hangs free.
        if (front) strandWeights(drawable.mesh!!, canvas.getValue(drawable.id.raw), 0.06f, 0.12f)
        else strandWeights(drawable.mesh!!, canvas.getValue(drawable.id.raw), 0.12f, 0.18f)

    /** Rest vertices of every visible mesh in canvas px; glue is left out so it cannot pull the rest shape. */
    internal fun restCanvas(model: PuppetModel): Map<String, FloatArray> =
        CpuDeformationEvaluator().evaluate(model.copy(glues = emptyList()), emptyMap()).worldPositions
            .entries.associate { (id, world) -> id.raw to FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }

    /** Journals groups and puts simulations, keeping the model the next command lands on in step. */
    private class Writer(var overlay: RigEditOverlay, var model: PuppetModel, val canvas: Map<String, FloatArray>) {
        val simulationIds = LinkedHashSet<String>()
        /** Per simulation, the group name used for each kind written to its meshes. */
        private val written = LinkedHashMap<String, MutableMap<VertexGroupKind, String>>()

        fun weights(drawable: Drawable, simulation: String?, weights: PresetWeights) {
            val named = simulation?.let { id -> overlay.simEdits.firstOrNull { it.id == id }?.groups }.orEmpty()
            for ((kind, values) in weights.groups()) {
                val name = named[kind] ?: GROUP_NAMES.getValue(kind)
                val command = VertexGroupJournal.encode(VertexGroup(name, drawable.id, kind, FloatArray(values.size) { values[it].coerceIn(0f, 1f) }))
                if (!VertexGroupJournal.isNoOp(model, command)) {
                    overlay = overlay.copy(authoringJournal = overlay.authoringJournal + command)
                    model = RigAuthoringJournal.apply(model, command)
                }
                if (simulation != null) written.getOrPut(simulation) { LinkedHashMap() }[kind] = name
            }
        }

        /**
         * Creates or updates preset simulation [id] on [targets]. A target in another preset simulation moves
         * here (a garment now read as the other type); one in a simulation of the user's own is refused.
         */
        fun simulation(id: String, name: String, kind: SimKind, targets: List<String>,
            material: SimMaterial, keepOthers: Boolean) {
            for (other in overlay.simEdits.filter { it.id != id }) {
                val shared = other.targets.filter { it in targets }
                if (shared.isEmpty()) continue
                require(other.id in PRESET_SIMS) { "${shared.first()} already belongs to simulation ${other.name}" }
                val left = other.targets - shared.toSet()
                overlay = if (left.isEmpty()) SimAuthoring.remove(overlay, other.id)
                    else overlay.copy(simEdits = overlay.simEdits.map { if (it.id == other.id) it.copy(targets = left) else it })
            }
            val previous = overlay.simEdits.firstOrNull { it.id == id }
            val allTargets = if (keepOthers && previous != null) (previous.targets + targets).distinct() else targets
            val groups = previous?.groups.orEmpty() + written[id].orEmpty()
            val edit = previous?.copy(targets = allTargets, groups = groups, enabled = true)
                ?: RigSimEdit(id, name, kind, allTargets, material, groups = groups)
            overlay = SimAuthoring.put(overlay, model, edit)
            simulationIds += id
        }

        /** Points every simulation whose meshes got weights at the groups just written, and lists it. */
        fun nameWrittenGroups() {
            for ((id, kinds) in written) {
                val edit = overlay.simEdits.firstOrNull { it.id == id } ?: continue
                overlay = SimAuthoring.put(overlay, model, edit.copy(groups = edit.groups + kinds))
                simulationIds += id
            }
        }
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun round(value: Float) = kotlin.math.round(value * 10f) / 10f
}
