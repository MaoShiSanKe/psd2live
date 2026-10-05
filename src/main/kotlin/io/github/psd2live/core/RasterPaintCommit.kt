package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.*
import org.umamo.edit.withDrawablesDeleted
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.*
import java.awt.image.BufferedImage

/**
 * One layer's slice of the packed atlas, with the source bounds it was cropped from.
 *
 * A mesh's texture coordinates address the slice, not the canvas, so repacking has to translate them
 * through canvas pixels: `uv -> canvas -> uv`. That round trip is what keeps a drawable on the same
 * pixels after its layer was re-cropped, which is why the atlas convention lives in one place.
 */
internal class AtlasSlice(
    val placement: io.github.psd2live.core.AtlasPlacement,
    val pageWidth: Int,
    val pageHeight: Int,
    /** The layer's source bounds in canvas pixels when this slice was packed. */
    val sourceBounds: Bounds,
) {
    private val scale get() = placement.scale.coerceAtLeast(1)

    fun canvasX(uv: Float): Float = sourceBounds.left + (uv * pageWidth - placement.x) / scale
    fun canvasY(uv: Float): Float = sourceBounds.top + (uv * pageHeight - placement.y) / scale
    fun uvX(canvasX: Float): Float = (placement.x + (canvasX - sourceBounds.left) * scale) / pageWidth
    fun uvY(canvasY: Float): Float = (placement.y + (canvasY - sourceBounds.top) * scale) / pageHeight
}

/**
 * The painted layer's box in canvas pixels, as the float box the rig math works in. The two are
 * easy to confuse: a [Bounds] holds edges, a [LayerBounds] holds a width and a height.
 */
private fun LayerBounds.toBounds(): Bounds =
	Bounds(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())

/** Re-addresses a mesh's texture coordinates from one slice of the atlas to another. */
private fun remapUvs(mesh: DrawableMesh, from: AtlasSlice, to: AtlasSlice): FloatArray {
    val uvs = FloatArray(mesh.uvs.size)
    for (index in mesh.uvs.indices step 2) {
        uvs[index] = to.uvX(from.canvasX(mesh.uvs[index]))
        uvs[index + 1] = to.uvY(from.canvasY(mesh.uvs[index + 1]))
    }
    return uvs
}

