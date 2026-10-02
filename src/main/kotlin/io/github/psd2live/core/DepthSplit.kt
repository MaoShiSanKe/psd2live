package io.github.psd2live.core

import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.*
import java.util.UUID

/** Two independently paintable slices of the same rig, welded vertex for vertex. */
internal object DepthSplit {
    const val OP = "canvas_depth_split"

    data class Result(val preview: RigPreviewModel, val frontLayerId: String)

    fun isFrontLayer(preview: RigPreviewModel?, layerId: String?): Boolean =
        layerId != null && preview?.config?.rigEdits?.authoringJournal?.any {
            it["op"]?.jsonPrimitive?.contentOrNull == OP &&
                it["layer_id"]?.jsonPrimitive?.contentOrNull == layerId
        } == true

    fun build(pipeline: PSD2LivePipeline, current: RigPreviewModel, config: PipelineConfig,
              sourceId: String, middleId: String): Result =
        build(pipeline, current, config, sourceId, listOf(middleId))

    fun build(pipeline: PSD2LivePipeline, current: RigPreviewModel, config: PipelineConfig,
              sourceId: String, middleIds: List<String>): Result {
        require(middleIds.isNotEmpty() && sourceId !in middleIds)
        require(config.rigEdits.importedCmo3 == null) { tr("editor.depthSplit.imported") }
        val source = current.rig.puppet.drawables.single { it.id.raw == sourceId }
        require(source.mesh != null)
        val middles = middleIds.distinct().map { id ->
            current.rig.puppet.drawables.single { it.id.raw == id }.also { require(it.mesh != null) }
        }
        val layerId = current.rig.layerIdByDrawableId.getValue(sourceId)
        val layer = current.analysis.layers.single { it.source.id.raw == layerId }
        val frontId = "depth:${UUID.randomUUID()}"
        val frontDrawableId = "ArtMeshDepth_${UUID.randomUUID()}"
        val backName = tr("editor.depthSplit.backName", source.name)
        val frontName = tr("editor.depthSplit.frontName", source.name)
        val original = WorkspaceSourceLayer.copyOf(layer.source, layer.source.order) as WorkspaceSourceLayer
        val front = original.copy(id = LayerId(frontId), name = frontName,
            order = (current.analysis.source.layers.maxOfOrNull { it.order } ?: 0) + 1,
            raster = LayerRaster(original.raster.width, original.raster.height, original.raster.rgba.copyOf()),
            clipped = false, derived = true)
        val art = WorkspaceSourceArt(current.analysis.source.widthPx, current.analysis.source.heightPx,
            current.analysis.source.layers + front, current.analysis.source.groups)
        val middleOrders = middles.associate { drawable ->
            val middleLayerId = current.rig.layerIdByDrawableId[drawable.id.raw]
            drawable.id.raw to (config.drawOrderOverrides[middleLayerId]
                ?: config.drawOrderOverrides[drawable.id.raw] ?: drawable.drawOrder)
        }
        val low = middleOrders.values.min()
        val high = middleOrders.values.max()
        // Make room at the draw-order limits while preserving the middle objects' relative order.
        val placedOrders = middleOrders.mapValues { (_, order) ->
            when {
                low >= 1f && high <= 999f -> order
                low == high -> order.coerceIn(1f, 999f)
                else -> 1f + (order - low) / (high - low) * 998f
            }
        }
        val backOrder = placedOrders.values.min() - 1f
        val frontOrder = placedOrders.values.max() + 1f
        val command = buildJsonObject {
            put("op", OP); put("id", frontDrawableId); put("source", sourceId)
            put("layer_id", frontId); put("back_name", backName); put("front_name", frontName)
            put("back_order", backOrder); put("front_order", frontOrder)
            put("glue_id", "GlueDepth_${UUID.randomUUID()}")
        }
        val inherited = config.layerOverrides[layerId] ?: layer.semantic.let {
            LayerClassificationOverride(it.type, it.tag, it.side, it.parameter, it.switchId)
        }
        // Freeze the pre-copy frames and identities. Adding art must never renumber existing meshes
        // or change the coordinate systems against which their authored keyforms were written.
        val ids = current.rig.layerIdByDrawableId.entries.associate { it.value to it.key } + (frontId to frontDrawableId)
        val orders = current.rig.puppet.drawables.associate { it.id.raw to it.drawOrder } +
            config.drawOrderOverrides + placedOrders.mapKeys { (id, _) ->
                current.rig.layerIdByDrawableId[id] ?: id
            } + mapOf(layerId to backOrder, frontId to frontOrder)
        val updated = config.copy(
            layerOverrides = config.layerOverrides + (frontId to inherited),
            parentOverrides = config.parentOverrides + (frontId to source.parentDeformerId?.raw),
            layerVisibility = config.layerVisibility + (frontId to source.isVisible),
            drawOrderOverrides = orders,
            rigEdits = config.rigEdits.copy(
                splitBaselineLayerIds = config.rigEdits.splitBaselineLayerIds.ifEmpty {
                    current.analysis.source.layers.mapTo(LinkedHashSet()) { it.id.raw }
                },
                splitDrawableIds = config.rigEdits.splitDrawableIds + ids,
                authoringJournal = config.rigEdits.authoringJournal + command,
            ),
        )
        return Result(pipeline.buildPreviewAfterLayerSplit(current, art, updated), frontId)
    }

