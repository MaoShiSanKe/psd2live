package io.github.psd2live.ui

import io.github.psd2live.ui.utils.toSkiaImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageConversionTest {
	@Test fun bulkConversionKeepsEveryPixel() {
		for (type in listOf(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB,
			BufferedImage.TYPE_4BYTE_ABGR)) {
			val source = BufferedImage(7, 5, type)
			for (y in 0 until 5) for (x in 0 until 7) source.setRGB(x, y, argb(x, y))
			assertSame(source, source.toSkiaImage().let(::pixels), "type $type")
			// A view into a larger raster has its own stride and origin.
			val view = source.getSubimage(2, 1, 4, 3)
			assertSame(view, view.toSkiaImage().let(::pixels), "subimage of type $type")
		}
	}

	@Test fun stableArtworkIsRasterizedAndPanReusesIt() {
		CachedSkiaPicture().use { cache ->
			Surface.makeRasterN32Premul(16, 16).use { surface ->
				var records = 0
				fun draw(key: String, pan: PanShift? = null) = cache.draw(surface.canvas, listOf(key), 16, 16, pan) { canvas ->
					records++
					Paint().use { canvas.drawRect(Rect.makeXYWH(0f, 0f, 4f, 4f), it) }
				}
				draw("a", PanShift(listOf("a"), 0.0, 0.0, panning = false))
				draw("a")
				draw("a")
				assertEquals(1, records)
				// Panning by (8, 8) draws the cached pass shifted instead of recording the moved camera.
				surface.canvas.clear(0)
				draw("b", PanShift(listOf("a"), 8.0, 8.0, panning = true))
				assertEquals(1, records)
				val shifted = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(16, 16)) }
				surface.readPixels(shifted, 0, 0)
				assertEquals(0, shifted.getColor(2, 2) ushr 24, "the pass left its old place")
				assertEquals(255, shifted.getColor(10, 10) ushr 24, "the pass moved with the pan")
				// The pan's release draws at the final camera.
				draw("b", PanShift(listOf("a"), 8.0, 8.0, panning = false))
				assertEquals(2, records)
			}
		}
	}

	private fun argb(x: Int, y: Int): Int = ((x * 37 + y * 11) % 256 shl 24) or (x * 40 shl 16) or (y * 50 shl 8) or (x * y * 7 % 256)

	private fun pixels(image: org.jetbrains.skia.Image): Bitmap {
		val bitmap = Bitmap().apply { allocPixels(ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)) }
		image.readPixels(bitmap, 0, 0)
		return bitmap
	}

	private fun assertSame(expected: BufferedImage, actual: Bitmap, label: String) {
		assertEquals(expected.width, actual.width, label)
		assertEquals(expected.height, actual.height, label)
		for (y in 0 until expected.height) for (x in 0 until expected.width) {
			val want = expected.getRGB(x, y)
			val got = actual.getColor(x, y)
			// Premultiplied sources lose precision at low alpha; compare channels within rounding.
			for (shift in intArrayOf(24, 16, 8, 0)) {
				val a = want ushr shift and 0xff
				val b = got ushr shift and 0xff
				val tolerance = if (shift == 24 || (want ushr 24) == 255) 0 else 255 / maxOf(1, want ushr 24) + 1
				kotlin.test.assertTrue(kotlin.math.abs(a - b) <= tolerance, "$label ($x, $y): ${Integer.toHexString(want)} != ${Integer.toHexString(got)}")
			}
		}
	}
}
