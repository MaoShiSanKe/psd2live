package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*

/** Synthetic torso, two arms and a tail, with the proposed armature committed as the first edit. */
internal suspend fun skeletonDraftFixture(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
    fun layer(id: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order,
        bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
        LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 90 }), null, null, false)
    val layers = listOf(
        layer("body", 0, LayerBounds(40, 30, 40, 70)),
        layer("arm_l", 1, LayerBounds(82, 34, 14, 56)),
        layer("arm_r", 2, LayerBounds(24, 34, 14, 56)),
        layer("tail", 3, LayerBounds(56, 96, 10, 24)),
    )
    val overrides = mapOf("body" to LayerClassificationOverride(SemanticTag.TOPWEAR, Side.NONE),
        "arm_l" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
        "arm_r" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.RIGHT),
        "tail" to LayerClassificationOverride(SemanticTag.TAIL, Side.NONE))
    val config = PipelineConfig(atlasSize = 512, meshOnly = false, meshSpacing = 8, generatePhysics = false, exportMoc3 = false,
        layerOverrides = overrides)
    val document = WorkspaceDocument(WorkspaceSourceArt(120, 140, layers, emptyList()), emptyMap(), emptySet(), overrides,
        emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
    val installed = runtime.install(runtime.state.value.state, "skeleton-project", document, WorkspacePreviewBuilder().build(document))
    return WorkspaceDocumentCommands(runtime).execute(installed.projectId, installed.state, "Propose skeleton",
        listOf(WorkspaceDocumentOperation("skeleton_auto", JsonObject(emptyMap()))), MutationAuthor.USER).capture
}
