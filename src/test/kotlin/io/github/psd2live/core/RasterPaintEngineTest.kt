package io.github.psd2live.core

import java.awt.image.BufferedImage
import kotlin.test.*

class RasterPaintEngineTest {
    private fun stroke(segments: List<Float>): IntArray {
        val image = BufferedImage(32, 12, BufferedImage.TYPE_INT_ARGB)
        val tip = RasterPaintEngine.Tip(3f, 0.5f)
        val stroke = RasterPaintEngine.Stroke(image.width, image.height)
        segments.zipWithNext().forEach { (from, to) -> stroke.addSegment(from, 6f, to, 6f, tip) }
        stroke.bounds?.let { stroke.land(image, 0xffff0000.toInt(), 0.5f, false, it) }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    @Test fun strokeCoverageDoesNotDependOnPointerSubdivisionOrRepeatedTraversal() {
        assertContentEquals(stroke(listOf(4f, 24f)), stroke((4..24).map(Int::toFloat)))
        assertContentEquals(stroke(listOf(4f, 24f)), stroke(listOf(4f, 24f, 4f, 24f)))
    }

    @Test fun floodFillRespectsClipAndKeepsOutsidePixelsUnchanged() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val clipped = java.awt.Rectangle(2, 2, 3, 3)
        val changed = RasterPaintEngine.floodFill(image, 3, 3, 0xff123456.toInt(), 0, clipped)
        assertEquals(clipped, changed)
        for (y in 0..7) for (x in 0..7) {
            assertEquals(if (clipped.contains(x, y)) 0xff123456.toInt() else 0, image.getRGB(x, y))
        }
    }
}
