package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset

/** How a weight stroke or gradient combines with the weights already in the group. */
internal enum class WeightPaintMode(val labelKey: String, val symbol: String) {
    ADD("editor.weightAdd", "+"),
    SUBTRACT("editor.weightSubtract", "−"),
    /** Pulls the weights toward the brush strength. */
    SET("editor.weightSet", "="),
    /** Pulls each weight toward the mean of its neighbours (Blender's Blur). */
    SMOOTH("editor.weightSmooth", "~"),
}

/**
 * The simulation weight tools' arithmetic, kept apart from the canvas so it can be tested on plain arrays.
 * A stroke or a gradient only decides how strongly it reaches each vertex (0..1); [apply] turns that reach
 * into weights, the same way for both tools.
 */
internal object WeightPaint {
    private const val SMOOTH_PASSES = 4

    /** [base] with [reach] applied in [mode] at [amount]; [neighbors] is needed only for [WeightPaintMode.SMOOTH]. */
    fun apply(
        base: FloatArray,
        reach: FloatArray,
        mode: WeightPaintMode,
        amount: Float,
        neighbors: List<IntArray>? = null,
    ): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        val n = reach.size
        if (mode == WeightPaintMode.SMOOTH) {
            var current = FloatArray(n) { base.getOrElse(it) { 0f } }
            if (neighbors == null) return current
            repeat(SMOOTH_PASSES) {
                val next = current.copyOf()
                for (i in 0 until n) {
                    val r = reach[i]
                    val adjacent = neighbors.getOrNull(i) ?: continue
                    if (r <= 0f || adjacent.isEmpty()) continue
                    var sum = 0f
                    var count = 0
                    for (j in adjacent) if (j in 0 until n) { sum += current[j]; count++ }
                    if (count == 0) continue
                    next[i] = current[i] + (sum / count - current[i]) * a * r
                }
                current = next
            }
            return FloatArray(n) { current[it].coerceIn(0f, 1f) }
        }
        return FloatArray(n) { i ->
            val r = reach[i]
            val w = base.getOrElse(i) { 0f }
            when {
                r <= 0f -> w
                mode == WeightPaintMode.SET -> w + (a - w) * r
                mode == WeightPaintMode.SUBTRACT -> w - a * r
                else -> w + a * r
            }.coerceIn(0f, 1f)
        }
    }

    /**
     * A linear gradient's reach: 1 at or before [from], 0 at or past [to], straight in between along the
     * drag. A drag too short to have a direction reaches nothing.
     */
    fun gradient(points: List<Offset>, from: Offset, to: Offset): FloatArray {
        val axis = to - from
        val length2 = axis.x * axis.x + axis.y * axis.y
        if (length2 < 1f) return FloatArray(points.size)
        return FloatArray(points.size) { i ->
            val d = points[i] - from
            val t = (d.x * axis.x + d.y * axis.y) / length2
            (1f - t).coerceIn(0f, 1f)
        }
    }

    /** The mode a stroke really runs in: Alt swaps adding and subtracting, and leaves the other two alone. */
    fun effective(mode: WeightPaintMode, alt: Boolean): WeightPaintMode = when {
        !alt -> mode
        mode == WeightPaintMode.ADD -> WeightPaintMode.SUBTRACT
        mode == WeightPaintMode.SUBTRACT -> WeightPaintMode.ADD
        else -> mode
    }
}
