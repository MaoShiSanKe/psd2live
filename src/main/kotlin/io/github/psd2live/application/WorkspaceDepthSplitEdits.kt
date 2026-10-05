package io.github.psd2live.application

import io.github.psd2live.core.DepthSplit
import io.github.psd2live.core.RigLayerDeletion
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Depth slices clone authored motion in journal order, rather than repartitioning the mesh. */
internal object WorkspaceDepthSplitEdits {
    val supported = setOf("source_split_depth")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: io.github.psd2live.core.RigPreviewModel,
              work: WorkspaceRasterWork): WorkspaceDocument {
        val request = operation.request
        work.progress(0.05f, "Preparing depth slices")
        val frontId = request["front_layer_id"]?.jsonPrimitive?.content ?: "depth:${UUID.randomUUID()}"
        require(document.source.layers.none { it.id.raw == frontId }) { "Front layer ID already exists: $frontId" }
        val decoded = document.config()
        val config = if ("drawOrderOverrides" in document.settings) decoded else decoded.copy(drawOrderOverrides = model.config.drawOrderOverrides)
        val prepared = DepthSplit.prepare(model, config, request.getValue("source_id").jsonPrimitive.content,
            request.getValue("middle_ids").jsonArray.map { it.jsonPrimitive.content }, frontId,
            request["front_mesh_id"]?.jsonPrimitive?.content ?: "ArtMeshDepth_${UUID.randomUUID()}",
            request["glue_id"]?.jsonPrimitive?.content ?: "GlueDepth_${UUID.randomUUID()}",
            request["names"]?.jsonArray?.map { it.jsonPrimitive.content }, work::checkpoint)
        work.progress(0.75f, "Preserving depth slice bindings")
        val front = prepared.source.layers.single { it.id.raw == frontId }
        return document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            document.source.layers + front, document.source.groups), layerVisibility = prepared.config.layerVisibility,
            layerOverrides = prepared.config.layerOverrides, parentOverrides = prepared.config.parentOverrides,
            meshOverrides = prepared.config.meshOverrides, rigEdits = RigLayerDeletion.preserve(model, prepared.config),
            generationSource = if (prepared.config.rigEdits.importedCmo3 == null) document.generationSource else document.generationSource ?: document.source,
            settings = JsonObject(document.settings + ("drawOrderOverrides" to JsonObject(prepared.config.drawOrderOverrides.mapValues { JsonPrimitive(it.value) }))))
    }
}
