package io.github.psd2live.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.psd2live.core.RasterPaintEngine
import io.github.psd2live.core.RasterPaintShape
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Compose color and shortcut adapters; all raster behavior lives in the shared engine. */
internal object LayerPaintEngine {
    class Tip(radius: Float, hardness: Float = 1f, antialias: Boolean = true) :
        RasterPaintEngine.Tip(radius, hardness, antialias)

    class Stroke(width: Int, height: Int) : RasterPaintEngine.Stroke(width, height) {
        fun land(target: BufferedImage, color: Color, opacity: Float, erase: Boolean, region: Rectangle) =
            super.land(target, color.toArgb(), opacity, erase, region, {})
    }

    fun floodFill(image: BufferedImage, startX: Int, startY: Int, fillColor: Color, tolerance: Int,
                  clipRect: Rectangle? = null, before: (Rectangle) -> Unit = {}): Rectangle? =
        RasterPaintEngine.floodFill(image, startX, startY, fillColor.toArgb(), tolerance, clipRect, before)

    fun shapeArea(x0: Int, y0: Int, x1: Int, y1: Int, strokeWidth: Float, clipRect: Rectangle? = null): Rectangle =
        RasterPaintEngine.shapeArea(x0, y0, x1, y1, strokeWidth, clipRect)

    fun drawShape(image: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, shape: PaintShape,
                  color: Color, opacity: Float, strokeWidth: Float, filled: Boolean, clipRect: Rectangle? = null): Rectangle? =
        RasterPaintEngine.drawShape(image, x0, y0, x1, y1, RasterPaintShape.valueOf(shape.name), color.toArgb(),
            opacity, strokeWidth, filled, clipRect)

    fun clear(image: BufferedImage) = RasterPaintEngine.clear(image)
}
