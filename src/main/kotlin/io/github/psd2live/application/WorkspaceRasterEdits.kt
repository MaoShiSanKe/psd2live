package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import java.awt.image.BufferedImage

/** Captured canvas pixels, rather than a live paint session or a mutable preview. */
data class WorkspacePaintRaster(val layerId: String, val raster: LayerRaster,
                                val rebuildMesh: Boolean, val preserveSourceRaster: Boolean = false) {
    init {
        require(raster.width > 0 && raster.height > 0 && raster.width.toLong() * raster.height <= 16_777_216)
        require(raster.rgba.size.toLong() == raster.width.toLong() * raster.height * 4)
    }

    companion object {
        fun capture(layerId: String, image: BufferedImage, rebuildMesh: Boolean, preserveSourceRaster: Boolean = false,
                    checkpoint: () -> Unit = {}): WorkspacePaintRaster {
            require(image.width.toLong() * image.height <= 16_777_216) { "Painting requires a canvas of at most 16 megapixels" }
            val rgba = ByteArray(Math.multiplyExact(Math.multiplyExact(image.width, image.height), 4))
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (x == 0) checkpoint()
                val pixel = image.getRGB(x, y); val index = (y * image.width + x) * 4
                rgba[index] = (pixel ushr 16).toByte(); rgba[index + 1] = (pixel ushr 8).toByte()
                rgba[index + 2] = pixel.toByte(); rgba[index + 3] = (pixel ushr 24).toByte()
            }
            return WorkspacePaintRaster(layerId, LayerRaster(image.width, image.height, rgba), rebuildMesh, preserveSourceRaster)
        }
    }
}

/** GUI pixels and public raster gestures prepare the same durable candidate before rebuild/CAS. */
internal object WorkspaceRasterEdits {
    /** Shared so repeated paint commits reuse its content-addressed mesh cache. */
    private val pipeline = PSD2LivePipeline()
    fun paint(document: WorkspaceDocument, model: RigPreviewModel, arguments: JsonObject,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument =
        prepare(document, model, WorkspacePaintRaster.capture(arguments.getValue("layer_id").jsonPrimitive.content,
            document.paintSourceImage(arguments, work), arguments["rebuild_mesh"]?.jsonPrimitive?.boolean ?: false,
            checkpoint = work::checkpoint), work)

    fun prepare(document: WorkspaceDocument, model: RigPreviewModel, request: WorkspacePaintRaster,
                work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        work.progress(0.25f, "Preparing painted pixels")
        require(request.raster.width == document.source.widthPx && request.raster.height == document.source.heightPx) {
            "Paint image dimensions must match the source canvas"
        }
        val id = RasterPaintCommit.sourceLayerFor(model, model.analysis, request.layerId)?.id?.raw
            ?: throw IllegalArgumentException("Paint layer not found: ${request.layerId}")
        val layer = document.source.layers.singleOrNull { it.id.raw == id && id !in document.deletedLayerIds }
            ?: throw IllegalArgumentException("Paint target must be a source artwork layer: $id")
        val image = BufferedImage(request.raster.width, request.raster.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (x == 0) work.checkpoint()
            val index = (y * image.width + x) * 4; val rgba = request.raster.rgba
            image.setRGB(x, y, ((rgba[index + 3].toInt() and 255) shl 24) or
                ((rgba[index].toInt() and 255) shl 16) or ((rgba[index + 1].toInt() and 255) shl 8) or
                (rgba[index + 2].toInt() and 255))
        }
        val original = document.sourceLayerImage(id, work::checkpoint)
        val samePixels = original.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            .contentEquals(image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        val missingMesh = model.rig.puppet.drawables.none { drawable -> drawable.mesh != null && model.rig.layerIdByDrawableId[drawable.id.raw] == id }
        val visiblePixels = (0 until request.raster.width * request.raster.height).any {
            if (it % request.raster.width == 0) work.checkpoint()
            (request.raster.rgba[it * 4 + 3].toInt() and 255) > model.config.alphaThreshold
        }
        val rebuild = visiblePixels && (request.rebuildMesh || missingMesh) && !DepthSplit.isFrontLayer(model, id)
        if (samePixels && !rebuild) return document
        val working = if (document.rigEdits.importedCmo3 == null) model else Cmo3ModelImport.paintingPreview(pipeline, document.source,
            document.config().copy(generationSource = document.generationSource ?: document.source))
        val prepared = RasterPaintCommit.prepare(pipeline, working, id, image, rebuild,
            request.preserveSourceRaster || samePixels, work::checkpoint,
            ProgressListener { stage, fraction -> work.progress(0.35f + 0.45f * fraction.toFloat(), stage) })
        val beforeIds = model.rig.puppet.drawables.mapTo(HashSet()) { it.id }
        val migrations = if (!rebuild) emptyList() else prepared.rig.puppet.drawables.mapNotNull { drawable ->
            work.checkpoint()
            val previous = model.rig.puppet.drawables.singleOrNull { it.id == drawable.id }?.mesh ?: return@mapNotNull null
            val replacement = drawable.mesh ?: return@mapNotNull null
            if (previous.positions.contentEquals(replacement.positions) && previous.indices.contentEquals(replacement.indices)) null
            else RasterMeshJournal.encode(model.rig.puppet, drawable.id, replacement, prepared.rig.puppet,
                prepared.rig.sourceBoundsByDrawableId[drawable.id.raw])
        }
        val creations = prepared.rig.puppet.drawables.filter { it.id !in beforeIds }.map { work.checkpoint(); RasterMeshCreation.encode(prepared.rig, it.id) }
        // Creation needs the candidate texture inventory, with only the preceding committed objects.
        val inventory = working.rig.puppet.copy(atlas = prepared.rig.puppet.atlas, sources = prepared.rig.puppet.sources)
        val records = migrations + creations
        val journal = if (records.isEmpty()) emptyList() else RigAuthoringJournal.compile(inventory, JsonArray(records)).second
        work.progress(1f, "Prepared painted document")
        val source = if (samePixels) document.source else prepared.analysis.source
        val meshInputs = document.meshSource ?: document.generationSource ?: document.source
        val savedMeshInput = meshInputs.layers.singleOrNull { it.id == layer.id }
        val previousMeshInput = savedMeshInput ?: layer
        val currentMeshInput = source.layers.single { it.id == layer.id }
        val changedMeshInput = rebuild && (savedMeshInput == null || previousMeshInput.bounds != currentMeshInput.bounds ||
            previousMeshInput.raster.width != currentMeshInput.raster.width || previousMeshInput.raster.height != currentMeshInput.raster.height ||
            !previousMeshInput.raster.rgba.contentEquals(currentMeshInput.raster.rgba))
        val meshSource = if (!rebuild) document.meshSource else WorkspaceSourceArt(source.widthPx, source.heightPx,
            meshInputs.layers.map { old -> if (old.id == layer.id) currentMeshInput else old } +
                listOfNotNull(currentMeshInput.takeIf { savedMeshInput == null }), source.groups)
        if (samePixels && journal.isEmpty() && !changedMeshInput) return document
        return document.copy(source = source, generationSource = document.generationSource ?: document.source,
            meshSource = meshSource, rigEdits = document.rigEdits.copy(authoringJournal = document.rigEdits.authoringJournal + journal))
    }
}
