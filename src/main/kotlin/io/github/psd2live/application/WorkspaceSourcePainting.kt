package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceDocument

import io.github.psd2live.core.RasterPaintEngine
import io.github.psd2live.core.RasterPaintShape
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import java.awt.image.BufferedImage

/** Layer-local eyedropper in source canvas coordinates; sampling never creates an edit. */
internal fun WorkspaceDocument.sampleSourceColor(layerId: String, x: Int, y: Int): List<Int> {
    require(x in 0 until source.widthPx && y in 0 until source.heightPx) { "Sample point is outside the canvas" }
    val layer = source.layers.singleOrNull { it.id.raw == layerId && layerId !in deletedLayerIds }
        ?: throw IllegalArgumentException("Source layer not found: $layerId")
    val bounds = layer.bounds
    if (x < bounds.left || y < bounds.top || x >= bounds.left + bounds.width || y >= bounds.top + bounds.height) return listOf(0, 0, 0, 0)
    val localX = ((x - bounds.left).toLong() * layer.raster.width / bounds.width).toInt()
    val localY = ((y - bounds.top).toLong() * layer.raster.height / bounds.height).toInt()
    val start = (localY * layer.raster.width + localX) * 4
    return (0..3).map { layer.raster.rgba[start + it].toInt() and 255 }
}

/** Seeds a canvas-sized paint image from the layer's saved raster and placement. */
internal fun WorkspaceDocument.sourceLayerImage(id: String, checkpoint: () -> Unit = {}): BufferedImage {
    checkpoint()
    val layer = source.layers.singleOrNull { it.id.raw == id && id !in deletedLayerIds }
        ?: throw IllegalArgumentException("Source layer not found: $id")
    require(source.widthPx.toLong() * source.heightPx <= 16_777_216) { "Painting requires a canvas of at most 16 megapixels" }
    val width = source.widthPx
    val height = source.heightPx
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val raster = BufferedImage(layer.raster.width, layer.raster.height, BufferedImage.TYPE_INT_ARGB)
    val original = layer.raster.rgba
    for (i in 0 until layer.raster.width * layer.raster.height) {
        if (i % layer.raster.width == 0) checkpoint()
        val at = i * 4
        raster.setRGB(i % layer.raster.width, i / layer.raster.width,
            ((original[at + 3].toInt() and 255) shl 24) or
                ((original[at].toInt() and 255) shl 16) or
                ((original[at + 1].toInt() and 255) shl 8) or
                (original[at + 2].toInt() and 255))
    }
    val graphics = image.createGraphics()
    try {
        graphics.composite = java.awt.AlphaComposite.Src
        graphics.drawImage(raster, layer.bounds.left, layer.bounds.top,
            layer.bounds.width, layer.bounds.height, null)
    } finally { graphics.dispose() }
    return image
}

internal fun WorkspaceDocument.paintSourceImage(arguments: JsonObject,
    work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): BufferedImage {
    work.progress(0f, "Preparing source pixels")
    val image = sourceLayerImage(arguments.getValue("layer_id").jsonPrimitive.content, work::checkpoint)
    paintRasterGesture(image, arguments, work)
    return image
}

