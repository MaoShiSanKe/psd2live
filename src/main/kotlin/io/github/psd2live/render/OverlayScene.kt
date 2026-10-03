package io.github.psd2live.render

/**
 * Guide geometry the GPU draws over the artwork, grouped by style so each group is one draw.
 *
 * Coordinates are world units (the artwork's own space, y up), so the guides move with the camera exactly as
 * the artwork does.
 */
class OverlayScene(val lines: List<LineBatch>, val points: List<PointBatch>) {
	companion object {
		val EMPTY = OverlayScene(emptyList(), emptyList())
	}
}

/**
 * Line segments in one colour and width.
 *
 * @property argb     Unpremultiplied ARGB.
 * @property width    Stroke width in device pixels.
 * @property segments x0, y0, x1, y1 per segment, world units.
 */
class LineBatch(val argb: Int, val width: Float, val segments: FloatArray)

/**
 * Discs in one style.
 *
 * @property radius  Outer radius in device pixels.
 * @property ring    Width of the outer ring drawn in [strokeArgb]; 0 for a plain disc.
 * @property centers x, y per point, world units.
 */
class PointBatch(val fillArgb: Int, val strokeArgb: Int, val radius: Float, val ring: Float, val centers: FloatArray)
