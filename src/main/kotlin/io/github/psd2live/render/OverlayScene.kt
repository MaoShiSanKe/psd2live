package io.github.psd2live.render

/**
 * Guide geometry the GPU draws over the artwork, in paint order: each item is one draw, and a later item lies
 * over an earlier one, as the Java2D guides it replaces painted them.
 *
 * Coordinates are world units (the artwork's own space, y up), so the guides move with the camera exactly as
 * the artwork does. Sizes (widths, radii) are device pixels.
 */
class OverlayScene(val items: List<OverlayItem>) {
	companion object {
		val EMPTY = OverlayScene(emptyList())
	}
}

sealed interface OverlayItem

/**
 * Line segments in one colour and width.
 *
 * @property argb     Unpremultiplied ARGB.
 * @property width    Stroke width in device pixels.
 * @property segments x0, y0, x1, y1 per segment, world units.
 */
class LineBatch(val argb: Int, val width: Float, val segments: FloatArray) : OverlayItem

/**
 * Discs in one style.
 *
 * @property radius  Outer radius in device pixels.
 * @property ring    Width of the outer ring drawn in [strokeArgb]; 0 for a plain disc.
 * @property centers x, y per point, world units.
 */
class PointBatch(val fillArgb: Int, val strokeArgb: Int, val radius: Float, val ring: Float, val centers: FloatArray) : OverlayItem

/**
 * Closed outlines filled in one colour by the even-odd rule, so concave outlines and holes fill right. Where two
 * outlines of one batch overlap they cancel, as even-odd does; shapes meant to overlap go in separate batches.
 *
 * @property contours x, y per vertex of each closed outline, world units.
 */
class FillBatch(val argb: Int, val contours: List<FloatArray>) : OverlayItem
