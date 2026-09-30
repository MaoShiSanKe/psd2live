package io.github.psd2live.core.sim

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A collider made of mesh triangles - the triangles of a leg or face mesh whose corners are in its
 * COLLIDER group. It follows the rig: the caller hands it the mesh's deformed vertices every frame, so a
 * bending knee pushes cloth where the knee actually is, not where a stand-in shape would be.
 *
 * A particle inside any triangle is pushed to the nearest point of the region's outline plus [margin].
 * The outline is the set of edges used by exactly one of the region's triangles, so pushing never stops
 * on an inner edge.
 */
class TriangleRegionCollider(
    /** Corner indices into the source mesh's vertices, three per triangle. */
    private val triangles: IntArray,
    private val margin: Float = 1f,
) : SimCollider {
    private val outline: IntArray = outlineOf(triangles)
    private var positions = FloatArray(0)
    private var previous = FloatArray(0)
    private var frameTime = 0f
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f

    val isEmpty: Boolean get() = triangles.isEmpty()

    /** The mesh's vertices for this frame, [dt] seconds after the last. */
    fun update(meshPositions: FloatArray, dt: Float) {
        previous = if (positions.size == meshPositions.size) positions else meshPositions.copyOf()
        positions = meshPositions.copyOf()
        frameTime = dt
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        for (corner in triangles) {
            minX = min(minX, positions[corner * 2]); maxX = max(maxX, positions[corner * 2])
            minY = min(minY, positions[corner * 2 + 1]); maxY = max(maxY, positions[corner * 2 + 1])
        }
    }

    override fun resolve(x: Float, y: Float, out: FloatArray): Boolean {
        if (triangles.isEmpty() || x < minX - margin || x > maxX + margin || y < minY - margin || y > maxY + margin) return false
        if (!inside(x, y)) return false
        // Nearest outline point; the push goes past it by the margin along the way out.
        var bestX = x
        var bestY = y
        var best = Float.MAX_VALUE
        var k = 0
        while (k < outline.size) {
            val a = outline[k]
            val b = outline[k + 1]
            k += 2
            val ax = positions[a * 2]
            val ay = positions[a * 2 + 1]
            val abx = positions[b * 2] - ax
            val aby = positions[b * 2 + 1] - ay
            val length2 = abx * abx + aby * aby
            val t = if (length2 < 1e-12f) 0f else (((x - ax) * abx + (y - ay) * aby) / length2).coerceIn(0f, 1f)
            val cx = ax + abx * t
            val cy = ay + aby * t
            val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
            if (d < best) { best = d; bestX = cx; bestY = cy }
        }
        val distance = sqrt(best)
        if (distance < 1e-6f) { out[0] = bestX; out[1] = bestY; return true }
        val scale = (distance + margin) / distance
        out[0] = x + (bestX - x) * scale
        out[1] = y + (bestY - y) * scale
        return true
    }

    override fun velocityAt(x: Float, y: Float, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        if (frameTime <= 0f || previous.size != positions.size) return
        // The motion of the nearest corner stands in for the surface's motion there.
        var nearest = -1
        var best = Float.MAX_VALUE
        for (corner in triangles) {
            val dx = positions[corner * 2] - x
            val dy = positions[corner * 2 + 1] - y
            val d = dx * dx + dy * dy
            if (d < best) { best = d; nearest = corner }
        }
        if (nearest < 0) return
        out[0] = (positions[nearest * 2] - previous[nearest * 2]) / frameTime
        out[1] = (positions[nearest * 2 + 1] - previous[nearest * 2 + 1]) / frameTime
    }

    private fun inside(x: Float, y: Float): Boolean {
        var t = 0
        while (t < triangles.size) {
            val a = triangles[t]
            val b = triangles[t + 1]
            val c = triangles[t + 2]
            t += 3
            val ax = positions[a * 2]; val ay = positions[a * 2 + 1]
            val bx = positions[b * 2]; val by = positions[b * 2 + 1]
            val cx = positions[c * 2]; val cy = positions[c * 2 + 1]
            val d1 = (x - bx) * (ay - by) - (ax - bx) * (y - by)
            val d2 = (x - cx) * (by - cy) - (bx - cx) * (y - cy)
            val d3 = (x - ax) * (cy - ay) - (cx - ax) * (y - ay)
            val negative = d1 < 0f || d2 < 0f || d3 < 0f
            val positive = d1 > 0f || d2 > 0f || d3 > 0f
            if (!(negative && positive)) return true
        }
        return false
    }

    companion object {
        /** Edges used by exactly one triangle, as flat (a, b) pairs in first-seen order. */
        fun outlineOf(triangles: IntArray): IntArray {
            val counts = LinkedHashMap<Long, Int>()
            var t = 0
            while (t < triangles.size) {
                for (e in 0..2) {
                    val a = triangles[t + e]
                    val b = triangles[t + (e + 1) % 3]
                    val key = min(a, b).toLong() shl 32 or max(a, b).toLong()
                    counts[key] = (counts[key] ?: 0) + 1
                }
                t += 3
            }
            val edges = counts.filterValues { it == 1 }.keys
            val out = IntArray(edges.size * 2)
            edges.forEachIndexed { i, key -> out[i * 2] = (key shr 32).toInt(); out[i * 2 + 1] = (key and 0xffffffffL).toInt() }
            return out
        }
    }
}
