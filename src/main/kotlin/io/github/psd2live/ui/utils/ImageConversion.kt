package io.github.psd2live.ui.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * This image as a Skia bitmap, copied in bulk.
 *
 * Compose's own `BufferedImage.toComposeImageBitmap()` reads every pixel through `getRGB(x, y)`, which
 * costs tens of milliseconds for one canvas-sized image and most of a second for a 4096 atlas page, all
 * on the UI thread. Integer rasters are already BGRA in little-endian memory, so they go straight across;
 * any other layout is copied once through the bulk `getRGB`.
 */
internal fun BufferedImage.toSkiaBitmap(): Bitmap {
	val w = width
	val h = height
	val whole = wholeRaster()
	val (argb, alphaType) = when {
		whole != null && type == BufferedImage.TYPE_INT_ARGB -> whole to ColorAlphaType.UNPREMUL
		whole != null && type == BufferedImage.TYPE_INT_ARGB_PRE -> whole to ColorAlphaType.PREMUL
		whole != null && type == BufferedImage.TYPE_INT_RGB -> whole to ColorAlphaType.OPAQUE
		// getRGB always answers unpremultiplied ARGB, whatever the raster holds.
		else -> getRGB(0, 0, w, h, null, 0, w) to ColorAlphaType.UNPREMUL
	}
	val bytes = ByteArray(w * h * 4)
	ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(argb, 0, w * h)
	if (alphaType == ColorAlphaType.OPAQUE) for (i in 3 until bytes.size step 4) bytes[i] = -1
	val info = ImageInfo(w, h, ColorType.BGRA_8888, alphaType)
	return Bitmap().apply {
		allocPixels(info)
		installPixels(bytes)
		setImmutable()
	}
}

/** [toSkiaBitmap] as an image, sharing its pixels. */
internal fun BufferedImage.toSkiaImage(): Image = Image.makeFromBitmap(toSkiaBitmap())

/**
 * [toSkiaBitmap] for Compose drawing. Wrapped, not converted: `Image.toComposeImageBitmap()` would read
 * every pixel back out of the image once more.
 */
internal fun BufferedImage.toImageBitmapFast(): ImageBitmap = toSkiaBitmap().asComposeImageBitmap()

/** The pixels of an integer raster that is exactly this image, or null for any other layout (a sub-image view). */
private fun BufferedImage.wholeRaster(): IntArray? {
	val raster = raster
	val buffer = raster.dataBuffer as? DataBufferInt ?: return null
	val sample = raster.sampleModel as? java.awt.image.SinglePixelPackedSampleModel ?: return null
	val data = buffer.data
	return data.takeIf { sample.scanlineStride == width && raster.sampleModelTranslateX == 0 &&
		raster.sampleModelTranslateY == 0 && buffer.offset == 0 && data.size >= width * height }
}
