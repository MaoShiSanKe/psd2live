package io.github.psd2live.core

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * The canvas controls of a swing on one target Warp: what to draw, where the handles sit, and which
 * setting a dragged handle produces. Points are in canvas pixels (Y down), the space the deformer
 * cascade outputs before the renderer negates Y.
 *
 * Handles sit on the +1 pose of the [motion] being edited, so dragging one puts that part of the pose
 * under the pointer: the tip sets the sway (and flips when dragged across) and the rise, the middle of
 * the strand sets the softness, a tip corner sets the width change and how level the tip edge stays, and
 * a pivot handle picks the pinned edge.
 */
internal class SwingGizmo private constructor(
    private val space: SwingSpace,
    private val rest: FloatArray,
    val edit: RigSwingEdit,
    val fulcrum: SwingFulcrum,
    /** The index in [RigSwingEdit.motions] the handles edit. */
    val motion: Int,
) {
    enum class Handle { PIVOT_TOP, PIVOT_BOTTOM, PIVOT_LEFT, PIVOT_RIGHT, TIP, MID, CORNER_START, CORNER_END }

    private val warp = space.warp
    private val rows = warp.rows
    private val columns = warp.columns

    private fun index(r: Int, c: Int) = r * (columns + 1) + c
    private fun local(i: Int) = rest[i * 2] to rest[i * 2 + 1]
    private fun localMid(indices: List<Int>): Pair<Float, Float> =
        indices.map(::local).let { ps -> ps.map { it.first }.average().toFloat() to ps.map { it.second }.average().toFloat() }
    private fun row(r: Int) = (0..columns).map { index(r, it) }
    private fun column(c: Int) = (0..rows).map { index(it, c) }
    private fun edge(f: SwingFulcrum) = when (f) {
        SwingFulcrum.TOP, SwingFulcrum.AUTO -> row(0)
        SwingFulcrum.BOTTOM -> row(rows)
        SwingFulcrum.LEFT -> column(0)
        SwingFulcrum.RIGHT -> column(columns)
    }
    private fun opposite(f: SwingFulcrum) = when (f) {
        SwingFulcrum.TOP, SwingFulcrum.AUTO -> SwingFulcrum.BOTTOM
        SwingFulcrum.BOTTOM -> SwingFulcrum.TOP
        SwingFulcrum.LEFT -> SwingFulcrum.RIGHT
        SwingFulcrum.RIGHT -> SwingFulcrum.LEFT
    }

    private val kind = edit.motions[motion].kind
    private val shape = edit.motions[motion].shape
    private fun reshaped(change: (SwingShape) -> SwingShape) = edit.withShape(motion, change)

    private fun world(p: Pair<Float, Float>) = space.world(p.first, p.second)

    /** Local [targets] with the edited motion at pose [value] (every segment) and the others at rest, mapped to world. */
    private fun posed(e: RigSwingEdit, value: Float, targets: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val flat = FloatArray(targets.size * 2) { if (it % 2 == 0) targets[it / 2].first else targets[it / 2].second }
        val shapes = e.motions.map { it.shape.deformer(it.kind, fulcrum, it.segments) }
        val moved = SwingDeformer.compose(shapes, flat) { m, t ->
            SwingDeformer.transform(rest, rows, columns, space.sx, space.sy, shapes[m], FloatArray(shapes[m].segments) { if (m == motion) value else 0f }, t)
        }
        return targets.indices.map { world(moved[it * 2] to moved[it * 2 + 1]) }
    }

    private val boundary: List<Int> = row(0) + column(columns).drop(1) + row(rows).reversed().drop(1) + column(0).reversed().drop(1)
    private val rootLocal = localMid(edge(fulcrum))
    private val tipLocal = localMid(edge(opposite(fulcrum)))
    private fun axisLocal(s: Float) = (rootLocal.first + (tipLocal.first - rootLocal.first) * s) to
        (rootLocal.second + (tipLocal.second - rootLocal.second) * s)
    private val tipEdge = edge(opposite(fulcrum))
    private val cornerLocals = listOf(local(tipEdge.first()), local(tipEdge.last()))

    /** The lattice outline at pose [value]; 0 is the rest pose. */
    fun outline(value: Float): List<Pair<Float, Float>> = cache.getOrPut("outline$value") { posed(edit, value, boundary.map(::local)) }

    /** The strand's centerline at pose [value], pinned edge first. */
    fun centerline(value: Float): List<Pair<Float, Float>> = cache.getOrPut("center$value") { posed(edit, value, (0..16).map { axisLocal(it / 16f) }) }

    // The gizmo is immutable and the canvas redraws every frame, so each pose is computed once.
    private val cache = HashMap<String, List<Pair<Float, Float>>>()

    /** The pinned edge at rest. */
    val pinnedEdge: List<Pair<Float, Float>> by lazy { edge(fulcrum).map { world(local(it)) } }

    /** The midpoint of every rest edge; the pinned one is [fulcrum]. */
    val pivots: Map<SwingFulcrum, Pair<Float, Float>> by lazy {
        listOf(SwingFulcrum.TOP, SwingFulcrum.BOTTOM, SwingFulcrum.LEFT, SwingFulcrum.RIGHT).associateWith { world(localMid(edge(it))) }
    }

    /** The draggable handles at their current positions. */
    fun handles(): Map<Handle, Pair<Float, Float>> = handleCache

    private val handleCache: Map<Handle, Pair<Float, Float>> by lazy {
        val (tip, mid) = posed(edit, 1f, listOf(axisLocal(1f), axisLocal(0.5f)))
        val corners = posed(edit, 1f, cornerLocals)
        mapOf(
            Handle.PIVOT_TOP to pivots.getValue(SwingFulcrum.TOP), Handle.PIVOT_BOTTOM to pivots.getValue(SwingFulcrum.BOTTOM),
            Handle.PIVOT_LEFT to pivots.getValue(SwingFulcrum.LEFT), Handle.PIVOT_RIGHT to pivots.getValue(SwingFulcrum.RIGHT),
            Handle.TIP to tip, Handle.MID to mid, Handle.CORNER_START to corners[0], Handle.CORNER_END to corners[1],
        )
    }

    // The rest frame in world: pinned-edge midpoint, unit axis toward the tip, and the unit normal on the
    // side a positive value moves toward.
    private val root = world(rootLocal)
    private val length = world(tipLocal).let { hypot(it.first - root.first, it.second - root.second) }.coerceAtLeast(1e-6f)
    private val ex = (world(tipLocal).first - root.first) / length
    private val ey = (world(tipLocal).second - root.second) / length
    private val direction = if (kind == SwingKind.LATERAL) world(localMid(column(columns))).let { b -> world(localMid(column(0))).let { a -> b.first - a.first to b.second - a.second } }
        else world(localMid(row(rows))).let { b -> world(localMid(row(0))).let { a -> b.first - a.first to b.second - a.second } }
    private val normal = (-ey to ex).let { n -> if (n.first * direction.first + n.second * direction.second < 0f) -n.first to -n.second else n }
    private fun along(p: Pair<Float, Float>) = ((p.first - root.first) * ex + (p.second - root.second) * ey) / length
    private fun across(p: Pair<Float, Float>) = ((p.first - root.first) * normal.first + (p.second - root.second) * normal.second) / length

    /** The swing with [handle] dragged to world point [p]. */
    fun drag(handle: Handle, p: Pair<Float, Float>): RigSwingEdit = when (handle) {
        Handle.PIVOT_TOP -> edit.copy(fulcrum = SwingFulcrum.TOP)
        Handle.PIVOT_BOTTOM -> edit.copy(fulcrum = SwingFulcrum.BOTTOM)
        Handle.PIVOT_LEFT -> edit.copy(fulcrum = SwingFulcrum.LEFT)
        Handle.PIVOT_RIGHT -> edit.copy(fulcrum = SwingFulcrum.RIGHT)
        Handle.TIP -> dragTip(p)
        Handle.MID -> fit(0f, 1f, { v -> reshaped { it.copy(softness = v) } }, p) { e -> posed(e, 1f, listOf(axisLocal(0.5f))).single() }
        Handle.CORNER_START, Handle.CORNER_END -> dragCorner(handle, p)
    }

    /**
     * A tip corner sits off the tip of the centerline by the half width, scaled by `1 + zoom` and turned
     * with the cross-section, which turns less the more parallel the swing is. So the pointer's distance
     * from the tip gives the width change directly, and its turn the parallel setting: the turn is linear
     * in that setting, so one probe pose measures its rate. Solving beats a search, which re-poses the
     * lattice hundreds of times per pointer move.
     */
    private fun dragCorner(handle: Handle, p: Pair<Float, Float>): RigSwingEdit {
        val tip = handleCache.getValue(Handle.TIP)
        fun offset(q: Pair<Float, Float>) = (q.first - tip.first) to (q.second - tip.second)
        fun turn(a: Pair<Float, Float>, b: Pair<Float, Float>) =
            kotlin.math.atan2(a.first * b.second - a.second * b.first, a.first * b.first + a.second * b.second)
        fun rounded(v: Float) = kotlin.math.round(v * 100f) / 100f
        val now = offset(handleCache.getValue(handle))
        val target = offset(p)
        val radius = hypot(now.first, now.second)
        if (radius < 1e-3f) return edit
        val zoom = ((1f + shape.zoom) * hypot(target.first, target.second) / radius - 1f).coerceIn(-0.5f, 0.5f)
        val zoomed = reshaped { it.copy(zoom = rounded(zoom)) }
        if (!shape.deformer(kind, fulcrum, 1).bends || shape.magnitude == 0f) return zoomed
        val step = if (shape.parallel <= 0.5f) 0.25f else -0.25f
        val corner = cornerLocals[if (handle == Handle.CORNER_START) 0 else 1]
        val probe = offset(posed(edit.withShape(motion) { it.copy(parallel = it.parallel + step) }, 1f, listOf(corner)).single())
        val rate = turn(now, probe) / step
        if (abs(rate) < 1e-4f) return zoomed
        val parallel = (shape.parallel + turn(now, target) / rate).coerceIn(0f, 1f)
        return zoomed.withShape(motion) { it.copy(parallel = rounded(parallel)) }
    }

    private fun dragTip(p: Pair<Float, Float>): RigSwingEdit {
        val max = RigSwingEdit.MAX_MAGNITUDE
        if (!shape.deformer(kind, fulcrum, 1).bends) {
            // A stretch moves the tip along the axis; which way +1 goes follows the kind's screen direction.
            val forward = sign(ex * direction.first + ey * direction.second).takeIf { it != 0f } ?: 1f
            val signed = (along(p) - 1f) * forward
            return reshaped { it.copy(flip = signed < 0f, magnitude = abs(signed).coerceAtMost(max)) }
        }
        val signed = across(p)
        val swayed = reshaped { it.copy(flip = signed < 0f, magnitude = abs(signed).coerceAtMost(max), lift = 0f) }
        // The arc already lifts the tip; whatever the pointer adds on top of that is the extra rise.
        val natural = along(posed(swayed, 1f, listOf(axisLocal(1f))).single())
        return swayed.withShape(motion) { it.copy(lift = (natural - along(p)).coerceIn(-0.5f, 0.5f)) }
    }

    /** The value in [low]..[high] whose handle lands closest to [p]: a scan, then a golden-section refine. */
    private fun fit(low: Float, high: Float, apply: (Float) -> RigSwingEdit, p: Pair<Float, Float>,
        handle: (RigSwingEdit) -> Pair<Float, Float>): RigSwingEdit {
        fun cost(v: Float) = handle(apply(v)).let { hypot(it.first - p.first, it.second - p.second) }
        val steps = 24
        val step = (high - low) / steps
        var best = low; var bestCost = Float.MAX_VALUE
        for (k in 0..steps) { val v = low + step * k; val c = cost(v); if (c < bestCost) { bestCost = c; best = v } }
        var a = (best - step).coerceAtLeast(low); var b = (best + step).coerceAtMost(high)
        val golden = 0.618034f
        repeat(16) {
            val c = b - (b - a) * golden; val d = a + (b - a) * golden
            if (cost(c) < cost(d)) b = d else a = c
        }
        return apply(((a + b) / 2f * 100f).let { kotlin.math.round(it) / 100f }.coerceIn(low, high))
    }

    companion object {
        /**
         * The controls of [edit] on [targetId], by default its first target; null when that is not a Warp.
         * [values] is the pose on screen, so the handles sit on the art as drawn; the swing itself is at rest.
         * [motion] is the direction the handles edit.
         */
        fun of(model: PuppetModel, edit: RigSwingEdit, targetId: String = edit.targets.first(),
            values: Map<ParameterId, Float> = emptyMap(), motion: Int = 0): SwingGizmo? {
            val warp = model.deformers.firstOrNull { it.id.raw == targetId } as? Deformer.Warp ?: return null
            val own = edit.parameterIds.toSet()
            val pose: (ParameterId) -> Float = { id ->
                val parameter = model.parameters.firstOrNull { it.id == id }
                if (id.raw in own) parameter?.default ?: 0f else values[id] ?: parameter?.default ?: 0f
            }
            val space = SwingSpace(model, warp, pose)
            val rest = space.rest ?: return null
            val fulcrum = if (edit.fulcrum == SwingFulcrum.AUTO) space.autoFulcrum(rest) else edit.fulcrum
            return SwingGizmo(space, rest, edit, fulcrum, motion.coerceIn(0, edit.motions.size - 1))
        }
    }
}