    /** Replays at the exact point the copy was made, after earlier mesh edits, before later ones. */
    fun apply(model: PuppetModel, command: JsonObject): PuppetModel {
        val source = model.drawables.firstOrNull { it.id.raw == command.getValue("source").jsonPrimitive.content }
            ?: return model // The rear layer may have been deleted subsequently.
        val mesh = source.mesh ?: return model
        val id = DrawableId(command.getValue("id").jsonPrimitive.content)
        val tileId = PuppetSourceAtlas.tileIdFor(command.getValue("layer_id").jsonPrimitive.content)
        val fromTile = model.atlas.tiles.firstOrNull { it.id == source.atlasTileId } ?: return model
        val toTile = model.atlas.tiles.firstOrNull { it.id == tileId } ?: return model
        val from = fromTile.placement ?: return model
        val to = toTile.placement ?: return model
        fun sourceLayer(tile: AtlasTile): ArtSourceLayer {
            val ref = requireNotNull(tile.source)
            return model.sources.single { it.id == ref.sourceId }.layers.single { it.key == ref.layerKey }
        }
        val fromLayer = sourceLayer(fromTile)
        val toLayer = sourceLayer(toTile)
        val fromPage = model.atlas.pages[from.pageIndex]
        val toPage = model.atlas.pages[to.pageIndex]
        val uvs = FloatArray(mesh.uvs.size)
        for (i in uvs.indices step 2) {
            val pixel = requireNotNull(layerPixelOf(from, mesh.uvs[i] * fromPage.width, mesh.uvs[i + 1] * fromPage.height))
            val packed = atlasPixelOf(to, pixel[0] + fromLayer.left - toLayer.left, pixel[1] + fromLayer.top - toLayer.top)
            uvs[i] = packed[0] / toPage.width
            uvs[i + 1] = packed[1] / toPage.height
        }
        fun atOrder(drawable: Drawable, order: Float): Drawable = drawable.copy(drawOrder = order,
            channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel - FormChannel.DRAW_ORDER),
            blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, it.opacity, it.multiplyColor, it.screenColor) }
            }) })
        val prior = model.drawables.firstOrNull { it.id == id }
        val front = atOrder(source.copy(id = id, name = command.getValue("front_name").jsonPrimitive.content,
            mesh = DrawableMesh(mesh.positions.copyOf(), uvs, mesh.indices.copyOf()),
            atlasTileId = tileId, texturePage = to.pageIndex, textureSourceId = null,
            isVisible = prior?.isVisible ?: source.isVisible,
        ), command.getValue("front_order").jsonPrimitive.float)
        val back = atOrder(source.copy(name = command.getValue("back_name").jsonPrimitive.content),
            command.getValue("back_order").jsonPrimitive.float)
        // A=rear remains the original rig. B=front follows it completely, including deformation
        // produced later by simulation and pre-existing glues; averaging would move the original.
        val glueId = command.getValue("glue_id").jsonPrimitive.content
        val glue = Glue(source.id, id, (0 until mesh.vertexCount).map { GluePair(it, it, 0f, 1f) }, id = glueId)
        fun children(old: List<OrgChild>): List<OrgChild> = old.filterNot { it == OrgChild.Drawable(id) }.flatMap {
            if (it == OrgChild.Drawable(source.id)) listOf(it, OrgChild.Drawable(id)) else listOf(it)
        }
        return model.copy(
            drawables = model.drawables.filterNot { it.id == id }.map { if (it.id == source.id) back else it } + front,
            parts = model.parts.map { it.copy(children = children(it.children)) },
            rootChildren = children(model.rootChildren),
            glues = model.glues.filterNot { it.id == glueId } + glue,
            deformPaths = model.deformPaths.filterNot { it.drawableId == id } + model.deformPaths
                .filter { it.drawableId == source.id }.map { it.copy(id = "$glueId/${it.id}", drawableId = id) },
            vertexGroups = model.vertexGroups.filterNot { it.drawableId == id } + model.vertexGroups
                .filter { it.drawableId == source.id }.map { it.copy(drawableId = id, weights = it.weights.copyOf()) },
        ).withDerivedRenderRoot()
    }
}
