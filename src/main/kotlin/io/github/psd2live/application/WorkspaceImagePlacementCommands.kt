package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.format.raster.RasterImage
import kotlin.math.roundToInt

internal object WorkspaceImagePlacementEdits {
    val supported = setOf("layer_set_bounds", "layer_cancel_import")

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, operation: WorkspaceDocumentOperation,
              checkCancelled: () -> Unit): WorkspaceDocument = when (operation.operation) {
        "layer_set_bounds" -> place(document, model, WorkspaceImageBounds.parse(operation.request), checkCancelled)
        "layer_cancel_import" -> cancel(document, model, operation.request.getValue("layer_ids").jsonArray.map { it.jsonPrimitive.content })
        else -> error("Unknown image placement operation")
    }

    private fun imported(document: WorkspaceDocument, id: String): SourceLayer {
        val layer = document.source.layers.singleOrNull { it.id.raw == id } ?: error("Imported layer not found")
        val metadata = layer as? WorkspaceSourceMetadata
        require(metadata?.derived == true && metadata.sourceAssetId == null &&
            (document.placementSource?.layers?.any { it.id.raw == id } == true || id.startsWith("import:"))) { "Only file-imported layers support bounds placement" }
        return layer
    }

    private fun place(document: WorkspaceDocument, model: RigPreviewModel, request: WorkspaceImageBounds,
                      checkCancelled: () -> Unit): WorkspaceDocument {
        checkCancelled()
        val layer = imported(document, request.layerId)
        val values = listOf(request.left, request.top, request.width, request.height)
        require(values.all { it.isFinite() && it.toDouble() >= Int.MIN_VALUE.toDouble() && it.toDouble() <= Int.MAX_VALUE.toDouble() }) { "Placement coordinates must be finite canvas pixels" }
        val width = request.width.roundToInt(); val height = request.height.roundToInt()
        val left = request.left.roundToInt(); val top = request.top.roundToInt()
        require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216) { "Placement requires positive dimensions of at most 16 megapixels" }
        require(left.toLong() + width <= Int.MAX_VALUE && top.toLong() + height <= Int.MAX_VALUE) { "Placement exceeds canvas coordinate range" }
        val name = request.name?.trim()?.also { require(it.isNotEmpty()) { "Layer name must not be blank" } } ?: layer.name
        val bounds = LayerBounds(left, top, width, height)
        if (bounds == layer.bounds && name == layer.name) return document
        WorkspaceLayerPlacementGuard.check(document, model, request.layerId)
        val original = document.placementSource?.layers?.singleOrNull { it.id == layer.id } ?: layer
        fun scaled(w: Int, h: Int): LayerRaster = if (w == original.raster.width && h == original.raster.height) original.raster else
            LayerRaster(w, h, LayerImport.scaleRgba(RasterImage(original.raster.width, original.raster.height, original.raster.rgba), w, h, checkCancelled))
        require(layer.raster.rgba.contentEquals(scaled(layer.bounds.width, layer.bounds.height).rgba)) { "Imported pixels were edited; finish placement before painting" }
        val moved = (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(name = name, bounds = bounds, raster = scaled(width, height))
        val source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx, document.source.layers.map { if (it.id == layer.id) moved else it }, document.source.groups)
        val originals = document.placementSource ?: WorkspaceSourceArt(source.widthPx, source.heightPx, emptyList(), emptyList())
        val frozen = WorkspaceLayerInsertionEdits.freeze(document, model).copy(source = source,
            placementSource = WorkspaceSourceArt(originals.widthPx, originals.heightPx,
                originals.layers + listOf(original).filterNot { l -> originals.layers.any { it.id == l.id } }, originals.groups),
            meshSource = document.meshSource?.let { mesh -> WorkspaceSourceArt(mesh.widthPx, mesh.heightPx,
                mesh.layers.filterNot { it.id == layer.id } + moved, mesh.groups) })
        val parent = model.rig.puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == request.layerId }.map { it.parentDeformerId?.raw }.distinct().single()
        return WorkspaceLayerInsertionEdits.materialize(WorkspaceLayerInsertionEdits.identities(frozen, model), model, setOf(request.layerId), parent, checkCancelled)
    }

    private fun cancel(document: WorkspaceDocument, model: RigPreviewModel, ids: List<String>): WorkspaceDocument {
        require(ids.size in 1..128 && ids.distinct().size == ids.size) { "Cancel requires 1..128 unique imported layer IDs" }
        if (ids.all { id -> document.source.layers.none { it.id.raw == id } }) {
            require(ids.all { it.startsWith("import:") }) { "Imported layer not found" }
            return document
        }
        ids.forEach { id ->
            imported(document, id)
            if (id !in document.deletedLayerIds) WorkspaceLayerPlacementGuard.check(document, model, id)
        }
        if (ids.all { it in document.deletedLayerIds }) return document
        val removed = ids.toSet()
        val remaining = document.source.layers.filterNot { it.id.raw in removed }
        // A legacy generation frame or the final source cannot be removed without changing other objects.
        if (remaining.isEmpty() || ids.any { it in document.rigEdits.splitBaselineLayerIds }) {
            return document.copy(deletedLayerIds = document.deletedLayerIds + removed,
                rigEdits = RigLayerDeletion.preserve(model, document.config()))
        }
        fun filter(source: SourceArt?) = source?.let { WorkspaceSourceArt(it.widthPx, it.heightPx, it.layers.filterNot { l -> l.id.raw in removed }, it.groups) }
        return document.copy(source = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            remaining.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, remaining.lastIndex - index) }, document.source.groups),
            generationSource = filter(document.generationSource), meshSource = filter(document.meshSource), placementSource = filter(document.placementSource),
            layerVisibility = document.layerVisibility - removed, layerOverrides = document.layerOverrides - removed,
            parentOverrides = document.parentOverrides - removed, meshOverrides = document.meshOverrides - removed,
            deletedLayerIds = document.deletedLayerIds - removed,
            rigEdits = document.rigEdits.copy(splitDrawableIds = document.rigEdits.splitDrawableIds - removed,
                authoringJournal = document.rigEdits.authoringJournal.filterNot {
                    it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP && it["layer_id"]?.jsonPrimitive?.content in removed
                }))
    }
}

internal class WorkspaceImagePlacementCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val commands = WorkspaceDocumentCommands(runtime)
    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String, author: MutationAuthor,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceLayerCommit {
        val before = runtime.capture()
        if (before.state != state) throw WorkspaceConflict(state, before.state)
        require(before.projectId == projectId) { "Operation targets another project" }
        val context = currentCoroutineContext(); val job = context[WorkspaceLayerJobExecution]
        job?.check(operation.operation); context.ensureActive()
        val result = commands.execute(projectId, state, summary, listOf(operation), author, beforeCommit = beforeCommit)
        val ids = if (operation.operation == "layer_set_bounds") listOf(operation.request.text("layer_id")) else operation.request.strings("layer_ids")
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, if (result.applied) ids else emptyList(), summary,
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        job?.committed(operation.operation, mutation.layerResult())
        return WorkspaceLayerCommit(result, mutation)
    }
}

internal fun WorkspaceImageBounds.operation() = WorkspaceDocumentOperation("layer_set_bounds", buildJsonObject {
    put("layer_id", layerId); put("left", left); put("top", top); put("width", width); put("height", height); name?.let { put("name", it) }
})
