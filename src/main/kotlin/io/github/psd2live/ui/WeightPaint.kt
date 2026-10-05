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
    /** [base] with [reach] applied in [mode] at [amount]; [neighbors] is needed only for [WeightPaintMode.SMOOTH]. */
    fun apply(
        base: FloatArray,
        reach: FloatArray,
        mode: WeightPaintMode,
        amount: Float,
        neighbors: List<IntArray>? = null,
    ): FloatArray {
        val normalized = FloatArray(reach.size) { base.getOrElse(it) { 0f } }
        if (mode == WeightPaintMode.SMOOTH && neighbors == null) return normalized
        return io.github.psd2live.core.CanvasWeightAuthoring.apply(normalized, reach,
            io.github.psd2live.core.CanvasWeightAuthoring.Mode.valueOf(mode.name), amount.coerceIn(0f, 1f), neighbors)
    }

    /**
     * A linear gradient's reach: 1 at or before [from], 0 at or past [to], straight in between along the
     * drag. A drag too short to have a direction reaches nothing.
     */
    fun gradient(points: List<Offset>, from: Offset, to: Offset): FloatArray {
        fun point(value: Offset) = io.github.psd2live.core.CanvasBrushPoint(value.x, value.y)
        return io.github.psd2live.core.CanvasWeightAuthoring.gradient(points.map(::point), point(from), point(to))
    }

    /** The mode a stroke really runs in: Alt swaps adding and subtracting, and leaves the other two alone. */
    fun effective(mode: WeightPaintMode, alt: Boolean): WeightPaintMode = when {
        !alt -> mode
        mode == WeightPaintMode.ADD -> WeightPaintMode.SUBTRACT
        mode == WeightPaintMode.SUBTRACT -> WeightPaintMode.ADD
        else -> mode
    }
}
