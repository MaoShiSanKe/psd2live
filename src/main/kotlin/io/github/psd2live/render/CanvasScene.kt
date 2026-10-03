package io.github.psd2live.render

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.CanvasViewport
import org.jetbrains.skia.Bitmap
import org.umamo.render.eval.DeformedGeometry

/**
 * Everything one frame of a canvas shows, as an immutable snapshot the UI hands to the render thread.
 *
 * It holds references only: the model, the geometry the UI already evaluated and the draw list. The render
 * thread re-uploads a mesh only when one of its arrays is a different instance from the last frame's, which
 * the copy-on-write edits guarantee for exactly the meshes an edit touched.
 *
 * @property width    Target width in device pixels.
 * @property height   Target height in device pixels.
 * @property viewport The camera, in the same device pixels.
 */
internal class CanvasScene(
	val width: Int,
	val height: Int,
	val viewport: CanvasViewport,
	val model: RigPreviewModel,
	val geometry: DeformedGeometry,
	val draws: List<ArtworkDraw>,
	val overlay: OverlayScene = OverlayScene.EMPTY,
	val paint: PaintScene? = null,
) {
	/** This scene carrying the uploads of [earlier], a scene it replaces before the GL thread drew it. */
	fun after(earlier: CanvasScene): CanvasScene {
		val merged = paint?.after(earlier.paint) ?: return this
		return if (merged === paint) this else CanvasScene(width, height, viewport, model, geometry, draws, overlay, merged)
	}
}

/**
 * A finished frame: premultiplied RGBA pixels, top row first, and the camera they were drawn with, so the
 * presenter can move it with the camera until the next one arrives.
 */
internal class RenderedFrame(val bitmap: Bitmap, val width: Int, val height: Int, val viewport: CanvasViewport, val scene: CanvasScene)

/** Premultiplied RGBA pixels for the [width] x [height] area at ([x], [y]) of a paint session's raster. */
internal class PaintUpload(val x: Int, val y: Int, val width: Int, val height: Int, val rgba: ByteArray)

/**
 * The paint session's document raster, drawn over the frame as one texture at its place on the document.
 *
 * @property session  The session the texture belongs to; another session starts a new texture.
 * @property uploads  The areas to write into the texture before drawing, oldest first.
 */
internal class PaintScene(val session: Any, val width: Int, val height: Int, val uploads: List<PaintUpload>) {
	/** This scene with [earlier]'s uploads first, when it is the same session; for a scene that replaces an undrawn one. */
	fun after(earlier: PaintScene?): PaintScene =
		if (earlier == null || earlier.session !== session || earlier.uploads.isEmpty()) this
		else PaintScene(session, width, height, earlier.uploads + uploads)
}
