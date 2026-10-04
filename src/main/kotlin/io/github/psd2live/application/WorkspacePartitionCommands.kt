package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Source partitions preserve the original layer and allocate stable identities in the candidate. */
internal object WorkspacePartitionEdits {
    val supported = setOf("source_split_polygon", "source_split_components")

    fun wouldDiscardEdits(model: RigPreviewModel, id: String): Boolean {
        val ids = model.rig.layerIdByDrawableId.filterValues { it == id }.keys + id
        val edits = model.config.rigEdits
        fun references(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.values.any(::references)
            is JsonArray -> value.any(::references)
            is JsonPrimitive -> value.isString && (value.content in ids ||
                ids.any { value.content in setOf("mesh:$it", "warp:$it", "rotation:$it") })
            else -> false
        }
        return edits.keyformSetEdits.any { it.target.id in ids } || edits.keyformDeleteEdits.any { it.target.id in ids } ||
            edits.keyformCopyEdits.any { it.sourceTarget.id in ids || it.destinationTarget.id in ids } ||
            edits.warpEdits.any { warp -> warp.meshIds.any { it in ids } } ||
            edits.authoringJournal.any(::references) || edits.structureEdits.any(::references) ||
            edits.simEdits.any { sim -> sim.targets.any { it in ids } } ||
            model.rig.puppet.glues.any { it.meshA.raw in ids || it.meshB.raw in ids }
    }

