package io.github.psd2live.core.sim

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.math.pow

/*
 * A simulation scene built from the rig: one particle per vertex of every target mesh, in target order.
 *
 * The rig drives it through [SimScene.drive]: each frame the model is evaluated at the frame's pose, and
 * - every vertex's own evaluated position is its goal (the rest shape carried by the rig);
 * - a PIN-group vertex is anchored on that same position;
 * - a vertex glued with role PIN is anchored on its partner's evaluated position, the other mesh's
 *   actual deformation;
 * - collider meshes take their evaluated vertices.
 */

/** Where particle anchors come from: the particle's own rig position, or another mesh's vertex. */
private class Anchor(val drawable: DrawableId, val vertex: Int)

class SimScene private constructor(
    val edit: RigSimEdit,
    val solver: XpbdSolver,
    /** Particle offset of each target mesh. */
    val offsets: Map<DrawableId, Int>,
    val vertexCounts: Map<DrawableId, Int>,
    private val anchors: Array<Anchor?>,
    private val colliderMeshes: List<Pair<DrawableId, TriangleRegionCollider>>,
    /** Why a part of the setup was skipped; shown in the panel. */
    val notes: List<String>,
) {
    private val evaluator = CpuDeformationEvaluator()
    /** Pinned particles that define the body's frame, and where their anchors sit at rest. */
    private val frameParticles = (0 until solver.state.count).filter { solver.pinWeight[it] >= 0.5f }.toIntArray()
    private var frameRest = FloatArray(0)

    val state: SimState get() = solver.state

    /**
     * Moves the rig to [pose] and advances [dt] seconds. Returns false when a target mesh is hidden at this
     * pose, in which case nothing moves.
     */
    fun drive(model: PuppetModel, pose: Map<ParameterId, Float>, dt: Float): Boolean {
        val world = evaluator.evaluate(model, pose).worldPositions
        if (!place(world)) return false
        for ((id, collider) in colliderMeshes) world[id]?.let { collider.update(it, dt) }
        solver.step(dt)
        return true
    }

    /** Puts every particle at rest at [pose], with no motion. */
    fun reset(model: PuppetModel, pose: Map<ParameterId, Float>) {
        val world = evaluator.evaluate(model, pose).worldPositions
        val rest = FloatArray(state.count * 2)
        for ((id, offset) in offsets) {
            val positions = world[id] ?: continue
            positions.copyInto(rest, offset * 2, 0, minOf(positions.size, vertexCounts.getValue(id) * 2))
        }
        state.reset(rest)
        place(world)
        frameRest = FloatArray(frameParticles.size * 2) { if (it % 2 == 0) state.anchorX[frameParticles[it / 2]] else state.anchorY[frameParticles[it / 2]] }
        state.frameAngle = 0f
        state.settle()
        for ((id, collider) in colliderMeshes) world[id]?.let { collider.update(it, 1f) }
    }

    /**
     * Makes the drawn shape the equilibrium at [pose]: settles under gravity, then moves each goal's
     * offset by what is left between rest and where the vertex settled, a few times over. Vertices gravity
     * already holds (a taut hanging strand) end with no offset and swing as a pure pendulum; a flared hem
     * or a strand drawn sideways ends pre-stressed on its goal. Needs a goal to hold anything: with goal 0
     * the body is pure physics and settles where gravity takes it.
     *
     * Returns the largest distance from rest left after the last pass, in px.
     */
    fun calibrate(model: PuppetModel, pose: Map<ParameterId, Float> = emptyMap(), passes: Int = 6, frames: Int = 90): Float {
        val s = state
        s.goalOffsetX.fill(0f); s.goalOffsetY.fill(0f)
        val calm = s.damping.copyOf()
        var residual = 0f
        try {
            for (i in 0 until s.count) s.damping[i] = maxOf(calm[i], 8f)
            repeat(passes) {
                reset(model, pose)
                val rest = s.positions()
                repeat(frames) { drive(model, pose, 1f / 60f) }
                residual = 0f
                for (i in 0 until s.count) {
                    if (!solver.goalCompliance[i].isFinite()) continue
                    val dx = rest[i * 2] - s.x[i]
                    val dy = rest[i * 2 + 1] - s.y[i]
                    s.goalOffsetX[i] += dx; s.goalOffsetY[i] += dy
                    residual = maxOf(residual, hypot(dx, dy))
                }
            }
        } finally {
            calm.copyInto(s.damping)
            reset(model, pose)
        }
        return residual
    }

    /** Writes goals and anchors for this frame. */
    private fun place(world: Map<DrawableId, FloatArray>): Boolean {
        val s = state
        for ((id, offset) in offsets) {
            val positions = world[id] ?: return false
            for (v in 0 until vertexCounts.getValue(id)) {
                val i = offset + v
                s.goalX[i] = positions[v * 2]; s.goalY[i] = positions[v * 2 + 1]
                val anchor = anchors[i]
                val source = if (anchor == null) positions else world[anchor.drawable]
                val index = anchor?.vertex ?: v
                if (source != null && index * 2 + 1 < source.size) {
                    s.anchorX[i] = source[index * 2]; s.anchorY[i] = source[index * 2 + 1]
                } else {
                    s.anchorX[i] = s.goalX[i]; s.anchorY[i] = s.goalY[i]
                }
            }
        }
        s.frameAngle = frameAngle()
        return true
    }

    /** The best-fit rotation of the pinned anchors from rest to now (2D Procrustes about the centroids). */
    private fun frameAngle(): Float {
        val s = state
        if (frameParticles.size < 2 || frameRest.size != frameParticles.size * 2) return 0f
        var rx = 0f; var ry = 0f; var cx = 0f; var cy = 0f
        for ((k, i) in frameParticles.withIndex()) { rx += frameRest[k * 2]; ry += frameRest[k * 2 + 1]; cx += s.anchorX[i]; cy += s.anchorY[i] }
        val count = frameParticles.size.toFloat()
        rx /= count; ry /= count; cx /= count; cy /= count
        var dot = 0f; var cross = 0f
        for ((k, i) in frameParticles.withIndex()) {
            val ax = frameRest[k * 2] - rx; val ay = frameRest[k * 2 + 1] - ry
            val bx = s.anchorX[i] - cx; val by = s.anchorY[i] - cy
            dot += ax * bx + ay * by; cross += ax * by - ay * bx
        }
        return if (dot == 0f && cross == 0f) 0f else kotlin.math.atan2(cross, dot)
    }

    /** The simulated vertices of [id], world space. */
    fun positions(id: DrawableId): FloatArray? {
        val offset = offsets[id] ?: return null
        val count = vertexCounts.getValue(id)
        return FloatArray(count * 2) { if (it % 2 == 0) state.x[offset + it / 2] else state.y[offset + it / 2] }
    }

    companion object {
        /** Pin weights at or above this hold the particle outright, and root the long-range limits. */
        private const val ROOT_PIN = 0.5f

        /** Stretch compliance for a 0..1 stiffness: 1 is effectively inextensible, 0 rubbery. */
        internal fun compliance(stiffness: Float): Float = 1e-9f * 10f.pow(6f * (1f - stiffness.coerceIn(0f, 1f)))

        /**
         * Bend compliance for a 0..1 stiffness, by the frequency the bend springs back at per unit mass:
         * 0.5 rad/s at 0 (limp), about 10 at 0.5, 200 at 1 (a stiff card). Stretch is kept near rigid
         * separately, so bending is what gravity and inertia actually work against.
         */
        internal fun bendCompliance(stiffness: Float): Float {
            val omega = 0.5f * 400f.pow(stiffness.coerceIn(0f, 1f))
            return 1f / (omega * omega)
        }

        /**
         * Goal compliance for a 0..1 strength; 0 is no goal at all. Per unit mass the spring's own frequency
         * is 1/sqrt(compliance): about 0.6 Hz at 0.1, 2 Hz at 0.5 and 10 Hz at 1, so a weak goal keeps the
         * drawn shape without damping the swing.
         */
        internal fun goalCompliance(strength: Float): Float {
            if (strength <= 0f) return Float.POSITIVE_INFINITY
            val omega = 3f * 20f.pow(strength.coerceIn(0f, 1f))
            return 1f / (omega * omega)
        }

        fun build(model: PuppetModel, edit: RigSimEdit, settings: SimSettings = SimSettings()): SimScene {
            val notes = ArrayList<String>()
            val targets = edit.targets.map { raw ->
                val drawable = requireNotNull(model.drawables.firstOrNull { it.id.raw == raw }) { "Simulation target not found: $raw" }
                drawable.id to requireNotNull(drawable.mesh) { "Simulation target has no mesh: $raw" }
            }
            val offsets = LinkedHashMap<DrawableId, Int>()
            val counts = LinkedHashMap<DrawableId, Int>()
            var total = 0
            for ((id, mesh) in targets) { offsets[id] = total; counts[id] = mesh.vertexCount; total += mesh.vertexCount }
            val state = SimState(total)

            fun group(id: DrawableId, kind: VertexGroupKind): VertexGroup? {
                val named = edit.groups[kind]
                val candidates = model.vertexGroups.filter { it.drawableId == id && it.kind == kind }
                return (if (named != null) candidates.firstOrNull { it.name == named } else candidates.firstOrNull())
                    ?.takeIf { it.weights.size == counts.getValue(id) }
            }
            fun perVertex(kind: VertexGroupKind, fallback: Float): FloatArray {
                val out = FloatArray(total) { fallback }
                for ((id, offset) in offsets) group(id, kind)?.weights?.forEachIndexed { v, w -> out[offset + v] = w }
                return out
            }

            val m = edit.material
            val massWeight = perVertex(VertexGroupKind.MASS, 1f)
            val dampingWeight = perVertex(VertexGroupKind.DAMPING, 1f)
            val windWeight = perVertex(VertexGroupKind.WIND, 1f)
            val stiffnessWeight = perVertex(VertexGroupKind.STIFFNESS, 1f)
            val goalWeight = perVertex(VertexGroupKind.GOAL, 1f)
            val pin = perVertex(VertexGroupKind.PIN, 0f)
            val collideGroup = offsets.keys.any { group(it, VertexGroupKind.COLLIDE) != null }
            val collide = perVertex(VertexGroupKind.COLLIDE, if (collideGroup) 0f else 1f)
            for (i in 0 until total) {
                state.invMass[i] = 1f / (m.mass * massWeight[i].coerceAtLeast(0.1f))
                state.damping[i] = m.damping * dampingWeight[i]
                state.windFactor[i] = windWeight[i]
            }

            // Glue: a PIN role anchors the simulated side on the partner; CONSTRAINT welds two simulated sides.
            val anchors = arrayOfNulls<Anchor>(total)
            val weldA = ArrayList<Int>(); val weldB = ArrayList<Int>(); val weldWA = ArrayList<Float>(); val weldWB = ArrayList<Float>()
            for (glue in model.glues) {
                val role = edit.glueRoles[glueKey(glue)] ?: GlueRole.IGNORE
                if (role == GlueRole.IGNORE) continue
                val offsetA = offsets[glue.meshA]
                val offsetB = offsets[glue.meshB]
                when {
                    role == GlueRole.CONSTRAINT && offsetA != null && offsetB != null -> for (pair in glue.pairs) {
                        if (pair.indexA >= counts.getValue(glue.meshA) || pair.indexB >= counts.getValue(glue.meshB)) continue
                        weldA += offsetA + pair.indexA; weldB += offsetB + pair.indexB
                        weldWA += pair.weightA; weldWB += pair.weightB
                    }
                    role == GlueRole.CONSTRAINT -> notes += "Glue ${glueKey(glue)} is a constraint but only one side is simulated"
                    role == GlueRole.PIN && (offsetA == null) == (offsetB == null) ->
                        notes += "Glue ${glueKey(glue)} pins only when exactly one side is simulated"
                    else -> for (pair in glue.pairs) {
                        val simulatedIsA = offsetA != null
                        val own = if (simulatedIsA) pair.indexA else pair.indexB
                        val other = if (simulatedIsA) pair.indexB else pair.indexA
                        val id = if (simulatedIsA) glue.meshA else glue.meshB
                        if (own >= counts.getValue(id)) continue
                        val i = offsets.getValue(id) + own
                        // The default half-and-half weld holds fully: a glue weight of 0.5 is a full pin.
                        val strength = ((if (simulatedIsA) pair.weightA else pair.weightB) * glue.intensity * 2f).coerceIn(0f, 1f)
                        if (strength >= pin[i]) {
                            pin[i] = strength
                            anchors[i] = Anchor(if (simulatedIsA) glue.meshB else glue.meshA, other)
                        }
                    }
                }
            }

            // Stretch along every mesh edge; bend across every inner edge (the two opposite corners).
            val sa = ArrayList<Int>(); val sb = ArrayList<Int>(); val sr = ArrayList<Float>(); val sc = ArrayList<Float>(); val sq = ArrayList<Float>()
            val ba = ArrayList<Int>(); val bb = ArrayList<Int>(); val br = ArrayList<Float>(); val bc = ArrayList<Float>()
            val neighbours = Array(total) { ArrayList<Pair<Int, Float>>() }
            val world = CpuDeformationEvaluator().evaluate(model, emptyMap()).worldPositions
            val rest = FloatArray(total * 2)
            for ((id, mesh) in targets) {
                val offset = offsets.getValue(id)
                val positions = world[id] ?: mesh.positions.also { notes += "${id.raw} is hidden at the default pose; using its rest mesh" }
                positions.copyInto(rest, offset * 2, 0, mesh.vertexCount * 2)
                val opposite = HashMap<Long, Int>()
                for (t in 0 until mesh.indices.size / 3) for (e in 0..2) {
                    val a = mesh.indices[t * 3 + e]
                    val b = mesh.indices[t * 3 + (e + 1) % 3]
                    val c = mesh.indices[t * 3 + (e + 2) % 3]
                    val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
                    val other = opposite.put(key, c)
                    if (other == null) {
                        val length = hypot(positions[a * 2] - positions[b * 2], positions[a * 2 + 1] - positions[b * 2 + 1])
                        sa += offset + a; sb += offset + b; sr += length
                        val stiffness = (stiffnessWeight[offset + a] + stiffnessWeight[offset + b]) / 2f
                        sc += compliance(m.stretch * stiffness)
                        sq += bendCompliance(m.bend * stiffness)
                        neighbours[offset + a] += (offset + b) to length
                        neighbours[offset + b] += (offset + a) to length
                    } else if (other != c) {
                        ba += offset + other; bb += offset + c
                        br += hypot(positions[other * 2] - positions[c * 2], positions[other * 2 + 1] - positions[c * 2 + 1])
                        bc += bendCompliance(m.bend * (stiffnessWeight[offset + other] + stiffnessWeight[offset + c]) / 2f)
                    }
                }
            }

            // Long-range limits from the nearest firmly pinned particle, by path length along the mesh.
            val roots = (0 until total).filter { pin[it] >= ROOT_PIN }
            val lraParticle = ArrayList<Int>(); val lraRoot = ArrayList<Int>(); val lraDistance = ArrayList<Float>()
            if (roots.isNotEmpty() && m.slack < 1f) {
                val distance = FloatArray(total) { Float.MAX_VALUE }
                val root = IntArray(total) { -1 }
                val queue = PriorityQueue<Pair<Float, Int>>(compareBy({ it.first }, { it.second }))
                for (r in roots) { distance[r] = 0f; root[r] = r; queue += 0f to r }
                while (queue.isNotEmpty()) {
                    val (d, i) = queue.poll()
                    if (d > distance[i]) continue
                    for ((j, length) in neighbours[i]) {
                        val next = d + length
                        if (next < distance[j]) { distance[j] = next; root[j] = root[i]; queue += next to j }
                    }
                }
                for (i in 0 until total) if (root[i] >= 0 && root[i] != i) {
                    lraParticle += i; lraRoot += root[i]; lraDistance += distance[i] * (1f + m.slack)
                }
            } else if (roots.isEmpty()) {
                notes += "Nothing is pinned: paint a PIN group or set a glue to pin, or the body falls away"
            }

            val goal = FloatArray(total) { goalCompliance(m.goal * goalWeight[it]) }
            val colliderMeshes = edit.colliders.mapNotNull { ref ->
                val drawable = model.drawables.firstOrNull { it.id.raw == ref.drawableId }
                val mesh = drawable?.mesh
                if (mesh == null) { notes += "Collider ${ref.drawableId} not found"; return@mapNotNull null }
                val weights = ref.group?.let { name ->
                    model.vertexGroups.firstOrNull { it.drawableId == drawable.id && it.name == name }?.weights
                        ?: run { notes += "Collider group $name not found on ${ref.drawableId}"; return@mapNotNull null }
                }
                val triangles = if (weights == null) mesh.indices else {
                    (0 until mesh.indices.size / 3).filter { t -> (0..2).all { weights.getOrElse(mesh.indices[t * 3 + it]) { 0f } >= 0.5f } }
                        .flatMap { t -> listOf(mesh.indices[t * 3], mesh.indices[t * 3 + 1], mesh.indices[t * 3 + 2]) }.toIntArray()
                }
                if (triangles.isEmpty()) { notes += "Collider ${ref.drawableId} has no triangle fully in its group"; null }
                else drawable.id to TriangleRegionCollider(triangles, ref.margin)
            }

            state.reset(rest)
            val solver = XpbdSolver(
                state,
                stretch = DistanceConstraints(sa.toIntArray(), sb.toIntArray(), sr.toFloatArray(), sc.toFloatArray(), sq.toFloatArray()),
                bend = DistanceConstraints(ba.toIntArray(), bb.toIntArray(), br.toFloatArray(), bc.toFloatArray()),
                welds = WeldConstraints(weldA.toIntArray(), weldB.toIntArray(), weldWA.toFloatArray(), weldWB.toFloatArray(), FloatArray(weldA.size)),
                longRange = LongRangeConstraints(lraParticle.toIntArray(), lraRoot.toIntArray(), lraDistance.toFloatArray()),
                pinWeight = pin,
                goalCompliance = goal,
                colliding = BooleanArray(total) { collide[it] >= 0.5f },
                colliders = colliderMeshes.map { it.second },
                settings = settings,
            )
            return SimScene(edit, solver, offsets, counts, anchors, colliderMeshes, notes)
        }
    }
}
