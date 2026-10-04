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


/**
 * The document stores raw v1 settings: what the user or Agent chose, never the value generation derived from
 * them. [WorkspaceSettingsPolicy.effective] is applied only where generation, export or queries consume them.
 */
internal fun WorkspaceDocument.rawConfig(base: PipelineConfig = PipelineConfig()): PipelineConfig =
    WorkspaceSettingsCodec.decode(settings, base).copy(
        layerVisibility = layerVisibility,
        deletedLayerIds = deletedLayerIds,
        layerOverrides = layerOverrides,
        parentOverrides = parentOverrides,
        rigEdits = rigEdits,
        meshOverrides = meshOverrides,
        generationSource = generationSource,
        meshSource = meshSource,
    )

/** The document, rather than a renderer or UI projection, supplies all durable generation inputs. */
internal fun WorkspaceDocument.config(base: PipelineConfig = PipelineConfig()): PipelineConfig =
    WorkspaceSettingsPolicy.effective(rawConfig(base))

/** The one raw-to-effective rule for the three settings that other settings gate. */
internal object WorkspaceSettingsPolicy {
    /** The generated-motion switches the desktop has always kept exportMotions in step with. */
    fun motionOptionOn(config: PipelineConfig): Boolean = config.motionIdle || config.motionBlink ||
        config.motionNod || config.motionShake || config.motionSkeleton

    fun hasMotion(config: PipelineConfig): Boolean =
        (config.motionBasic && (config.motionIdle || config.motionBlink || config.motionNod || config.motionShake)) ||
            config.motionSkeleton || config.rigEdits.motionClips.any { it.builtin == null && it.enabled }

    /**
     * Mesh-only gates deformers, motions and physics. A raw false is honoured except where v1 files cannot tell
     * it from the old derived value: exportMotions=false with every generated motion off was written by the
     * desktop itself, and custom clips still exported then, so that combination keeps exporting them.
     */
    fun effective(raw: PipelineConfig): PipelineConfig = raw.copy(
        generateDeformers = if (raw.rigEdits.importedCmo3 != null) raw.generateDeformers else !raw.meshOnly && raw.generateDeformers,
        exportMotions = !raw.meshOnly && hasMotion(raw) && (raw.exportMotions || !motionOptionOn(raw)),
        generatePhysics = raw.generatePhysics && !raw.meshOnly,
    )

    /** Undo [effective] on a config generation returned, so it can be stored as the document's raw settings. */
    fun restoreRaw(generated: PipelineConfig, raw: PipelineConfig): PipelineConfig = generated.copy(
        meshOnly = raw.meshOnly, generateDeformers = raw.generateDeformers,
        exportMotions = raw.exportMotions, generatePhysics = raw.generatePhysics)
}
