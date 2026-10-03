package io.github.psd2live.ui

import io.github.psd2live.ui.utils.toSkiaImage
import io.github.psd2live.core.PackedAtlas
import io.github.psd2live.core.RigPreviewModel
import org.jetbrains.skia.*
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.glsl.SELECTION_TINT_STRENGTH
import org.umamo.runtime.model.DrawableId


/** Draw the editing texture channel on Compose's Skia canvas, without per-triangle Java2D clips. */
internal class SkiaRigPainter(atlas: PackedAtlas) : AutoCloseable {
    private val images = atlas.pages.map { page ->
        page.image.toSkiaImage()
    }
    private val shaders = images.map {
        it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null)
    }

    fun paint(
        canvas: Canvas,
        model: RigPreviewModel,
        geometry: DeformedGeometry,
        viewport: CanvasViewport,
        alpha: Float = 1f,
        visibleLayerIds: Set<String>? = null,
        drawOrderOverrides: Map<String, Float> = emptyMap(),
        dimUnselected: Boolean = false,
        highlightedLayerIds: Set<String>? = null,
        dimmedAlphaMultiplier: Float = 0.22f,
        tintLayerIds: Set<String>? = null,
        tintColor: Int = 0,
        /** Defaults to the GpuRenderer's own wash, so the two paths tint by the same amount. */
        tintAlpha: Float = SELECTION_TINT_STRENGTH,
    ) {
        val drawables = model.rig.puppet.drawables.filter { it.mesh != null && it.id in geometry.worldPositions }
            .sortedBy { RigCanvasSupport.displayOrder(model, it, geometry, drawOrderOverrides) }
        val byId = model.rig.puppet.drawables.associateBy { it.id }
        val masks = mutableMapOf<List<DrawableId>, Path?>()
        Paint().use { paint ->
            try {
                for (drawable in drawables) {
                    val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
                    if (visibleLayerIds != null && layerId != null && layerId !in visibleLayerIds) continue
                    if (visibleLayerIds != null && layerId == null && drawable.id.raw !in visibleLayerIds && !drawable.isVisible) continue
                    val highlighted = highlightedLayerIds == null || (layerId != null && layerId in highlightedLayerIds) || drawable.id.raw in highlightedLayerIds
                    val dim = if (dimUnselected && !highlighted) dimmedAlphaMultiplier else 1f
                    val opacity = ((geometry.opacity[drawable.id] ?: drawable.opacity) * alpha * dim).coerceIn(0f, 1f)
                    if (opacity <= 0.001f) continue
                    val mesh = drawable.mesh ?: continue
                    val world = geometry.worldPositions[drawable.id] ?: continue
                    val page = model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
                    val image = images.getOrNull(page) ?: continue
                    val positions = FloatArray(mesh.indices.size * 2)
                    val uvs = FloatArray(positions.size)
                    // Expanded vertices avoid the unsigned-short index limit for large authored meshes.
                    mesh.indices.forEachIndexed { index, vertex ->
                        positions[index * 2] = viewport.x(world[vertex * 2]).toFloat()
                        positions[index * 2 + 1] = viewport.yFromWorld(world[vertex * 2 + 1]).toFloat()
                        uvs[index * 2] = mesh.uvs[vertex * 2] * image.width
                        uvs[index * 2 + 1] = mesh.uvs[vertex * 2 + 1] * image.height
                    }
                    val mask = if (drawable.maskedBy.isNotEmpty() && !drawable.invertMask) {
                        masks.getOrPut(drawable.maskedBy) {
                            PathBuilder().use { path ->
                                var triangles = 0
                                for (id in drawable.maskedBy) {
                                    val source = byId[id] ?: continue
                                    if (!source.isVisible) continue
                                    val maskMesh = source.mesh ?: continue
                                    val points = geometry.worldPositions[id] ?: continue
                                    for (i in maskMesh.indices.indices step 3) {
                                        val a = maskMesh.indices[i] * 2
                                        var b = maskMesh.indices[i + 1] * 2
                                        var c = maskMesh.indices[i + 2] * 2
                                        val cross = (points[b] - points[a]) * (points[c + 1] - points[a + 1]) -
                                            (points[b + 1] - points[a + 1]) * (points[c] - points[a])
                                        if (cross == 0f) continue
                                        if (cross < 0f) { val swap = b; b = c; c = swap }
                                        path.moveTo(viewport.x(points[a]).toFloat(), viewport.yFromWorld(points[a + 1]).toFloat())
                                        path.lineTo(viewport.x(points[b]).toFloat(), viewport.yFromWorld(points[b + 1]).toFloat())
                                        path.lineTo(viewport.x(points[c]).toFloat(), viewport.yFromWorld(points[c + 1]).toFloat())
                                        path.closePath()
                                        triangles++
                                    }
                                }
                                if (triangles == 0) null else path.detach()
                            }
                        }
                    } else null
                    val saved = canvas.save()
                    try {
                        mask?.let { canvas.clipPath(it, false) }
                        paint.shader = shaders[page]
                        paint.setAlphaf(opacity)
                        canvas.drawVertices(VertexMode.TRIANGLES, positions, null, uvs, null, BlendMode.MODULATE, paint)
                        // Hover annotation: wash the very triangles just drawn with the component colour
                        // instead of boxing them. Re-drawing the mesh keeps the tint on the artwork's own
                        // silhouette — a part lights up rather than growing a rectangle — and because it
                        // runs inside the same clip, a masked part is tinted only where it actually shows.
                        if (tintColor != 0 && tintLayerIds != null &&
                            ((layerId != null && layerId in tintLayerIds) || drawable.id.raw in tintLayerIds)
                        ) {
                            paint.shader = null
                            paint.color = tintColor
                            paint.setAlphaf(tintAlpha)
                            canvas.drawVertices(VertexMode.TRIANGLES, positions, null, null, null, BlendMode.SRC_OVER, paint)
                        }
                    } finally { canvas.restoreToCount(saved) }
                }
            } finally { masks.values.forEach { it?.close() } }
        }
    }

    override fun close() {
        shaders.forEach { it.close() }
        images.forEach { it.close() }
    }
}

