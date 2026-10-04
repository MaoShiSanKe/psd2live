package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopology
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.*

/** A captured canvas gesture. No viewport, Compose state, transport or committed model is mutated. */
internal object CanvasDeformStroke {
    enum class Action { BRUSH, SMOOTH, INFLATE }
    enum class Mode { EDIT, DEFORM }
    data class Target(val kind: String, val id: String, val key: Map<String, Float> = emptyMap(), val vertices: Set<Int>? = null) {
        val handle get() = "$kind:$id"
    }
    data class Sample(val point: CanvasBrushPoint, val smooth: Boolean = false, val preserveChildren: Boolean = false)
    data class Request(val action: Action, val mode: Mode, val targets: List<Target>, val pose: Map<String, Float>,
                       val tip: CanvasBrushTip, val strength: Float, val connected: Boolean, val shrink: Boolean = false)
    private data class Surface(val target: Target, val geometry: RigGeometryTools.Geometry,
                               val mapping: DrawableSpaceMapping, val indices: IntArray, val neighbors: List<IntArray>)

    fun begin(model: PuppetModel, request: Request, press: Sample): Session = Session(model,
        request.copy(targets = request.targets.map { it.copy(key = it.key.toMap(), vertices = it.vertices?.toSet()) }, pose = request.pose.toMap()), press.canonical())

    /**
     * Samples are kept on a 1/1024 px grid. A pointer stroke reaches the canvas through float screen coordinates,
     * which lose an ulp or two on the way back, and image-keeping edits derive UVs from the moved positions; on the
     * grid the panel's stroke and the same stroke sent as a command replay to identical geometry.
     */
    private fun Sample.canonical(): Sample {
        fun grid(value: Float) = if (value.isFinite()) kotlin.math.round(value * 1024f) / 1024f else value
        return copy(point = CanvasBrushPoint(grid(point.x), grid(point.y)))
    }

    class Session internal constructor(private val source: PuppetModel, val request: Request, press: Sample) {
        private val samples = arrayListOf(press)
        private val brushed = request.targets.mapTo(HashSet()) { it.handle }
        private val surfaces: List<Surface>
        private val initial: List<CanvasBrushSurface>
        private val initialWeights: List<FloatArray>
        private var previous = press.point
        private var pending = emptyList<JsonObject>()
        private var cancelled = false
        var preview: PuppetModel = source
            private set
        val commands: JsonArray get() = JsonArray(pending)
        val weights: Map<String, FloatArray> get() = surfaces.indices.filter { surfaces[it].target.handle in brushed }
            .associate { surfaces[it].target.handle to initialWeights[it].copyOf() }

