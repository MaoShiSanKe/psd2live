package io.github.psd2live.project

import io.github.psd2live.core.*
import kotlinx.serialization.json.JsonObject
import org.umamo.format.art.*

/** Immutable aggregate captured by each append-only history node. Raster buffers are never mutated. */
data class WorkspaceDocument(
	val source: SourceArt,
	val layerVisibility: Map<String, Boolean>,
	val deletedLayerIds: Set<String>,
	val layerOverrides: Map<String, LayerClassificationOverride>,
	val parentOverrides: Map<String, String?>,
	val rigEdits: RigEditOverlay,
    val settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
	val meshOverrides: Map<String, io.github.psd2live.core.MeshSettings> = emptyMap(),
	/** Original raster geometry used to generate the rig when subsequent paint keeps its bindings. */
	val generationSource: SourceArt? = null,
	/** Pixels at the last explicit mesh rebuild, used by generated contour textures. */
	val meshSource: SourceArt? = null,
    /** Original imported pixels used by absolute placement; excluded from rig generation. */
    val placementSource: SourceArt? = null,
)

/** Marker used to distinguish Agent-created source layers from layers loaded from the artist file. */
internal interface WorkspaceSourceMetadata : SourceLayer {
	val derived: Boolean
	val sourceAssetId: String?
	val sourceSpatialReferenceId: String?
}

internal data class WorkspaceSourceArt(
	override val widthPx: Int,
	override val heightPx: Int,
	override val layers: List<SourceLayer>,
	override val groups: List<SourceGroup>,
) : SourceArt

internal data class WorkspaceSourceLayer(
	override val id: LayerId,
	override val name: String,
	override val groupPath: String,
	override val kind: SourceLayerKind,
	override val visible: Boolean,
	override val order: Int,
	override val bounds: LayerBounds,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val channelMask: ChannelMask,
	override val raster: LayerRaster,
	override val sourceAssetId: String?,
	override val sourceSpatialReferenceId: String?,
	override val derived: Boolean,
) : WorkspaceSourceMetadata {
	companion object {
		fun copyOf(layer: SourceLayer, order: Int): SourceLayer = WorkspaceSourceLayer(
			id = layer.id,
			name = layer.name,
			groupPath = layer.groupPath,
			kind = layer.kind,
			visible = layer.visible,
			order = order,
			bounds = layer.bounds,
			opacity = layer.opacity,
			clipped = layer.clipped,
			blend = layer.blend,
			channelMask = layer.channelMask,
			raster = layer.raster,
			sourceAssetId = (layer as? WorkspaceSourceMetadata)?.sourceAssetId,
			sourceSpatialReferenceId = (layer as? WorkspaceSourceMetadata)?.sourceSpatialReferenceId,
			derived = (layer as? WorkspaceSourceMetadata)?.derived == true,
		)
	}
}


/** The document, rather than a renderer or UI projection, supplies all durable generation inputs. */
internal fun WorkspaceDocument.config(base: PipelineConfig = PipelineConfig()): PipelineConfig {
    val saved = WorkspaceSettingsCodec.decode(settings, base)
    // Match the desktop's existing effective-generation policy while retaining raw v1 settings.
    val hasMotion = (saved.motionBasic && (saved.motionIdle || saved.motionBlink || saved.motionNod || saved.motionShake)) ||
        saved.motionSkeleton || rigEdits.motionClips.any { it.builtin == null && it.enabled }
    return saved.copy(
        generateDeformers = if (rigEdits.importedCmo3 != null) saved.generateDeformers else !saved.meshOnly,
        exportMotions = !saved.meshOnly && hasMotion,
        generatePhysics = saved.generatePhysics && !saved.meshOnly,
        layerVisibility = layerVisibility,
        deletedLayerIds = deletedLayerIds,
        layerOverrides = layerOverrides,
        parentOverrides = parentOverrides,
        rigEdits = rigEdits,
        meshOverrides = meshOverrides,
        generationSource = generationSource,
        meshSource = meshSource,
    )
}