    fun componentPlan(model: RigPreviewModel, id: String, checkpoint: () -> Unit = {}): MeshComponentSplit.Plan? {
        if (id in model.config.deletedLayerIds) return null
        val source = model.analysis.source.layers.firstOrNull { it.id.raw == id }
            ?: model.analysis.layers.firstOrNull { it.source.id.raw == id }?.source ?: return null
        if (source.clipped || source.blend != LayerBlend.Normal || source.channelMask != ChannelMask.ALL) return null
        val drawable = model.rig.puppet.drawables.firstOrNull { it.id.raw == id || model.rig.layerIdByDrawableId[it.id.raw] == id }
            ?: return null
        return drawable.mesh?.let { mesh ->
            val canvas = Cmo3ModelImport.textureCanvas(model, drawable)
            val local = FloatArray(canvas.size) { index ->
                if (index % 2 == 0) (canvas[index] - source.bounds.left) * source.raster.width / source.bounds.width
                else (canvas[index] - source.bounds.top) * source.raster.height / source.bounds.height
            }
            MeshComponentSplit.detect(mesh, source, local, checkpoint)
        }
    }

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        require(operation.operation in supported)
        work.progress(0.05f, "Preparing source partition")
        val request = operation.request
        val id = request.getValue("layer_id").jsonPrimitive.content
        val migrate = model.config.rigEdits.importedCmo3 != null || MeshGenerationBaseline.present(document.rigEdits) || wouldDiscardEdits(model, id)
        val source = document.source.layers.firstOrNull { it.id.raw == id }
            ?: model.analysis.layers.firstOrNull { it.source.id.raw == id }?.source
            ?: throw IllegalArgumentException("Source layer not found: $id")
        require(id !in document.deletedLayerIds) { "Source layer is deleted: $id" }
        require(!source.clipped && source.blend == LayerBlend.Normal && source.channelMask == ChannelMask.ALL) {
            "Split requires a normal, unmasked source layer"
        }
        val names = request.getValue("names").jsonArray.map { it.jsonPrimitive.content.trim() }
        require(names.size >= 2 && names.all { it.isNotBlank() } && names.distinct().size == names.size) { "Name each piece once" }
        val pieceIds = request["piece_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (pieceIds != null) {
            val known = document.source.layers.map { it.id.raw } + model.analysis.layers.map { it.source.id.raw }
            require(pieceIds.size == names.size && pieceIds.distinct().size == pieceIds.size &&
                pieceIds.all { it.isNotBlank() && it !in known }) { "Piece IDs must be unique, new and match the names" }
        }
        val sides = request["sides"]?.jsonArray?.map { Side.valueOf(it.jsonPrimitive.content.uppercase()) }
            ?: List(names.size) { Side.NONE }
        require(sides.size == names.size) { "Provide one side per piece" }
        val componentPlan = if (operation.operation == "source_split_components")
            componentPlan(model, id, work::checkpoint) ?: throw IllegalArgumentException("Layer has no splittable mesh islands: $id") else null
        val pieces = if (operation.operation == "source_split_polygon") {
            require(names.size == 2) { "Polygon partition requires two names" }
            partitionSourcePolygon(source, request, work)
        } else {
            val plan = requireNotNull(componentPlan)
            require(plan.components.size == names.size) { "Names must match the current mesh island count: ${plan.components.size}" }
            plan.pieces(names, pieceIds, work::checkpoint)
        }
        work.progress(0.75f, "Preserving partition generation context")
        if (!migrate) return install(document, model, id, pieces, sides)
        val drawable = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == id || it.id.raw == id }
        val mesh = requireNotNull(drawable.mesh)
        val canvas = Cmo3ModelImport.textureCanvas(model, drawable)
        val geometry = if (componentPlan != null) SourcePartitionGeometry.components(mesh, canvas,
            componentPlan.ownerByVertex, pieces.size, work::checkpoint) else {
            SourcePartitionGeometry.polygon(mesh, canvas, request.getValue("polygon").jsonArray.map {
                val pair = it.jsonArray; pair[0].jsonPrimitive.double to pair[1].jsonPrimitive.double
            }, work::checkpoint)
        }
        val frozen = WorkspaceLayerInsertionEdits.freeze(document, model)
        var next = install(frozen, model, id, pieces, sides)
        val ids = pieces.map { next.rigEdits.splitDrawableIds.getValue(it.id.raw) }
        val command = SourcePartitionJournal.encode(model.rig.puppet, drawable.id, pieces.map { it.id.raw }, ids, names, geometry,
            textureSource = if (model.config.rigEdits.importedCmo3 == null) PuppetSourceAtlas.SOURCE_ID_RAW else Cmo3ModelImport.PAINT_SOURCE_ID,
            followCutVertices = componentPlan == null)
        val overlay = SourcePartitionJournal.migrateSimulations(
            MeshGenerationBaseline.preserve(next.rigEdits, model.config), model.rig.puppet, drawable.id.raw, ids, geometry)
        next = next.copy(rigEdits = overlay.copy(authoringJournal = overlay.authoringJournal + command,
                importedLayerIds = if (overlay.importedCmo3 == null) overlay.importedLayerIds else
                    overlay.importedLayerIds + ids.zip(pieces.map { it.id.raw })))
        work.checkpoint()
        return next
    }

    private fun install(document: WorkspaceDocument, model: RigPreviewModel, id: String,
                        pieces: List<WorkspaceSourceLayer>, sides: List<Side>): WorkspaceDocument {
        val original = document.source.layers.firstOrNull { it.id.raw == id }
            ?: model.analysis.layers.first { it.source.id.raw == id }.source
        val classification = document.layerOverrides[id] ?: model.analysis.layers.firstOrNull { it.source.id.raw == id }?.semantic?.let {
            LayerClassificationOverride(it.type, it.tag, it.side, it.parameter, it.switchId)
        } ?: LayerClassificationOverride()
        val ids = pieces.map { it.id.raw }
        val parent = if (id in document.parentOverrides) document.parentOverrides[id] else model.rig.puppet.drawables.firstOrNull {
            it.id.raw == id || model.rig.layerIdByDrawableId[it.id.raw] == id
        }?.parentDeformerId?.raw
        val owner = if (document.source.layers.any { it.id.raw == id }) id else document.source.layers
            .map { it.id.raw }.filter { id.startsWith("$it:") }.maxByOrNull(String::length)
        val layers = document.source.layers.flatMap { if (it.id.raw == owner) listOf(it) + pieces else listOf(it) }
            .let { if (owner == null) it + pieces else it }
        val nextSource = WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
            layers.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, layers.size - index) }, document.source.groups)
        val drawOrders = document.settings["drawOrderOverrides"]?.jsonObject.orEmpty() + ids.mapNotNull { next ->
            document.settings["drawOrderOverrides"]?.jsonObject?.get(id)?.let { next to it }
        }.toMap()
        val next = document.copy(source = nextSource, deletedLayerIds = document.deletedLayerIds + id,
            layerOverrides = document.layerOverrides + ids.mapIndexed { index, next ->
                next to classification.copy(side = sides[index].takeUnless { it == Side.NONE } ?: classification.side)
            },
            layerVisibility = document.layerVisibility + (id to false) + ids.associateWith { document.layerVisibility[id] ?: original.visible },
            parentOverrides = document.parentOverrides + ids.associateWith { parent },
            meshOverrides = document.meshOverrides + ids.mapNotNull { next -> document.meshOverrides[id]?.let { next to it } }.toMap(),
            settings = if (drawOrders.isEmpty()) document.settings else JsonObject(document.settings + ("drawOrderOverrides" to JsonObject(drawOrders))),
            rigEdits = document.rigEdits.copy(splitBaselineLayerIds = document.rigEdits.splitBaselineLayerIds.ifEmpty {
                (document.source.layers.map { it.id.raw }.filterNot { it in document.deletedLayerIds } + model.analysis.layers.map { it.source.id.raw }).toSet()
            }))
        // Allocate identities before rebuilding, so subsequent batch members and reopen use the same names.
        val existing = model.rig.layerIdByDrawableId.map { (drawable, layer) -> layer to DrawableId(drawable) }.toMap() +
            document.rigEdits.splitDrawableIds.mapValues { DrawableId(it.value) }
        val analysis = MouthLipLayers.prepare(RigGenerationSource.analyze(next.source, next.config()), next.config())
        val stable = RigBuilder.assignSplitDrawableIds(analysis, existing).mapValues { it.value.raw }
        return next.copy(rigEdits = next.rigEdits.copy(splitDrawableIds = stable))
    }
}

