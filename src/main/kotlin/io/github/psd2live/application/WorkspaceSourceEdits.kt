package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.awt.geom.Path2D
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal fun sourceArtwork(arguments: JsonObject): Pair<WorkspaceSourceArt, Map<String, LayerClassificationOverride>> {
    val width = arguments.getValue("width").jsonPrimitive.int; val height = arguments.getValue("height").jsonPrimitive.int
    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "Canvas exceeds 16 megapixels" }
    val files = arguments.getValue("layers").jsonArray
    require(files.size in 1..32)
    val overrides = mutableMapOf<String, LayerClassificationOverride>()
    var pixels = 0L
    val layers = files.mapIndexed { index, entry ->
        val a = entry.jsonObject
        val file = Path.of(a.text("path"))
        require(file.isAbsolute && Files.isRegularFile(file) && Files.size(file) in 8..67_108_864) { "Invalid local image path or file budget" }
        val image = LayerImport.decodeRasterFile(file.toFile())
        pixels += image.width.toLong() * image.height
        require(pixels <= 33_554_432) { "Artwork exceeds total raster budget" }
        val rgba = image.rgba
        val id = "artwork:${UUID.randomUUID()}"
        a["role"]?.jsonPrimitive?.content?.let { role -> overrides[id] = LayerClassificationOverride(type = LayerType.PRESET,
            tag = enumValueOf<SemanticTag>(role.uppercase()), side = enumValueOf<Side>((a["side"]?.jsonPrimitive?.content ?: "none").uppercase())) }
        WorkspaceSourceLayer(LayerId(id), a.text("name"), "", SourceLayerKind.Raster, true, files.lastIndex - index,
            LayerBounds(a["x"]?.jsonPrimitive?.int ?: 0, a["y"]?.jsonPrimitive?.int ?: 0, image.width, image.height),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(image.width, image.height, rgba), null, null, false)
    }
    return WorkspaceSourceArt(width, height, layers, emptyList()) to overrides
}

/** A binary source partition preserves original RGBA exactly; it does not invent hidden artwork. */
internal fun partitionSourcePolygon(layer: SourceLayer, arguments: JsonObject, work: WorkspaceRasterWork): List<WorkspaceSourceLayer> {
    require(!layer.clipped && layer.blend == LayerBlend.Normal && layer.channelMask == ChannelMask.ALL) { "Split requires a normal, unmasked source layer" }
    val polygon = arguments.getValue("polygon").jsonArray.map { point -> point.jsonArray.map { it.jsonPrimitive.double } }
    require(polygon.size in 3..32 && polygon.all { it.size == 2 && it.all(Double::isFinite) })
    val names = arguments.getValue("names").jsonArray.map { it.jsonPrimitive.content }
    require(names.size == 2 && names.all { it.isNotBlank() })
    val shape = Path2D.Double()
    shape.moveTo(polygon.first()[0], polygon.first()[1]); polygon.drop(1).forEach { shape.lineTo(it[0], it[1]) }; shape.closePath()
    val sourceRaster = layer.raster
    val selected = sourceRaster.rgba.copyOf(); val remainder = sourceRaster.rgba.copyOf()
    var selectedPixels = 0; var remainingPixels = 0
    for (y in 0 until sourceRaster.height) {
        work.progress(0.1f + 0.5f * y / sourceRaster.height, "Partitioning source pixels")
        for (x in 0 until sourceRaster.width) {
            val inside = shape.contains(layer.bounds.left + (x + 0.5) * layer.bounds.width / sourceRaster.width,
                layer.bounds.top + (y + 0.5) * layer.bounds.height / sourceRaster.height)
            val alpha = (y * sourceRaster.width + x) * 4 + 3
            if (inside) { remainder[alpha] = 0; if (selected[alpha].toInt() != 0) selectedPixels++ }
            else { selected[alpha] = 0; if (remainder[alpha].toInt() != 0) remainingPixels++ }
        }
    }
    require(selectedPixels > 0 && remainingPixels > 0) { "Polygon must separate two nonempty painted regions" }
    val pieceIds = arguments["piece_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
    val pieces = listOf(selected, remainder).mapIndexed { index, rgba ->
        (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
            id = LayerId(pieceIds?.get(index) ?: "split:${UUID.randomUUID()}"), name = names[index].trim(),
            raster = LayerRaster(sourceRaster.width, sourceRaster.height, rgba), sourceAssetId = null, sourceSpatialReferenceId = null, derived = true)
    }
    return pieces
}
