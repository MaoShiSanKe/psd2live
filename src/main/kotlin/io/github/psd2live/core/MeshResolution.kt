package io.github.psd2live.core

import org.umamo.format.art.SourceArt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The scale mesh lengths are measured at.
 *
 * Mesh settings, and every pixel tolerance inside the generator, are pixel lengths. Measured in source
 * pixels they ask a 6000-pixel document for nine times the vertices of a 2000-pixel one, and trace
 * contours nine times as long, for the same drawing.
 * In [MeshUnits.DOCUMENT] one mesh unit is one pixel of the document scaled to [REFERENCE_SIDE], and the
 * generator works on the layer's alpha reduced to that scale, so both the mesh and its cost stay the
 * same however large the source is.
 */
object MeshResolution {
	/** The longer side, in pixels, of the document one mesh unit is a pixel of. */
	const val REFERENCE_SIDE = 2048

	/** Below this scale the reduction would save nothing and only blur the contour. */
	private const val MIN_REDUCTION = 1.05f

	/** Source pixels per mesh unit for a [width] by [height] document; never below 1. */
	fun unitScale(units: MeshUnits, width: Int, height: Int): Float = when (units) {
		MeshUnits.PIXELS -> 1f
		MeshUnits.DOCUMENT -> {
			val scale = max(width, height).toFloat() / REFERENCE_SIDE
			if (scale.isFinite() && scale >= MIN_REDUCTION) scale else 1f
		}
	}

	fun unitScale(config: PipelineConfig, source: SourceArt): Float =
		unitScale(config.meshUnits, source.widthPx, source.heightPx)

	/** A layer's alpha reduced by [scale]: working pixel (x, y) covers source [x * scale, (x + 1) * scale). */
	internal class ReducedAlpha(val width: Int, val height: Int, val rgba: ByteArray, val scale: Double)

	/**
	 * Reduces [rgba] by [scale], keeping each working pixel's highest alpha so the reduced mask covers every
	 * pixel the source paints: a hairline stays a line and nothing opaque falls outside the mesh. Colour is
	 * dropped; the generator reads only alpha. Null when [scale] does not reduce.
	 */
	internal fun reduce(width: Int, height: Int, rgba: ByteArray, scale: Float): ReducedAlpha? {
		if (!scale.isFinite() || scale < MIN_REDUCTION || width <= 0 || height <= 0) return null
		val factor = scale.toDouble()
		val reducedWidth = max(1, ceil(width / factor).toInt())
		val reducedHeight = max(1, ceil(height / factor).toInt())
		fun spans(working: Int, source: Int) = IntArray((working + 1) * 2).also { spans ->
			for (i in 0 until working) {
				val first = floor(i * factor).toInt().coerceIn(0, source - 1)
				val end = ceil((i + 1) * factor).toInt().coerceIn(first + 1, source)
				spans[i * 2] = first
				spans[i * 2 + 1] = end
			}
		}
		val columns = spans(reducedWidth, width)
		val rows = spans(reducedHeight, height)
		// Separable maximum: across each source row first, then down each working column.
		val horizontal = ByteArray(reducedWidth * height)
		for (y in 0 until height) {
			val row = y * width
			for (x in 0 until reducedWidth) {
				var peak = 0
				for (sx in columns[x * 2] until columns[x * 2 + 1]) {
					val alpha = rgba[(row + sx) * 4 + 3].toInt() and 0xff
					if (alpha > peak) { peak = alpha; if (peak == 255) break }
				}
				horizontal[y * reducedWidth + x] = peak.toByte()
			}
		}
		val reduced = ByteArray(reducedWidth * reducedHeight * 4)
		for (y in 0 until reducedHeight) {
			val first = rows[y * 2]
			val end = rows[y * 2 + 1]
			for (x in 0 until reducedWidth) {
				var peak = 0
				for (sy in first until end) {
					val alpha = horizontal[sy * reducedWidth + x].toInt() and 0xff
					if (alpha > peak) { peak = alpha; if (peak == 255) break }
				}
				reduced[(y * reducedWidth + x) * 4 + 3] = peak.toByte()
			}
		}
		return ReducedAlpha(reducedWidth, reducedHeight, reduced, factor)
	}
}
