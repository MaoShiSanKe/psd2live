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
 * the strand sets the softness, a tip corner sets the width change and how level the tip edge stays, the
 * axis handle past the rest tip turns the whole swing rectangle about its pinned edge's midpoint, the
 * handle on that midpoint moves the rectangle, and a pivot handle picks the pinned edge. Everything drawn is that rectangle, not the lattice.
 */
internal class SwingGizmo private constructor(
    private val space: SwingSpace,
    private val rest: FloatArray,
    val edit: RigSwingEdit,
    val fulcrum: SwingFulcrum,
    /** The index in [RigSwingEdit.motions] the handles edit. */
    val motion: Int,
) {
    enum class Handle { PIVOT_TOP, PIVOT_BOTTOM, PIVOT_LEFT, PIVOT_RIGHT, TIP, MID, CORNER_START, CORNER_END, AXIS, MOVE }

    private val warp = space.warp
    private val rows = warp.rows
    private val columns = warp.columns

    private fun index(r: Int, c: Int) = r * (columns + 1) + c
    private fun local(i: Int) = rest[i * 2] to rest[i * 2 + 1]
    private fun localMid(indices: List<Int>): Pair<Float, Float> =
        indices.map(::local).let { ps -> ps.map { it.first }.average().toFloat() to ps.map { it.second }.average().toFloat() }
    private fun row(r: Int) = (0..columns).map { index(r, it) }
    private fun column(c: Int) = (0..rows).map { index(it, c) }
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
        val shapes = e.motions.map { it.shape.deformer(it.kind, fulcrum, it.segments, e.placement) }
        val moved = SwingDeformer.compose(shapes, flat) { m, t ->
            SwingDeformer.transform(rest, rows, columns, space.sx, space.sy, shapes[m], FloatArray(shapes[m].segments) { if (m == motion) value else 0f }, t)
        }
        return targets.indices.map { world(moved[it * 2] to moved[it * 2 + 1]) }
    }

    /** A point of the rest swing rectangle placed by [placement], in local units; see [SwingDeformer.rectPoint]. */
    private fun rectLocal(s: Float, t: Float = 0f, placement: SwingDeformer.Placement = edit.placement) =
        SwingDeformer.rectPoint(rest, rows, columns, space.sx, space.sy, fulcrum, placement, s, t)
    private fun axisLocal(s: Float, placement: SwingDeformer.Placement = edit.placement) = rectLocal(s, 0f, placement)
    private val rootLocal = axisLocal(0f)
    private val tipLocal = axisLocal(1f)
    private val cornerLocals = listOf(rectLocal(1f, -0.5f), rectLocal(1f, 0.5f))

    /** The swing rectangle's rest outline, pinned edge first; the long sides are sampled finely so they bend smoothly. */
    private val boundary: List<Pair<Float, Float>> = buildList {
        val across = 4; val along = 16
        for (k in 0 until across) add(rectLocal(0f, -0.5f + k.toFloat() / across))
        for (k in 0 until along) add(rectLocal(k.toFloat() / along, 0.5f))
        for (k in 0 until across) add(rectLocal(1f, 0.5f - k.toFloat() / across))
        for (k in 0 until along) add(rectLocal(1f - k.toFloat() / along, -0.5f))
    }

    /** The swing rectangle's outline at pose [value]; 0 is the rest pose. */
    fun outline(value: Float): List<Pair<Float, Float>> = cache.getOrPut("outline$value") { posed(edit, value, boundary) }

    /** The strand's centerline at pose [value], pinned edge first. */
    fun centerline(value: Float): List<Pair<Float, Float>> = cache.getOrPut("center$value") { posed(edit, value, (0..16).map { axisLocal(it / 16f) }) }

    // The gizmo is immutable and the canvas redraws every frame, so each pose is computed once.
    private val cache = HashMap<String, List<Pair<Float, Float>>>()

    /** The rest hanging axis, from the pinned edge out to the axis handle. */
    val axis: List<Pair<Float, Float>> by lazy { listOf(world(rootLocal), world(axisLocal(AXIS_REACH))) }

    /** The swing rectangle's pinned edge at rest. */
    val pinnedEdge: List<Pair<Float, Float>> by lazy { listOf(-0.5f, 0f, 0.5f).map { world(rectLocal(0f, it)) } }

    /**
     * The midpoint of every edge of the rest swing rectangle, by the lattice edge it stands for; the pinned one
     * is [fulcrum]. The sides follow the pinned edge's lattice order: its last point is the right or bottom end.
     */
    val pivots: Map<SwingFulcrum, Pair<Float, Float>> by lazy {
        val sideways = fulcrum == SwingFulcrum.LEFT || fulcrum == SwingFulcrum.RIGHT
        mapOf(
            fulcrum to world(rectLocal(0f)), opposite(fulcrum) to world(rectLocal(1f)),
            (if (sideways) SwingFulcrum.TOP else SwingFulcrum.LEFT) to world(rectLocal(0.5f, -0.5f)),
            (if (sideways) SwingFulcrum.BOTTOM else SwingFulcrum.RIGHT) to world(rectLocal(0.5f, 0.5f)),
        )
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
            Handle.AXIS to axis[1], Handle.MOVE to axis[0],
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
        Handle.PIVOT_TOP -> repinned(SwingFulcrum.TOP)
        Handle.PIVOT_BOTTOM -> repinned(SwingFulcrum.BOTTOM)
        Handle.PIVOT_LEFT -> repinned(SwingFulcrum.LEFT)
        Handle.PIVOT_RIGHT -> repinned(SwingFulcrum.RIGHT)
        Handle.TIP -> dragTip(p)
        Handle.MID -> fit(0f, 1f, { v -> reshaped { it.copy(softness = v) } }, p) { e -> posed(e, 1f, listOf(axisLocal(0.5f))).single() }
        Handle.CORNER_START, Handle.CORNER_END -> dragCorner(handle, p)
        Handle.AXIS -> dragAxis(p)
        Handle.MOVE -> dragMove(p)
    }

    /**
     * Moves the rectangle so its pinned midpoint lands on [p]. The parent mapping may bend, so a few Newton
     * steps on the offsets, with the Jacobian measured by probing, stand in for its inverse.
     */
    private fun dragMove(p: Pair<Float, Float>): RigSwingEdit {
        val max = RigSwingEdit.MAX_OFFSET
        var along = edit.offsetAlong; var across = edit.offsetAcross
        fun at(a: Float, c: Float) = world(rectLocal(0f, 0f, SwingDeformer.Placement(edit.tilt, a, c)))
        repeat(4) {
            val h = 0.01f
            val here = at(along, across)
            val rx = p.first - here.first; val ry = p.second - here.second
            if (hypot(rx, ry) < 1e-3f) return@repeat
            val da = at(along + h, across).let { (it.first - here.first) / h to (it.second - here.second) / h }
            val dc = at(along, across + h).let { (it.first - here.first) / h to (it.second - here.second) / h }
            val det = da.first * dc.second - da.second * dc.first
            if (abs(det) < 1e-6f) return@repeat
            along = (along + (rx * dc.second - ry * dc.first) / det).coerceIn(-max, max)
            across = (across + (da.first * ry - da.second * rx) / det).coerceIn(-max, max)
        }
        fun rounded(v: Float) = kotlin.math.round(v * 1000f) / 1000f
        return edit.copy(offsetAlong = rounded(along), offsetAcross = rounded(across))
    }

    /** Pinned on another edge the swing sits square on it again: the turn and move were measured from the old edge. */
    private fun repinned(to: SwingFulcrum) =
        if (to == fulcrum) edit.copy(fulcrum = to) else edit.copy(fulcrum = to, tilt = 0f, offsetAlong = 0f, offsetAcross = 0f)

    /**
     * Turns the axis so the handle points at [p] from the pinned edge's midpoint. Only the direction counts,
     * so the pointer can be anywhere along the ray; near square it snaps back to no tilt.
     */
    private fun dragAxis(p: Pair<Float, Float>): RigSwingEdit {
        val root = world(rootLocal)
        if (hypot(p.first - root.first, p.second - root.second) < 1e-3f) return edit
        val wanted = kotlin.math.atan2(p.second - root.second, p.first - root.first)
        fun cost(tilt: Float): Float {
            val h = world(axisLocal(AXIS_REACH, edit.placement.copy(tilt = tilt)))
            val d = kotlin.math.atan2(h.second - root.second, h.first - root.first) - wanted
            return abs(kotlin.math.atan2(kotlin.math.sin(d), kotlin.math.cos(d)))
        }
        val max = RigSwingEdit.MAX_TILT
        var best = 0f; var bestCost = Float.MAX_VALUE
        var t = -max
        while (t <= max) { val c = cost(t); if (c < bestCost) { bestCost = c; best = t }; t += 1f }
        var a = (best - 1f).coerceAtLeast(-max); var b = (best + 1f).coerceAtMost(max)
        val golden = 0.618034f
        repeat(12) {
            val c = b - (b - a) * golden; val d = a + (b - a) * golden
            if (cost(c) < cost(d)) b = d else a = c
        }
        val tilt = (kotlin.math.round((a + b) / 2f * 10f) / 10f).let { if (abs(it) < AXIS_SNAP) 0f else it }
        return edit.copy(tilt = tilt.coerceIn(-max, max))
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
        /** How far past the rest tip the axis handle sits, so it stays clear of the tip handle. */
        private const val AXIS_REACH = 1.18f
        /** Degrees within which a dragged axis snaps back to square. */
        private const val AXIS_SNAP = 1.5f

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