/**
 * The camera translation a cached pass was drawn at, and everything else it depends on. While the user pans,
 * a pass whose [stableKey] still matches is drawn shifted instead of being drawn again.
 */
internal class PanShift(val stableKey: List<Any?>, val offsetX: Double, val offsetY: Double, val panning: Boolean)

/**
 * Keeps the unchanged texture pass for this one viewport.
 *
 * A new key is recorded as Skia draw commands and replayed while it keeps changing (a drag, a scrub).
 * Replaying is not free: every textured mesh is sampled from the atlas again and every masked part clips
 * against a path of all its mask's triangles, on each frame the canvas draws, which includes every hover
 * and overlay change. So once the same key is drawn twice it is rasterized, and later frames draw one image.
 */
internal class CachedSkiaPicture : AutoCloseable {
    private var key: List<Any?>? = null
    private var picture: Picture? = null
    private var raster: Image? = null
    private var shift: PanShift? = null

    fun draw(
        canvas: Canvas, key: List<Any?>, width: Int, height: Int, pan: PanShift? = null, record: (Canvas) -> Unit,
    ) {
        val cached = shift
        if (pan != null && pan.panning && cached != null && (picture != null || raster != null) && cached.stableKey == pan.stableKey &&
            (cached.offsetX != pan.offsetX || cached.offsetY != pan.offsetY)) {
            val saved = canvas.save()
            try {
                canvas.translate((pan.offsetX - cached.offsetX).toFloat(), (pan.offsetY - cached.offsetY).toFloat())
                drawCached(canvas)
            } finally { canvas.restoreToCount(saved) }
            return
        }
        if (picture == null || this.key != key) {
            val next = PictureRecorder().use { recorder ->
                record(recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat())))
                recorder.finishRecordingAsPicture()
            }
            picture?.close()
            raster?.close()
            raster = null
            picture = next
            this.key = key
            shift = pan
        } else if (raster == null && width > 0 && height > 0) {
            raster = Surface.makeRasterN32Premul(width, height).use { surface ->
                picture?.let { surface.canvas.drawPicture(it) }
                surface.makeImageSnapshot()
            }
        }
        drawCached(canvas)
    }

    private fun drawCached(canvas: Canvas) {
        val image = raster
        if (image != null) canvas.drawImage(image, 0f, 0f) else picture?.let { canvas.drawPicture(it) }
    }

    override fun close() {
        picture?.close()
        picture = null
        raster?.close()
        raster = null
        key = null
        shift = null
    }
}