        init {
            canvasWeightCheckpoint()
            require(request.targets.size in 1..64 && request.targets.map { it.handle }.distinct().size == request.targets.size)
            require(request.targets.all { it.kind == "mesh" } || (request.targets.size == 1 && request.targets.single().kind == "warp")) {
                "Choose one Warp or an ordered set of meshes"
            }
            require(request.strength.isFinite() && request.strength in 0f..1f)
            require(press.point.x.isFinite() && press.point.y.isFinite())
            require(request.tip.radius.isFinite() && request.tip.radius in 0.5f..4096f)
            require(request.tip.hardness.isFinite() && request.tip.hardness in 0f..0.95f)
            require(request.tip.angleDeg.isFinite() && request.tip.aspect.isFinite() && request.tip.aspect in 0.1f..10f)
            val parameters = source.parameters.associateBy { it.id.raw }
            fun validatePose(values: Map<String, Float>) = require(values.all { (id, value) ->
                parameters[id]?.let { value.isFinite() && value in it.min..it.max } == true
            }) { "Unknown or out-of-range parameter coordinate" }
            validatePose(request.pose)
            request.targets.forEach { validatePose(it.key) }
            val blends = request.targets.flatMap { it.key.keys }.filter { parameters[it]?.kind == ParameterKind.BLEND_SHAPE }.distinct()
            require(blends.size <= 1) { "A stroke can write one blend shape at a time" }
            val targets = request.targets.toMutableList()
            if (request.targets.first().kind == "mesh") {
                val ids = request.targets.mapTo(HashSet()) { it.id }
                val counts = source.drawables.associate { it.id.raw to (it.mesh?.vertexCount ?: 0) }
                for (group in WeldGroups.of(source.glues) { it.index in 0 until (counts[it.mesh] ?: 0) }.groups) {
                    if (group.none { it.mesh in ids }) continue
                    for (member in group) if (targets.none { it.kind == "mesh" && it.id == member.mesh }) {
                        val geometry = RigGeometryTools.geometry(source, "mesh", member.mesh, request.pose)
                        val key = geometry.axes.associate { it.parameterId.raw to (request.pose[it.parameterId.raw] ?: parameters.getValue(it.parameterId.raw).default) } +
                            blends.associateWith { request.pose[it] ?: parameters.getValue(it).default }
                        targets += Target("mesh", member.mesh, key)
                    }
                }
            }
            val defaults = source.parameters.associate { it.id to it.default }
            require(targets.size <= 128) { "Stroke and its Glue partners must fit in 128 geometry edits" }
            val worlds = buildDeformerWorlds(source.deformers, { request.pose[it.raw] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
            surfaces = targets.map { target ->
                canvasWeightCheckpoint()
                val geometry = RigGeometryTools.geometry(source, target.kind, target.id, request.pose)
                if (request.mode == Mode.DEFORM) {
                    require(geometry.axes.all { it.parameterId.raw in target.key }) { "Include every bound geometry axis for ${target.handle}" }
                    require(target.key.all { (id, value) -> value == (request.pose[id] ?: parameters.getValue(id).default) }) {
                        "Destination key must match the captured viewing pose"
                    }
                }
                val count = geometry.points.size / 2
                require(target.vertices == null || target.vertices.all { it in 0 until count }) { "Invalid selected vertex for ${target.handle}" }
                val indices = if (target.kind == "mesh") requireNotNull(source.drawables.single { it.id.raw == target.id }.mesh).indices else intArrayOf()
                val neighbors = if (target.kind == "mesh") MeshTopology.buildVertexAdjacency(count, indices) else {
                    val columns = requireNotNull(geometry.columns); val rows = requireNotNull(geometry.rows)
                    List(count) { i ->
                        val x = i % (columns + 1); val y = i / (columns + 1)
                        listOfNotNull(if (x > 0) i - 1 else null, if (x < columns) i + 1 else null,
                            if (y > 0) i - columns - 1 else null, if (y < rows) i + columns + 1 else null).toIntArray()
                    }
                }
                val mapping = DrawableSpaceMapping(geometry.parent?.let { requireNotNull(worlds[DeformerId(it)]) { "Missing parent transform" } })
                Surface(target, geometry, mapping, indices, neighbors)
            }
            initial = surfaces.map { surface(it, it.geometry.points) }
            val computed = reach(initial, press.point, press.point)
            initialWeights = surfaces.indices.map { if (surfaces[it].target.handle in brushed) computed[it] else FloatArray(initial[it].points.size) }
        }

        private fun surface(target: Surface, local: FloatArray): CanvasBrushSurface {
            val world = target.mapping.localToWorld(local)
            return CanvasBrushSurface((world.indices step 2).map { CanvasBrushPoint(world[it], -world[it + 1]) },
                if (request.connected) target.neighbors else null, target.indices.takeIf { target.target.kind == "mesh" }, target.target.id.hashCode())
        }

        private fun reach(points: List<CanvasBrushSurface>, from: CanvasBrushPoint, to: CanvasBrushPoint): List<FloatArray> {
            val at = surfaces.indices.filter { surfaces[it].target.handle in brushed }
            val weights = canvasBrushWeights(at.map { points[it] }, from, to, request.tip, request.connected)
            val result = points.map { FloatArray(it.points.size) }
            at.forEachIndexed { k, index ->
                val allowed = surfaces[index].target.vertices
                for (i in weights[k].indices) {
                    if (i % 512 == 0) canvasWeightCheckpoint()
                    result[index][i] = if (allowed == null || i in allowed) weights[k][i] * request.strength else 0f
                }
            }
            return result
        }

        fun step(input: Sample): PuppetModel {
            val sample = input.canonical()
            check(!cancelled) { "Stroke was cancelled" }
            require(samples.size < 4096) { "Use at most 4096 samples" }
            require(sample.point.x.isFinite() && sample.point.y.isFinite())
            canvasWeightCheckpoint()
            val deform = request.action == Action.BRUSH && !sample.smooth
            val smooth = request.action == Action.SMOOTH || (request.action == Action.BRUSH && sample.smooth)
            val bases = surfaces.map { if (deform) it.geometry.points else RigGeometryTools.geometry(preview, it.target.kind, it.target.id, request.pose).points }
            val points = if (deform) initial else surfaces.indices.map { surface(surfaces[it], bases[it]) }
            val weights = if (deform) initialWeights else reach(points, previous, sample.point)
            val worlds = surfaces.indices.map { surfaces[it].mapping.localToWorld(bases[it]) }
            val moved = surfaces.map { HashSet<Int>() }
            val from = if (deform) samples.first().point else previous
            val delta = sample.point - from
            for (at in surfaces.indices) {
                canvasWeightCheckpoint()
                if (surfaces[at].target.handle !in brushed) continue
                for (i in points[at].points.indices) {
                    if (i % 512 == 0) canvasWeightCheckpoint()
                    val weight = weights[at][i]
                    if (weight <= if (deform) 0.0001f else 0f) continue
                    val point = points[at].points[i]
                    val destination = when {
                        request.action == Action.INFLATE -> point + inflateOffset(point, previous, sample.point,
                            delta.getDistance().coerceAtMost(request.tip.radius) * weight * 0.5f * if (request.shrink) -1f else 1f)
                        smooth -> {
                            val adjacent = surfaces[at].neighbors[i]
                            if (adjacent.isEmpty()) point else point + (CanvasBrushPoint(
                                adjacent.map { points[at].points[it].x }.average().toFloat(),
                                adjacent.map { points[at].points[it].y }.average().toFloat()) - point) * weight
                        }
                        else -> point + delta * weight
                    }
                    worlds[at][i * 2] = destination.x; worlds[at][i * 2 + 1] = -destination.y
                    moved[at] += i
                }
            }
            keepWeldsTogether(surfaces.map { it.target.id }, bases.map { it.size / 2 }, worlds, moved, source.glues)
            val edits = surfaces.indices.filter { moved[it].isNotEmpty() }.map { at ->
                val target = surfaces[at]
                val local = target.mapping.worldToLocal(worlds[at], bases[at], moved[at])
                require(local.all(Float::isFinite)) { "Non-finite deformed geometry" }
                command(request.mode, target.target, request.pose, local, sample.preserveChildren)
            }
            val merged = if (deform) edits else {
                val previousCommands = pending.associateByTo(LinkedHashMap()) { it.getValue("id").jsonPrimitive.content }
                edits.forEach { previousCommands[it.getValue("id").jsonPrimitive.content] = it }
                previousCommands.values.toList()
            }
            // The commit compiles these same commands, so the preview goes through compile too: a plain fold
            // would also apply the edits compile drops as no-ops and drift from the committed rig.
            val next = if (merged.isEmpty()) source else RigAuthoringJournal.compile(source, JsonArray(merged)).first
            samples += sample; previous = sample.point; pending = merged; preview = next
            return next
        }

        fun cancel() { cancelled = true; pending = emptyList(); preview = source }
        fun capturedSamples(): List<Sample> = samples.toList()
    }

    fun command(mode: Mode, target: Target, pose: Map<String, Float>, points: FloatArray, preserveChildren: Boolean) = buildJsonObject {
        val editMesh = target.kind == "mesh" && mode == Mode.EDIT
        val editWarp = target.kind == "warp" && (mode == Mode.EDIT || preserveChildren)
        put("op", "canvas_geometry"); put("kind", target.kind); put("id", target.id)
        val key = if (mode == Mode.EDIT) emptyMap() else target.key
        put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
        // Viewing pose is independent of the destination's directly bound axes, also for deformation.
        put("pose", JsonObject(pose.mapValues { JsonPrimitive(it.value) }))
        put("preserve_image", editMesh || editWarp)
        if (editWarp) put("preserve_children", true)
        put("points", JsonArray(points.map(::JsonPrimitive)))
    }

    fun keepWeldsTogether(ids: List<String>, counts: List<Int>, worlds: List<FloatArray>, moved: List<MutableSet<Int>>, glues: List<Glue>) {
        val slot = ids.withIndex().associate { it.value to it.index }
        for (group in WeldGroups.of(glues) { member -> slot[member.mesh]?.let { member.index in 0 until counts[it] } == true }.groups) {
            canvasWeightCheckpoint()
            val movers = group.filter { it.index in moved[slot.getValue(it.mesh)] }
            if (movers.isEmpty()) continue
            if (group.size < 2) continue
            var x = 0f; var y = 0f
            for (member in movers) {
                x += worlds[slot.getValue(member.mesh)][member.index * 2]
                y += worlds[slot.getValue(member.mesh)][member.index * 2 + 1]
            }
            x /= movers.size; y /= movers.size
            for (member in group) {
                val at = slot.getValue(member.mesh)
                worlds[at][member.index * 2] = x; worlds[at][member.index * 2 + 1] = y; moved[at] += member.index
            }
        }
    }

    fun inflateOffset(point: CanvasBrushPoint, from: CanvasBrushPoint, to: CanvasBrushPoint, amount: Float): CanvasBrushPoint {
        val segment = to - from
        val length2 = segment.x * segment.x + segment.y * segment.y
        val direction = if (length2 < 1e-8f) point - from else {
            val relative = point - from
            val t = ((relative.x * segment.x + relative.y * segment.y) / length2).coerceIn(0f, 1f)
            point - (from + segment * t)
        }
        val length = direction.getDistance()
        return if (length < 1e-3f) CanvasBrushPoint(0f, 0f) else direction * (amount / length)
    }
}
