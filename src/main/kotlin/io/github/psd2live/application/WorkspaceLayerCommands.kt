package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Soft deletion changes membership only; pixels and ordered authoring stay available for restoration. */
internal object WorkspaceLayerEdits {
    val supported = setOf("layer_soft_delete", "layer_restore")

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, operation: WorkspaceDocumentOperation): WorkspaceDocument {
        val known = document.source.layers.mapTo(HashSet()) { it.id.raw } + model.analysis.layers.map { it.source.id.raw } +
            document.deletedLayerIds
        return when (operation.operation) {
            "layer_soft_delete" -> {
                val id = operation.request.getValue("layer_id").jsonPrimitive.content
                require(id in known) { "Layer not found: $id" }
                if (id in document.deletedLayerIds) document else document.copy(deletedLayerIds = document.deletedLayerIds + id,
                    rigEdits = RigLayerDeletion.preserve(model, document.config()))
            }
            "layer_restore" -> {
                val ids = operation.request["layer_ids"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: document.deletedLayerIds.toList()
                require(ids.distinct().size == ids.size && ids.all { it in known }) { "Restore requires existing unique layer IDs" }
                if (ids.none { it in document.deletedLayerIds }) document else
                    document.copy(deletedLayerIds = document.deletedLayerIds - ids.toSet(),
                        rigEdits = RigLayerDeletion.preserve(model, document.config()))
            }
            else -> error("Not a layer membership operation")
        }
    }
}

internal data class WorkspaceLayerCommit(val commit: WorkspaceCommit<RigPreviewModel>, val mutation: WorkspaceMutationResult)

internal class WorkspaceLayerCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String,
        author: MutationAuthor, taskId: String? = null,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceLayerCommit {
        require(operation.operation in WorkspaceLayerEdits.supported)
        val context = currentCoroutineContext()
        val job = context[WorkspaceLayerJobExecution]
        job?.check(operation.operation)
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        context[WorkspaceJobContext]?.progress(0.1f, "Preparing layer membership")
        val result = commands.executeCandidate(projectId, state, summary, author, taskId, mutation = { document, model ->
            WorkspaceLayerEdits.apply(document, model, operation).also {
                context.ensureActive(); context[WorkspaceJobContext]?.progress(0.3f, "Rebuilding layer membership")
            }
        }, beforeCommit = { captured, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing layer membership")
            beforeCommit(captured, document, model)
        })
        val changed = (before.document.deletedLayerIds - result.capture.document.deletedLayerIds) +
            (result.capture.document.deletedLayerIds - before.document.deletedLayerIds)
        val mutation = WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, changed.toList(), summary,
            applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        // Retain the actual CAS result before a host refresh or a late cancellation can intervene.
        job?.committed(operation.operation, mutation.layerResult())
        return WorkspaceLayerCommit(result, mutation)
    }
}

internal fun WorkspaceMutationResult.layerResult() = buildJsonObject {
    put("state", requireNotNull(state)); put("project_id", requireNotNull(projectId)); put("history_node_id", historyNodeId)
    put("revision", revisionId); put("applied", applied)
    if (affectedLayerIds.isNotEmpty()) put("affectedLayerIds", JsonArray(affectedLayerIds.map(::JsonPrimitive)))
    if (affectedObjectIds.isNotEmpty()) put("affectedObjectIds", JsonArray(affectedObjectIds.map(::JsonPrimitive)))
}

internal class WorkspaceLayerJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceLayerJobExecution>
    fun check(actual: String) { require(actual == operation) { "A nested command cannot replace the active layer operation" } }
    fun committed(actual: String, result: JsonObject) { check(actual); completion.committed(WorkspaceOperationOutput(result)) }
}
