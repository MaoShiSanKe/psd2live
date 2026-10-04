package io.github.psd2live.core

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.DrawableMesh
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage

/** Rebuild geometry from its saved input while packing the currently painted pixels. */
internal object RigGenerationSource {
    data class Analyses(val geometry: PipelineAnalysis, val textures: PipelineAnalysis)

    fun analyze(source: SourceArt, config: PipelineConfig): PipelineAnalysis {
        val input = config.generationSource?.let { geometrySource(source, it, config.rigEdits) } ?: source
        val analysis = CharacterAnalyzer.analyze(input, RigLayerDeletion.generationConfig(config))
        return if (input === source) analysis else analysis.copy(source = source, preview = PreviewRenderer.composite(source))
    }

    fun prepare(input: PipelineAnalysis, config: PipelineConfig, textureConfig: PipelineConfig = config): Analyses {
        val reference = config.generationSource ?: return MouthLipLayers.prepare(input, config).let { Analyses(it, it) }
        val geometry = MouthLipLayers.prepare(CharacterAnalyzer.analyze(geometrySource(input.source, reference, config.rigEdits), config), config)
        val current = input.source.layers.associateBy { it.id.raw }
        val creationCoverage = config.rigEdits.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP }
            .associate { command ->
                command.getValue("layer_id").jsonPrimitive.content to RasterMeshCreation.sourceBounds(command)
            }
        val meshSources = (config.meshSource ?: reference).layers.associateBy { it.id.raw }
        val lipInputs = geometry.copy(layers = geometry.layers.filter { it.source !is MouthLipLayer }.map { layer ->
            meshSources[layer.source.id.raw]?.let { source -> CharacterAnalyzer.classify(source, textureConfig) } ?: layer
        })
        val generated = (if (RigGenerationBaseline.present(config.rigEdits))
            RigGenerationTextures.layers(config.meshSource ?: input.source, textureConfig) else
            MouthLipLayers.prepare(lipInputs, textureConfig).layers.filter { it.source is MouthLipLayer })
            .associateBy { it.source.id.raw }
        val textures = geometry.copy(source = input.source, preview = input.preview, layers = geometry.layers.map { layer ->
            // Keeping a generated ribbon's mesh also keeps its generated raster and contour.
            val lip = layer.source as? MouthLipLayer
            if (lip != null) {
                val owner = current[lip.ownerId] ?: lip
                val visible = (0 until owner.raster.width * owner.raster.height).any { owner.raster.rgba[it * 4 + 3] != 0.toByte() }
                val replacement = if (visible) generated[lip.id.raw]?.source else null
                val source = replacement ?: object : SourceLayer by lip {
                    override val raster = LayerRaster(lip.raster.width, lip.raster.height, ByteArray(lip.raster.rgba.size))
                }
                val covered = padded(source, lip.bounds)
                layer.copy(source = MouthLipLayer(lip.ownerId, lip.side, owner, covered.raster, covered.bounds))
            } else {
                val artwork = current[layer.source.id.raw] ?: current.entries
                    .filter { layer.source.id.raw.startsWith("${it.key}:") }.maxByOrNull { it.key.length }?.value
                    ?.let { parent -> object : SourceLayer by parent {
                        override val id = layer.source.id
                        override val name = layer.source.name
                    } } ?: error("Generation layer has no current artwork")
                val classified = CharacterAnalyzer.classify(artwork, textureConfig)
                val covered = padded(artwork, layer.source.bounds).let { source -> creationCoverage[artwork.id.raw]?.let { padded(source, it) } ?: source }
                layer.copy(source = covered, semantic = classified.semantic, bounds = classified.bounds,
                    centroidX = classified.centroidX, centroidY = classified.centroidY,
                    opaquePixels = classified.opaquePixels.coerceAtLeast(1))
            }
        } + generated.values.filter { generatedLayer -> geometry.layers.none { it.source.id == generatedLayer.source.id } }.map { layer ->
            val lip = layer.source as MouthLipLayer
            val owner = current.getValue(lip.ownerId)
            val visible = (0 until owner.raster.width * owner.raster.height).any { owner.raster.rgba[it * 4 + 3] != 0.toByte() }
            val source = if (visible) lip else MouthLipLayer(lip.ownerId, lip.side, owner,
                LayerRaster(lip.raster.width, lip.raster.height, ByteArray(lip.raster.rgba.size)), lip.bounds)
            val covered = creationCoverage[lip.id.raw]?.let { padded(source, it) } ?: source
            layer.copy(source = MouthLipLayer(lip.ownerId, lip.side, owner, covered.raster, covered.bounds), opaquePixels = layer.opaquePixels.coerceAtLeast(1))
        })
        return Analyses(geometry, textures)
    }

    internal fun partitionCoverage(overlay: RigEditOverlay): Map<String, LayerBounds> = SourcePartitionJournal.commands(overlay)
        .flatMap(SourcePartitionJournal::pieces).associate { piece ->
            val canvas = piece.getValue("texture_canvas").jsonArray.map { it.jsonPrimitive.float }
            require(canvas.size >= 6 && canvas.size % 2 == 0 && canvas.all(Float::isFinite)) { "Invalid partition texture coverage" }
            val xs = canvas.indices.step(2).map { canvas[it] }; val ys = canvas.indices.step(2).map { canvas[it + 1] }
            val left = kotlin.math.floor(xs.min().toDouble()).toInt(); val top = kotlin.math.floor(ys.min().toDouble()).toInt()
            // A valid mesh can sample a single pixel along one texture axis.
            val right = maxOf(left + 1, kotlin.math.ceil(xs.max().toDouble()).toInt())
            val bottom = maxOf(top + 1, kotlin.math.ceil(ys.max().toDouble()).toInt())
            piece.getValue("layer_id").jsonPrimitive.content to LayerBounds(left, top, right - left, bottom - top)
        }

    internal fun geometrySource(current: SourceArt, reference: SourceArt, overlay: RigEditOverlay = RigEditOverlay.Empty): SourceArt {
        require(reference.widthPx == current.widthPx && reference.heightPx == current.heightPx) {
            "Generation source dimensions must match the current canvas"
        }
        val previous = reference.layers.associateBy { it.id.raw }
        val partitions = partitionCoverage(overlay)
        // Current metadata and newly added layers remain authoritative; existing raster shapes stay fixed.
        return object : SourceArt by current {
            override val layers = current.layers.map { layer ->
                partitions[layer.id.raw]?.let { coverage ->
                    val old = previous[layer.id.raw]?.bounds ?: layer.bounds
                    val left = minOf(old.left, coverage.left); val top = minOf(old.top, coverage.top)
                    val right = maxOf(old.left + old.width, coverage.left + coverage.width)
                    val bottom = maxOf(old.top + old.height, coverage.top + coverage.height)
                    // The ordered partition journal owns this geometry. Keep its texture extent
                    // without adding derived pixels to the durable original generation source.
                    return@map object : SourceLayer by layer {
                        override val bounds = LayerBounds(left, top, right - left, bottom - top)
                        override val raster = LayerRaster(1, 1, ByteArray(4))
                    }
                }
                previous[layer.id.raw]?.let { old -> object : SourceLayer by layer {
                    override val bounds = old.bounds
                    override val raster = old.raster
                } } ?: layer
            }
        }
    }

    fun repack(rig: BuiltRig, geometry: PipelineAnalysis, from: PackedAtlas,
               textures: PipelineAnalysis, to: PackedAtlas): BuiltRig {
        val oldLayers = geometry.layers.associateBy { it.source.id.raw }
        val newLayers = textures.layers.associateBy { it.source.id.raw }
        val pages = rig.pageByDrawableId.toMutableMap()
        val drawables = rig.puppet.drawables.map { drawable ->
            val mesh = drawable.mesh ?: return@map drawable
            val layerId = rig.layerIdByDrawableId.getValue(drawable.id.raw)
            val old = requireNotNull(from.placementByLayerId[layerId]) { "Generation atlas layer is missing" }
            val next = requireNotNull(to.placementByLayerId[layerId]) { "Current atlas layer is missing" }
            val oldBounds = oldLayers.getValue(layerId).source.bounds
            val newBounds = newLayers.getValue(layerId).source.bounds
            fun slice(atlas: PackedAtlas, placement: AtlasPlacement, bounds: LayerBounds): AtlasSlice {
                val page = atlas.pages[placement.page]
                return AtlasSlice(placement, page.image.width, page.image.height,
                    Bounds(bounds.left.toFloat(), bounds.top.toFloat(),
                        (bounds.left + bounds.width).toFloat(), (bounds.top + bounds.height).toFloat()))
            }
            val before = slice(from, old, oldBounds)
            val after = slice(to, next, newBounds)
            val sameAddress = old == next && oldBounds == newBounds &&
                from.pages[old.page].image.width == to.pages[next.page].image.width &&
                from.pages[old.page].image.height == to.pages[next.page].image.height
            val uvs = if (sameAddress) mesh.uvs else FloatArray(mesh.uvs.size).also { values ->
                for (index in values.indices step 2) {
                    values[index] = after.uvX(before.canvasX(mesh.uvs[index]))
                    values[index + 1] = after.uvY(before.canvasY(mesh.uvs[index + 1]))
                }
            }
            pages[drawable.id.raw] = next.page
            drawable.copy(mesh = DrawableMesh(mesh.positions, uvs, mesh.indices), texturePage = next.page)
        }
        val (atlas, sources) = PuppetSourceAtlas.build(textures, to)
        return rig.copy(puppet = rig.puppet.copy(drawables = drawables, atlas = atlas, sources = sources), pageByDrawableId = pages)
    }

    /** Transparent coverage prevents a kept mesh from sampling another layer after a tighter crop. */
    internal fun padded(current: SourceLayer, previous: LayerBounds): SourceLayer {
        val left = minOf(current.bounds.left, previous.left)
        val top = minOf(current.bounds.top, previous.top)
        val right = maxOf(current.bounds.left + current.bounds.width, previous.left + previous.width)
        val bottom = maxOf(current.bounds.top + current.bounds.height, previous.top + previous.height)
        val bounds = LayerBounds(left, top, right - left, bottom - top)
        if (bounds == current.bounds) return current
        val raster = BufferedImage(current.raster.width, current.raster.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            val offset = (y * raster.width + x) * 4
            val rgba = current.raster.rgba
            raster.setRGB(x, y, ((rgba[offset + 3].toInt() and 255) shl 24) or
                ((rgba[offset].toInt() and 255) shl 16) or ((rgba[offset + 1].toInt() and 255) shl 8) or
                (rgba[offset + 2].toInt() and 255))
        }
        val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { graphics.drawImage(raster, current.bounds.left - left, current.bounds.top - top,
            current.bounds.width, current.bounds.height, null) } finally { graphics.dispose() }
        val rgba = ByteArray(Math.multiplyExact(Math.multiplyExact(bounds.width, bounds.height), 4))
        for (y in 0 until bounds.height) for (x in 0 until bounds.width) {
            val pixel = image.getRGB(x, y)
            val offset = (y * bounds.width + x) * 4
            rgba[offset] = (pixel ushr 16).toByte(); rgba[offset + 1] = (pixel ushr 8).toByte()
            rgba[offset + 2] = pixel.toByte(); rgba[offset + 3] = (pixel ushr 24).toByte()
        }
        return object : SourceLayer by current {
            override val bounds = bounds
            override val raster = LayerRaster(bounds.width, bounds.height, rgba)
        }
    }
}
