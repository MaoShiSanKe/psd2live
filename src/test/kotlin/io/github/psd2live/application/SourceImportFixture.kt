package io.github.psd2live.application

import kotlinx.serialization.json.*
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdWriter
import org.umamo.format.raster.RasterImage
import java.nio.file.Files
import java.nio.file.Path

internal fun sourceImportArguments(png: Path, discard: Boolean = false): JsonObject = buildJsonObject {
    put("width", 32); put("height", 32)
    if (discard) put("discard_unsaved", true)
    putJsonArray("layers") { add(buildJsonObject {
        put("path", png.toString()); put("name", "Artwork"); put("role", "objects"); put("x", 4); put("y", 3)
    }) }
}

internal fun writeSourceImportFixture(directory: Path): Pair<Path, Path> {
    val rgba = ByteArray(16 * 16 * 4)
    for (y in 0..15) for (x in 0..15) {
        val i = (y * 16 + x) * 4
        rgba[i] = (40 + x * 8).toByte(); rgba[i + 1] = (90 + y * 4).toByte(); rgba[i + 2] = 120
        rgba[i + 3] = if (x + y < 22) -1 else 0
    }
    val png = directory.resolve("art.png")
    Files.write(png, PngCodec.write(RasterImage(16, 16, rgba)))
    val (source, _) = sourceArtwork(sourceImportArguments(png))
    val psd = directory.resolve("art.psd")
    Files.write(psd, PsdWriter.write(32, 32, source.layers, source.groups))
    return png to psd
}
