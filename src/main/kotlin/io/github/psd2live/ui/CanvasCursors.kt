package io.github.psd2live.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Custom canvas cursors AWT has no stock shape for. Drawn twice - a white halo, then a dark core - so
 * they stay legible over light, dark and busy artwork alike.
 */
internal object CanvasCursors {
    private val DARK_CORE = Color(0x1B, 0x1F, 0x27)
    private val WHITE_HALO = Color.WHITE

    /** Photoshop's pipette: the hotspot is the tip, so the pixel read is the one the tip touches. */
    val eyedropper: Cursor by lazy {
        cursor("psd2live-eyedropper", 3, 28) { g ->
            // A tube from the tip (lower left) up to the bulb (upper right), in a canonical 32x32 space.
            val tube = Path2D.Float().apply {
                moveTo(3.0, 28.0)
                lineTo(5.0, 22.0)
                lineTo(17.0, 10.0)
                lineTo(21.0, 14.0)
                lineTo(9.0, 26.0)
                closePath()
            }
            val collar = Path2D.Float().apply {
                moveTo(14.5, 9.5)
                lineTo(21.5, 16.5)
            }
            val bulb = Path2D.Float().apply {
                moveTo(18.0, 8.5)
                lineTo(23.5, 3.0)
                quadTo(27.5, 1.5, 29.0, 3.0)
                quadTo(30.5, 4.5, 29.0, 8.5)
                lineTo(23.5, 14.0)
                closePath()
            }

            g.color = WHITE_HALO
            g.stroke = BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(tube)
            g.draw(collar)
            g.draw(bulb)
            g.fill(bulb)

            g.color = DARK_CORE
            g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(tube)
            g.fill(bulb)
            g.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(collar)
        }
    }

    private fun cursor(name: String, hotX: Int, hotY: Int, paint: (Graphics2D) -> Unit): Cursor {
        val size = Toolkit.getDefaultToolkit().getBestCursorSize(32, 32).let { dim ->
            if (dim.width <= 0 || dim.height <= 0) 32 else maxOf(dim.width, dim.height)
        }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        if (size != 32) g.scale(size / 32.0, size / 32.0)
        paint(g)
        g.dispose()
        return Toolkit.getDefaultToolkit().createCustomCursor(image, Point(hotX * size / 32, hotY * size / 32), name)
    }
}