/** A private session and a durable single gesture use the same raster processor. */
internal fun paintRasterGesture(image: BufferedImage, arguments: JsonObject,
    work: WorkspaceRasterWork = WorkspaceRasterWork.Direct, before: (java.awt.Rectangle) -> Unit = {}) {
    val width = image.width; val height = image.height
    work.progress(0.05f, "Rasterizing paint gesture")
    val mode = arguments.getValue("mode").jsonPrimitive.content
    val rgba = arguments["color"]?.jsonArray?.map { it.jsonPrimitive.int } ?: listOf(0, 0, 0, 255)
    require(rgba.size == 4 && rgba.all { it in 0..255 }) { "color must be four RGBA bytes" }
    val color = (rgba[3] shl 24) or (rgba[0] shl 16) or (rgba[1] shl 8) or rgba[2]
    val opacity = arguments["opacity"]?.jsonPrimitive?.float ?: 1f
    require(opacity.isFinite() && opacity in 0f..1f) { "opacity must be 0..1" }
    fun point(value: JsonElement): Pair<Float, Float> {
        val numbers = value.jsonArray.map { it.jsonPrimitive.float }
        require(numbers.size == 2 && numbers.all(Float::isFinite)) { "point must be [x,y]" }
        return numbers[0] to numbers[1]
    }
    when (mode) {
        "clear" -> { before(java.awt.Rectangle(0, 0, width, height)); RasterPaintEngine.clear(image) }
        "brush", "pencil", "eraser" -> {
            val points = arguments.getValue("points").jsonArray.map(::point)
            require(points.size in 1..512) { "Use 1..512 stroke points" }
            val radius = arguments["radius"]?.jsonPrimitive?.float ?: 8f
            val hardness = arguments["hardness"]?.jsonPrimitive?.float ?: 1f
            require(radius.isFinite() && radius in 0.5f..512f && hardness.isFinite() && hardness in 0f..1f)
            val stroke = RasterPaintEngine.Stroke(width, height)
            val tip = RasterPaintEngine.Tip(radius, if (mode == "pencil") 1f else hardness, antialias = mode != "pencil")
            if (points.size == 1) stroke.addSegment(points[0].first, points[0].second,
                points[0].first, points[0].second, tip, work::checkpoint)
            else points.zipWithNext().forEach { (a, b) -> stroke.addSegment(a.first, a.second, b.first, b.second, tip, work::checkpoint) }
            stroke.bounds?.let { before(it); stroke.land(image, color, opacity, mode == "eraser", it, work::checkpoint) }
        }
        "bucket" -> {
            val (x, y) = point(arguments.getValue("point"))
            val tolerance = arguments["tolerance"]?.jsonPrimitive?.int ?: 0
            require(tolerance in 0..255)
            RasterPaintEngine.floodFill(image, x.toInt(), y.toInt(), color, tolerance, before = before, checkpoint = work::checkpoint)
        }
        "shape" -> {
            val (x0, y0) = point(arguments.getValue("from"))
            val (x1, y1) = point(arguments.getValue("to"))
            val shape = RasterPaintShape.valueOf(arguments.getValue("shape").jsonPrimitive.content.uppercase())
            val strokeWidth = arguments["stroke_width"]?.jsonPrimitive?.float ?: 1f
            require(strokeWidth.isFinite() && strokeWidth in 1f..512f)
            before(RasterPaintEngine.shapeArea(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), strokeWidth,
                java.awt.Rectangle(0, 0, width, height)))
            RasterPaintEngine.drawShape(image, x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(),
                shape, color, opacity, strokeWidth, arguments["filled"]?.jsonPrimitive?.boolean ?: false)
        }
        else -> throw IllegalArgumentException("Unknown paint mode: $mode")
    }
    work.progress(0.2f, "Rasterized paint gesture")
}

/** Pure raster-only fixture edit; application commits additionally preserve and migrate rig inputs. */
internal fun WorkspaceDocument.paintSource(arguments: JsonObject): WorkspaceDocument {
    val id = arguments.getValue("layer_id").jsonPrimitive.content
    val layer = source.layers.single { it.id.raw == id }
    val width = source.widthPx; val height = source.heightPx
    val image = paintSourceImage(arguments)
    var left = width; var top = height; var right = 0; var bottom = 0
    for (y in 0 until height) for (x in 0 until width) if (image.getRGB(x, y) ushr 24 != 0) {
        left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
    }
    if (right <= left || bottom <= top) {
        left = 0; top = 0; right = 1; bottom = 1
    }
    val output = ByteArray((right - left) * (bottom - top) * 4)
    for (y in top until bottom) for (x in left until right) {
        val pixel = image.getRGB(x, y)
        val at = ((y - top) * (right - left) + x - left) * 4
        output[at] = (pixel ushr 16).toByte(); output[at + 1] = (pixel ushr 8).toByte()
        output[at + 2] = pixel.toByte(); output[at + 3] = (pixel ushr 24).toByte()
    }
    val updated = (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
        bounds = LayerBounds(left, top, right - left, bottom - top),
        raster = LayerRaster(right - left, bottom - top, output),
        sourceAssetId = null, sourceSpatialReferenceId = null, derived = true,
    )
    return copy(source = WorkspaceSourceArt(width, height,
        source.layers.map { if (it.id.raw == id) updated else it }, source.groups))
}
