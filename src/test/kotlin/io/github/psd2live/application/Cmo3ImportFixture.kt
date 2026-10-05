package io.github.psd2live.application

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path

internal fun writeCmo3Fixture(path: Path, vararg names: String, color: Int = 0xffbb4070.toInt()): Path {
    val drawables = names.mapIndexed { index, name -> Drawable(DrawableId(name), name, null, BlendMode.Normal, emptyList(),
        DrawableMesh(floatArrayOf(2f + index, 2f, 12f + index, 2f, 2f + index, 12f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)), null) }
    val puppet = PuppetModel(listOf(Parameter(ParameterId("ParamCustom"), "Custom", -1f, 1f, 0f)),
        emptyList(), emptyList(), drawables, drawables.map { OrgChild.Drawable(it.id) }, null, canvasWidth = 32f, canvasHeight = 32f)
    val rgba = ByteArray(8 * 8 * 4)
    for (i in rgba.indices step 4) {
        rgba[i] = (color ushr 16).toByte(); rgba[i + 1] = (color ushr 8).toByte()
        rgba[i + 2] = color.toByte(); rgba[i + 3] = (color ushr 24).toByte()
    }
    val converted = Cmo3Conversion.freshCmo3(puppet, listOf(Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(8, 8, rgba)), 8, 8)),
        drawables.associate { it.id.raw to 0 }, "Authored fixture", 0L, 0x51)
    Files.write(path, Cmo3.write(converted.model))
    return path
}
