package io.github.psd2live.project

import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal data class WorkspacePngAsset(
	val public: WorkspaceImportedPngAsset,
	val rgba: ByteArray,
    val originalPng: ByteArray? = null,
)

internal fun WorkspacePngAsset.preview(): WorkspaceAssetPreview {
    val png = java.io.ByteArrayOutputStream().also { ImageIO.write(assetImage(public.pixelWidth, public.pixelHeight, rgba), "png", it) }.toByteArray()
    var transparent = 0
    var translucent = 0
    for (i in 3 until rgba.size step 4) {
        val alpha = rgba[i].toInt() and 255
        if (alpha == 0) transparent++ else if (alpha < 255) translucent++
    }
    return WorkspaceAssetPreview(public, png, transparent, translucent, originalPng)
}

private fun assetImage(width: Int, height: Int, rgba: ByteArray): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = IntArray(width * height) { i ->
        val j = i * 4
        ((rgba[j + 3].toInt() and 255) shl 24) or ((rgba[j].toInt() and 255) shl 16) or
            ((rgba[j + 1].toInt() and 255) shl 8) or (rgba[j + 2].toInt() and 255)
    }
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image
}