/** Shared raster/atlas/mesh preparation. Adapters own gestures, prompts and durable submission. */
internal object RasterPaintCommit {
    fun prepare(pipeline: PSD2LivePipeline, currentPreview: RigPreviewModel, layerId: String,
                image: BufferedImage, rebuildMesh: Boolean, preserveSourceRaster: Boolean = false,
                checkpoint: () -> Unit = {}, progress: ProgressListener = ProgressListener { _, _ -> }): RigPreviewModel {
        checkpoint()
        progress.update("Cropping painted source", 0.0)
        require(image.width == currentPreview.analysis.source.widthPx && image.height == currentPreview.analysis.source.heightPx) {
            "Paint image dimensions must match the source canvas"
        }
        require(sourceLayerFor(currentPreview, currentPreview.analysis, layerId) != null) { "Paint layer not found: $layerId" }
        val rebuild = rebuildMesh && !DepthSplit.isFrontLayer(currentPreview, layerId)
        val currentAnalysis = currentPreview.analysis
        // The frames the live rig was built on. Rebuilt from the previous analysis on purpose: the
        // commit preserves every deformer, so a mesh rebuilt against frames moved by the new paint
        // would no longer line up with the parent deformer it hangs under.
        val geometryAnalysis = if (currentPreview.config.generationSource == null) currentAnalysis
            else RigGenerationSource.prepare(currentAnalysis, currentPreview.config).geometry
        val rigContext = RigBuilder.rigContext(geometryAnalysis, currentPreview.config)
        val img = image
        val docW = image.width
        val docH = image.height

        val newBounds: LayerBounds
        val newRaster: LayerRaster

        // A hierarchy rebuild changes only the mesh. Keep the exact saved pixels and bounds instead
        // of running the paint-commit crop step over an untouched raster.
        val existingLayer = sourceLayerFor(currentPreview, currentAnalysis, layerId)
        if (!preserveSourceRaster && DepthSplit.isFrontLayer(currentPreview, layerId) && existingLayer != null) {
            // Erasing changes alpha only. Cropping would let vertices outside the smaller tile
            // sample neighbouring art, and rebuilding would discard the original rig and glue.
            newBounds = existingLayer.bounds
            val rgba = existingLayer.raster.rgba.copyOf()
            for (y in 0 until existingLayer.raster.height) for (x in 0 until existingLayer.raster.width) {
                if (x == 0) checkpoint()
                val docX = newBounds.left + x
                val docY = newBounds.top + y
                if (docX !in 0 until docW || docY !in 0 until docH) continue
                val argb = img.getRGB(docX, docY)
                val index = (y * existingLayer.raster.width + x) * 4
                rgba[index] = (argb ushr 16).toByte()
                rgba[index + 1] = (argb ushr 8).toByte()
                rgba[index + 2] = argb.toByte()
                rgba[index + 3] = (argb ushr 24).toByte()
            }
            newRaster = LayerRaster(existingLayer.raster.width, existingLayer.raster.height, rgba)
        } else if (preserveSourceRaster && existingLayer != null) {
            newBounds = existingLayer.bounds
            newRaster = existingLayer.raster
        } else {
            // 1. Scan workingImage to find tight non-transparent bounding box
            var minX = docW
            var minY = docH
            var maxX = -1
            var maxY = -1

            val row = IntArray(docW)
            for (y in 0 until docH) {
                checkpoint()
                img.getRGB(0, y, docW, 1, row, 0, docW)
                for (x in 0 until docW) {
                    val alpha = (row[x] ushr 24) and 0xFF
                    if (alpha > 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            if (maxX < minX || maxY < minY) {
                // Completely erased / transparent layer
                newBounds = LayerBounds(0, 0, 1, 1)
                newRaster = LayerRaster(1, 1, ByteArray(4))
            } else {
                val cropW = maxX - minX + 1
                val cropH = maxY - minY + 1
                newBounds = LayerBounds(minX, minY, cropW, cropH)
                val croppedImg = img.getSubimage(minX, minY, cropW, cropH)
                val pixels = IntArray(cropW * cropH)
                croppedImg.getRGB(0, 0, cropW, cropH, pixels, 0, cropW)
                val rgba = ByteArray(cropW * cropH * 4)
                for (i in pixels.indices) {
                    if (i % cropW == 0) checkpoint()
                    val argb = pixels[i]
                    rgba[i * 4] = ((argb ushr 16) and 0xFF).toByte()     // R
                    rgba[i * 4 + 1] = ((argb ushr 8) and 0xFF).toByte()  // G
                    rgba[i * 4 + 2] = (argb and 0xFF).toByte()           // B
                    rgba[i * 4 + 3] = ((argb ushr 24) and 0xFF).toByte() // A
                }
                newRaster = LayerRaster(cropW, cropH, rgba)
            }
        }

        // 2. Identify target Drawable, ClassifiedLayer, and SourceLayer
        val targetLid = layerId
        val targetDrawable = currentPreview.rig.puppet.drawables.firstOrNull {
            it.id.raw == targetLid || currentPreview.rig.layerIdByDrawableId[it.id.raw] == targetLid
        }
        val targetClassified = classifiedLayerFor(currentPreview, currentAnalysis, targetLid)
        val targetSourceLayerId = targetClassified?.source?.id?.raw
            ?: targetLid.substringBefore(':').substringBeforeLast('-')
        val oldBounds = sourceLayerFor(currentPreview, currentAnalysis, targetLid)?.bounds ?: newBounds

        // 3. Update Source Art and Classified Layers
        val updatedSrcLayers = currentAnalysis.source.layers.map { sl ->
            if (sl.id.raw == targetSourceLayerId || sl.id.raw == targetLid || sl.id.raw == targetClassified?.source?.id?.raw) {
                val base = if (sl is WorkspaceSourceLayer) sl else WorkspaceSourceLayer.copyOf(sl, sl.order) as WorkspaceSourceLayer
                base.copy(bounds = newBounds, raster = newRaster)
            } else {
                if (sl is WorkspaceSourceLayer) sl else WorkspaceSourceLayer.copyOf(sl, sl.order)
            }
        }
        val updatedSourceArt = WorkspaceSourceArt(
            widthPx = currentAnalysis.source.widthPx,
            heightPx = currentAnalysis.source.heightPx,
            layers = updatedSrcLayers,
            groups = currentAnalysis.source.groups,
        )

        val updatedClassifiedLayers = currentAnalysis.layers.map { cl ->
            checkpoint()
            if (cl.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid)) {
                val updatedSource = (cl.source as? WorkspaceSourceLayer)?.copy(bounds = newBounds, raster = newRaster)
                    ?: (WorkspaceSourceLayer.copyOf(cl.source, cl.source.order) as WorkspaceSourceLayer).copy(bounds = newBounds, raster = newRaster)
                val updatedFloatBounds = newBounds.toBounds()
                cl.copy(
                    source = updatedSource,
                    bounds = updatedFloatBounds,
                    opaquePixels = newBounds.width * newBounds.height,
                    centroidX = newBounds.left + newBounds.width * 0.5f,
                    centroidY = newBounds.top + newBounds.height * 0.5f,
                )
            } else cl
        }

        val updatedAnalysis = currentAnalysis.copy(
            source = updatedSourceArt,
            layers = updatedClassifiedLayers,
        )

        // 4. Repack texture atlas with updated layer raster
        val refreshedAnalysis = MouthLipLayers.prepare(updatedAnalysis, currentPreview.config)
        // The ribbons are generated from the mouth layer, so a repaint would normally regenerate them
        // too. Keeping the existing mesh means keeping the ribbons' own pixels as well: a regenerated
        // ribbon follows a contour the kept mesh no longer has, and its coordinates would fall outside
        // the slice it was packed into.
        val effectiveAnalysis = if (rebuild) refreshedAnalysis else refreshedAnalysis.copy(
            layers = refreshedAnalysis.layers.map { layer ->
                if (layer.source is MouthLipLayer) {
                    currentAnalysis.layers.firstOrNull { it.source.id.raw == layer.source.id.raw } ?: layer
                } else {
                    layer
                }
            },
        )
        val newAtlas = AtlasPacker.pack(
            effectiveAnalysis.layers,
            currentPreview.config.atlasSize,
            currentPreview.config.texturePadding,
            currentPreview.config.textureUpscale,
            ProgressListener { stage, fraction -> checkpoint(); progress.update(stage, 0.15 + 0.35 * fraction) },
        )
        progress.update("Preparing painted mesh", 0.55)
        val oldAtlas = currentPreview.atlas

        fun findPlacement(atlas: PackedAtlas, drawableId: String, layerId: String?): io.github.psd2live.core.AtlasPlacement? {
            if (layerId != null && atlas.placementByLayerId.containsKey(layerId)) {
                return atlas.placementByLayerId[layerId]
            }
            if (atlas.placementByLayerId.containsKey(drawableId)) {
                return atlas.placementByLayerId[drawableId]
            }
            val mappedId = currentPreview.rig.layerIdByDrawableId[drawableId]
            if (mappedId != null && atlas.placementByLayerId.containsKey(mappedId)) {
                return atlas.placementByLayerId[mappedId]
            }
            val baseId = (layerId ?: drawableId).substringBefore(':').substringBeforeLast('-')
            if (atlas.placementByLayerId.containsKey(baseId)) {
                return atlas.placementByLayerId[baseId]
            }
            return null
        }

        /** One layer's slice of [atlas], or null when it holds none. An unknown [bounds] reads as the
         *  canvas origin, which leaves the texture coordinates translated but unscaled. */
        fun sliceOf(atlas: PackedAtlas, drawableId: String, layerId: String, bounds: LayerBounds?): AtlasSlice? {
            val placement = findPlacement(atlas, drawableId, layerId) ?: return null
            val page = atlas.pages.getOrNull(placement.page)
            return AtlasSlice(
                placement = placement,
                pageWidth = page?.image?.width ?: placement.width,
                pageHeight = page?.image?.height ?: placement.height,
                sourceBounds = bounds?.let { Bounds(it.left.toFloat(), it.top.toFloat(), (it.left + it.width).toFloat(), (it.top + it.height).toFloat()) }
                    ?: Bounds(0f, 0f, 0f, 0f),
            )
        }

        /** The mesh a rebuild replaces, described so the frame its parent deformer expects can be
         *  recovered from the geometry itself - the only source left for an imported or hand-made rig. */
        fun replacedMesh(mesh: DrawableMesh, atlas: PackedAtlas, drawableId: String, layerId: String, bounds: LayerBounds): RigBuilder.ReplacedMesh {
            val slice = sliceOf(atlas, drawableId, layerId, bounds)
            return RigBuilder.ReplacedMesh(
                mesh = mesh,
                placement = slice?.placement,
                pageWidth = slice?.pageWidth ?: 1,
                pageHeight = slice?.pageHeight ?: 1,
                sourceBounds = slice?.sourceBounds ?: Bounds(0f, 0f, 0f, 0f),
            )
        }

        val targetPlacement = findPlacement(newAtlas, targetDrawable?.id?.raw ?: targetLid, targetClassified?.source?.id?.raw ?: targetLid)
            ?: newAtlas.placementByLayerId[targetLid]
            ?: newAtlas.placementByLayerId.values.firstOrNull()
            ?: io.github.psd2live.core.AtlasPlacement(0, 0, 0, newBounds.width, newBounds.height)
        val targetPage = newAtlas.pages.getOrNull(targetPlacement.page)
        val targetPageWidth = targetPage?.image?.width ?: currentPreview.config.atlasSize
        val targetPageHeight = targetPage?.image?.height ?: currentPreview.config.atlasSize

        val regeneratedLips = RigBuilder.generatedMouthLips(effectiveAnalysis)
        val finalTargetClassified = effectiveAnalysis.layers.firstOrNull { it.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid) }
            ?: updatedClassifiedLayers.firstOrNull { it.source.id.raw == (targetClassified?.source?.id?.raw ?: targetLid) }
            ?: targetClassified

        // 5. Update drawables (preserving deformers, hierarchy, keyforms, and rigging)
        val updatedPageByDrawableId = currentPreview.rig.pageByDrawableId.toMutableMap()
        val updatedSourceBounds = currentPreview.rig.sourceBoundsByDrawableId.toMutableMap()

        val droppedDrawables = mutableSetOf<DrawableId>()
        val rebuiltLips = mutableMapOf<String, RigBuilder.MouthLip>()
        val rebuiltDrawableIds = mutableSetOf<DrawableId>()
        val updatedDrawables = currentPreview.rig.puppet.drawables.mapNotNull { drawable ->
            checkpoint()
            val layerId = currentPreview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
            val isTarget = (targetDrawable != null && drawable.id == targetDrawable.id) ||
                           drawable.id.raw == targetLid ||
                           layerId == targetLid ||
                           layerId == targetClassified?.source?.id?.raw ||
                           drawable.id.raw == targetClassified?.source?.id?.raw

            if (isTarget) {
                updatedPageByDrawableId[drawable.id.raw] = targetPlacement.page

                if (rebuild || drawable.mesh == null) {
                    rebuiltDrawableIds += drawable.id
                    // The neutral-pose reference follows the mesh, not the texture: a kept mesh keeps
                    // describing the area it covers even when new pixels were painted beyond it.
                    updatedSourceBounds[drawable.id.raw] =
                        newBounds.toBounds()

                    val targetClassifiedLayer = finalTargetClassified
                        ?: targetClassified
                        ?: error("Target classified layer not found for paint commit: $targetLid")

                    // The rig keeps its deformers, so the new mesh has to be normalized against the
                    // frames those deformers were built on - the context of the analysis the rig came
                    // from - and not against frames derived from the freshly painted bounds, which
                    // would rescale the drawable against every sibling that kept the old frames.
                    val rebuilt = RigBuilder.rebuildDrawableMesh(
                        layer = targetClassifiedLayer,
                        context = rigContext,
                        placement = targetPlacement,
                        pageWidth = targetPageWidth,
                        pageHeight = targetPageHeight,
                        config = currentPreview.config,
                        parentId = drawable.parentDeformerId,
                        owner = drawable,
                        atlas = newAtlas,
                        generatedLips = regeneratedLips,
                        previous = drawable.mesh?.let { replacedMesh(it, oldAtlas, drawable.id.raw, layerId, oldBounds) },
                    )
                    for (lip in rebuilt.mouthLips) rebuiltLips[lip.drawable.id.raw] = lip

                    drawable.copy(
                        mesh = rebuilt.mesh,
                        texturePage = targetPlacement.page,
                        geometryGrid = drawable.geometryGrid ?: rebuilt.geometryGrid,
                    )
                } else {
                    val oldMesh = drawable.mesh
                    val oldSlice = sliceOf(oldAtlas, drawable.id.raw, layerId, oldBounds)
                    val newSlice = sliceOf(newAtlas, drawable.id.raw, layerId, newBounds)
                    if (oldSlice != null && newSlice != null) {
                        drawable.copy(
                            mesh = DrawableMesh(oldMesh.positions, remapUvs(oldMesh, oldSlice, newSlice), oldMesh.indices),
                            texturePage = targetPlacement.page,
                        )
                    } else {
                        drawable.copy(texturePage = targetPlacement.page)
                    }
                }
            } else {
                val oldSlice = sliceOf(oldAtlas, drawable.id.raw, layerId, sourceLayerFor(currentPreview, currentAnalysis, layerId)?.bounds)
                val newSlice = sliceOf(newAtlas, drawable.id.raw, layerId, sourceLayerFor(currentPreview, effectiveAnalysis, layerId)?.bounds)
                val oldMesh = drawable.mesh
                when {
                    // The repack has no slice for this drawable any more. That is what a generated layer
                    // does when the layer it follows loses the shape it was built from - an erased mouth
                    // takes its lip ribbons with it - and keeping the drawable would leave it sampling
                    // whatever the repack happened to place at its old texture coordinates, which reads
                    // as the art tearing apart instead of disappearing.
                    oldMesh != null && oldSlice != null && newSlice == null -> {
                        droppedDrawables += drawable.id
                        null
                    }
                    oldMesh != null && oldSlice != null && newSlice != null -> {
                        updatedPageByDrawableId[drawable.id.raw] = newSlice.placement.page
                        drawable.copy(
                            mesh = DrawableMesh(oldMesh.positions, remapUvs(oldMesh, oldSlice, newSlice), oldMesh.indices),
                            texturePage = newSlice.placement.page,
                        )
                    }
                    else -> drawable
                }
            }
        }

        // 6. Update puppet and rig (deformers, hierarchy, parameters preserved 100%). Deleting a drawable
        // goes through the model's own delete so the org tree, clip masks, glues and the derived render
        // order all stop referring to it.
        for (id in droppedDrawables) {
            updatedPageByDrawableId.remove(id.raw)
            updatedSourceBounds.remove(id.raw)
        }
        // Ribbons are swapped in by id, whichever side of their owner they sit on, and a ribbon the rig
        // never had - a mouth painted back after its own erase - joins the part its own mouth is in.
        var drawablesAfterRepack = updatedDrawables.map { drawable ->
            val lip = rebuiltLips[drawable.id.raw]
            if (lip != null) {
                rebuiltDrawableIds += drawable.id
                updatedPageByDrawableId[drawable.id.raw] = lip.drawable.texturePage
                updatedSourceBounds[drawable.id.raw] = lip.neutralBounds
            }
            if (lip == null) drawable else lip.drawable.copy(
                drawOrder = drawable.drawOrder,
                blendMode = drawable.blendMode,
                isVisible = drawable.isVisible,
                maskedBy = drawable.maskedBy,
                geometryGrid = drawable.geometryGrid,
                channelGrids = drawable.channelGrids,
                blendShapes = drawable.blendShapes,
            )
        }
        val addedLips = rebuiltLips.values.filter { lip -> drawablesAfterRepack.none { it.id == lip.drawable.id } }
        for (lip in addedLips) {
            updatedPageByDrawableId[lip.drawable.id.raw] = lip.drawable.texturePage
            updatedSourceBounds[lip.drawable.id.raw] = lip.neutralBounds
        }
        if (addedLips.isNotEmpty()) drawablesAfterRepack = drawablesAfterRepack + addedLips.map { it.drawable }
        val partsAfterRepack = if (addedLips.isEmpty()) {
            currentPreview.rig.puppet.parts
        } else {
            val addedByOwner = addedLips.groupBy { it.ownerId }
            currentPreview.rig.puppet.parts.map { part ->
                val added = part.children.filterIsInstance<OrgChild.Drawable>()
                    .flatMap { addedByOwner[it.id].orEmpty() }
                if (added.isEmpty()) part else part.copy(children = part.children + added.map { OrgChild.Drawable(it.drawable.id) })
            }
        }
        val (repackedPuppetAtlas, repackedSources) = PuppetSourceAtlas.build(effectiveAnalysis, newAtlas,
            sourceIdRaw = if (currentPreview.config.rigEdits.importedCmo3 == null) PuppetSourceAtlas.SOURCE_ID_RAW
                else Cmo3ModelImport.PAINT_SOURCE_ID)
        val updatedPuppet = currentPreview.rig.puppet
            .let { puppet -> if (droppedDrawables.isEmpty()) puppet else puppet.withDrawablesDeleted(droppedDrawables) }
            .copy(
                drawables = drawablesAfterRepack,
                atlas = repackedPuppetAtlas,
                sources = repackedSources,
                parts = partsAfterRepack,
                deformPaths = currentPreview.rig.puppet.deformPaths
                    .filterNot { it.drawableId in droppedDrawables } +
                    addedLips.mapNotNull { it.path },
            )
            .let { puppet -> if (addedLips.isEmpty()) puppet else puppet.withDerivedRenderRoot() }
            .let { puppet ->
                var migrated = currentPreview.rig.puppet
                for (id in rebuiltDrawableIds) {
                    checkpoint()
                    val oldMesh = migrated.drawables.firstOrNull { it.id == id }?.mesh ?: continue
                    val newMesh = puppet.drawables.firstOrNull { it.id == id }?.mesh ?: continue
                    migrated = RasterMeshJournal.apply(migrated, id, RasterMeshJournal.prepare(oldMesh, newMesh))
                }
                val liveIds = puppet.drawables.mapTo(HashSet()) { it.id }
                puppet.copy(
                    drawables = puppet.drawables.map { drawable ->
                        val carried = migrated.drawables.firstOrNull { it.id == drawable.id }
                        if (drawable.id !in rebuiltDrawableIds || carried?.mesh == null) drawable else drawable.copy(
                            geometryGrid = carried.geometryGrid, blendShapes = carried.blendShapes,
                        )
                    },
                    glues = migrated.glues.filter { it.meshA in liveIds && it.meshB in liveIds },
                    deformPaths = puppet.deformPaths.map { path ->
                        if (path.drawableId !in rebuiltDrawableIds) path
                        else migrated.deformPaths.firstOrNull { it.id == path.id } ?: path
                    },
                    vertexGroups = puppet.vertexGroups.map { group ->
                        migrated.vertexGroups.firstOrNull { it.drawableId == group.drawableId && it.name == group.name } ?: group
                    },
                )
            }
        var updatedRig = currentPreview.rig.copy(
            puppet = updatedPuppet,
            pageByDrawableId = updatedPageByDrawableId,
            sourceBoundsByDrawableId = updatedSourceBounds,
            layerIdByDrawableId = currentPreview.rig.layerIdByDrawableId +
                addedLips.associate { it.drawable.id.raw to it.layer.source.id.raw },
        )

        // A transparent source layer has no drawable yet. Generate its first mesh in the old
        // rig's frames, then carry only its new objects into the otherwise preserved puppet.
        if (targetDrawable == null && finalTargetClassified != null &&
            (0 until newRaster.width * newRaster.height).any { (newRaster.rgba[it * 4 + 3].toInt() and 255) > currentPreview.config.alphaThreshold }) {
            val stable = RigBuilder.assignSplitDrawableIds(effectiveAnalysis,
                currentPreview.rig.layerIdByDrawableId.entries.associate { it.value to DrawableId(it.key) })
            val generated = RigBuilder.buildPreservingDeformers(effectiveAnalysis, newAtlas, currentPreview.config, null,
                geometryAnalysis, currentPreview.config, stable)
            checkpoint()
            val ids = generated.layerIdByDrawableId.filterValues { layer ->
                layer == finalTargetClassified.source.id.raw || effectiveAnalysis.layers.any { candidate ->
                    val lip = candidate.source as? MouthLipLayer
                    lip?.id?.raw == layer && lip.ownerId == finalTargetClassified.source.id.raw
                }
            }.keys
            val born = generated.puppet.drawables.filter { it.id.raw in ids && updatedRig.puppet.drawables.none { old -> old.id == it.id } }
            val bornIds = born.mapTo(HashSet()) { it.id }
            val children = bornIds.mapTo(HashSet()) { OrgChild.Drawable(it) }
            val current = updatedRig.puppet
            val additions = generated.puppet.parts.filter { part -> current.parts.none { it.id == part.id } }
                .map { it.copy(children = it.children.filter { child -> child in children }) }
            val parts = current.parts.map { part ->
                val added = generated.puppet.parts.firstOrNull { it.id == part.id }?.children.orEmpty().filter { it in children }
                part.copy(children = part.children + added)
            } + additions
            val axes = born.flatMap { it.geometryGrid?.axes.orEmpty() + it.channelGrids.gridsByChannel.values.flatMap { grid -> grid.axes } }
                .mapTo(HashSet()) { it.parameterId }
            val parameters = generated.puppet.parameters.filter { it.id in axes && current.parameters.none { old -> old.id == it.id } }
            updatedRig = updatedRig.copy(puppet = current.copy(drawables = current.drawables + born, parts = parts,
                rootChildren = current.rootChildren + generated.puppet.rootChildren.filter { it in children } + additions.map { OrgChild.Part(it.id) },
                parameters = current.parameters + parameters, parameterTree = current.parameterTree + parameters.map { ParameterNode.Param(it.id) },
                deformPaths = current.deformPaths + generated.puppet.deformPaths.filter { it.drawableId in bornIds }).withDerivedRenderRoot(),
                pageByDrawableId = updatedRig.pageByDrawableId + generated.pageByDrawableId.filterKeys { it in ids },
                sourceBoundsByDrawableId = updatedRig.sourceBoundsByDrawableId + generated.sourceBoundsByDrawableId.filterKeys { it in ids },
                layerIdByDrawableId = updatedRig.layerIdByDrawableId + generated.layerIdByDrawableId.filterKeys { it in ids })
        }

        progress.update("Preparing painted runtime", 0.85)
        checkpoint()
        val (runtimeBundle, _) = pipeline.buildRuntimeBundle(
            "psd2live-preview",
            effectiveAnalysis,
            newAtlas,
            updatedRig,
            currentPreview.config,
        )

        val committedConfig = if (rebuild && targetDrawable != null) {
            val rebuiltMesh = updatedRig.puppet.drawables
                .firstOrNull { it.id == targetDrawable.id }?.mesh
            val meshVertexCount = rebuiltMesh?.positions?.size?.div(2)
            val baseEdits = if (preserveSourceRaster && meshVertexCount != null) {
                MeshRebuildEdits.reset(
                    currentPreview.config.rigEdits,
                    targetDrawable.id.raw,
                    targetDrawable.mesh?.vertexCount ?: 0,
                    meshVertexCount,
                )
            } else currentPreview.config.rigEdits
            val previousBasePaths = currentPreview.baseRig.puppet.deformPaths
                .filter { it.drawableId == targetDrawable.id }
            val survivingPaths = currentPreview.rig.puppet.deformPaths
                .filter { it.drawableId == targetDrawable.id }
                .mapNotNull { path -> updatedPuppet.deformPaths.firstOrNull { it.id == path.id } }
            currentPreview.config.copy(
                rigEdits = DeformPathJournal.replaceMeshPaths(
                    baseEdits,
                    targetDrawable.id.raw,
                    previousBasePaths,
                    survivingPaths,
                ).let { edits ->
                    VertexGroupJournal.replaceMeshGroups(
                        edits,
                        targetDrawable.id.raw,
                        updatedPuppet.vertexGroups.filter { it.drawableId == targetDrawable.id },
                    )
                },
            )
        } else currentPreview.config

        val finalPreview = currentPreview.copy(
            analysis = updatedAnalysis,
            atlas = newAtlas,
            rig = updatedRig,
            config = committedConfig,
            baseRig = currentPreview.baseRig.copy(puppet = updatedPuppet, pageByDrawableId = updatedPageByDrawableId, sourceBoundsByDrawableId = updatedSourceBounds),
            runtimeBundle = runtimeBundle,
        )

        checkpoint()
        progress.update("Prepared painted mesh", 1.0)
        return finalPreview
    }

    /**
     * Every id a paint target can be named by: the rig maps generated drawables back to their layer,
     * and generated mouth lips carry a suffix on top of it.
     */
    private fun paintTargetIds(preview: RigPreviewModel?, layerId: String): List<String> = buildList {
        add(layerId)
        preview?.rig?.layerIdByDrawableId?.get(layerId)?.let { add(it) }
        layerId.substringBefore(':').substringBeforeLast('-').let { if (it !in this) add(it) }
    }

    private fun classifiedLayerFor(preview: RigPreviewModel, analysis: PipelineAnalysis, layerId: String): ClassifiedLayer? =
        paintTargetIds(preview, layerId).firstNotNullOfOrNull { id ->
            analysis.layers.firstOrNull { it.source.id.raw == id }
        }

    fun sourceLayerFor(preview: RigPreviewModel?, analysis: PipelineAnalysis, layerId: String): SourceLayer? =
        paintTargetIds(preview, layerId).firstNotNullOfOrNull { id ->
            analysis.layers.firstOrNull { it.source.id.raw == id }?.source
                ?: analysis.source.layers.firstOrNull { it.id.raw == id }
        }

}
