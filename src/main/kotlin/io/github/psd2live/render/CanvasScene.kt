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
)

/**
 * A finished frame: premultiplied RGBA pixels, top row first, and the camera they were drawn with, so the
 * presenter can move it with the camera until the next one arrives.
 */
internal class RenderedFrame(val bitmap: Bitmap, val width: Int, val height: Int, val viewport: CanvasViewport, val scene: CanvasScene)