internal data class WorkspacePartitionCommit(val commit: WorkspaceCommit<RigPreviewModel>, val mutation: WorkspaceMutationResult)

internal class WorkspacePartitionCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val observer: WorkspaceRasterWork = WorkspaceRasterWork.Direct) {
    companion object { val supported = WorkspacePartitionEdits.supported + WorkspaceDepthSplitEdits.supported }
    private val commands = WorkspaceDocumentCommands(runtime, rasterWork = observer)

    suspend fun execute(projectId: String, state: String, operations: List<WorkspaceDocumentOperation>, summary: String,
        author: MutationAuthor,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspacePartitionCommit {
        require(operations.isNotEmpty() && operations.all { it.operation in supported })
        val context = currentCoroutineContext()
        val job = context[WorkspacePartitionJobExecution]
        job?.check(operations)
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        val project: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { capture, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing source partition")
            beforeCommit(capture, document, model)
        }
        val result = if (operations.size == 1) commands.executeCandidate(projectId, state, summary, author,
            mutation = { document, model -> WorkspaceDocumentEdits.apply(operations.single(), document, model,
                rasterWork = observer.cancellable(context) { fraction, message ->
                    context[WorkspaceJobContext]?.progress(0.05f + 0.2f * fraction, message)
                }) },
            beforeCommit = project)
        else commands.execute(projectId, state, summary, operations, author, beforeCommit = project)
        val oldIds = before.document.source.layers.mapTo(HashSet()) { it.id.raw }
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision,
            result.capture.document.source.layers.map { it.id.raw }.filterNot { it in oldIds }, summary,
            affectedObjectIds = WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet, result.capture.model.rig.puppet),
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        job?.committed(mutation.sourceResult())
        return WorkspacePartitionCommit(result, mutation)
    }
}

internal class WorkspacePartitionJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspacePartitionJobExecution>
    fun check(operations: List<WorkspaceDocumentOperation>) {
        require(operations.size == 1 && operations.single().operation == operation) { "A nested command cannot replace the active source partition" }
    }
    fun committed(result: JsonObject) { completion.committed(WorkspaceOperationOutput(result)) }
}
