package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceMutationResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PuppetModel

/** The same document command boundary is used by desktop batches and independent application hosts. */
internal class WorkspaceDocumentCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
                                         private val simulationWork: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct,
                                         private val physicsWork: WorkspacePhysicsWork = WorkspacePhysicsWork.Direct,
                                         private val rasterWork: WorkspaceRasterWork = WorkspaceRasterWork.Direct,
                                         private val assetResources: ((WorkspaceCapture<RigPreviewModel>) -> WorkspaceAssetWorkflow)? = null) {
    private val previews = WorkspacePreviewBuilder()
    suspend fun execute(projectId: String, state: String, summary: String, edits: List<WorkspaceDocumentOperation>,
                        author: MutationAuthor, taskId: String? = null,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        require(edits.size in 1..128) { "Use 1..128 edits" }
        require(edits.all { it.operation in WorkspaceDocumentEdits.supported }) { "Batch contains an unsupported document operation" }
        val (before, resources) = runtime.withCapture { capture ->
            if (capture.state != state) throw WorkspaceConflict(state, capture.state)
            require(capture.projectId == projectId) { "Operation targets another project" }
            capture to if (edits.any { it.operation in WorkspaceAssetLayerEdits.supported && it.operation != "layer_finalize_placement" })
                requireNotNull(assetResources) { "Asset resources are required" }.invoke(capture) else null
        }
        val context = currentCoroutineContext()
        val batch = context[WorkspaceBatchJobExecution]
        require(batch == null || batch.edits == edits) { "A nested document command cannot replace the active batch" }
        val result = runtime.execute(projectId, state, summary, author,
            edits.mapIndexed { index, operation -> WorkspaceDocumentEdit { document, model ->
                batch?.preparing(index)
                val candidate = runInterruptible(Dispatchers.Default) { WorkspaceDocumentEdits.apply(operation, document, model, simulationWork.cancellable(context) { id, value ->
                    batch?.baking(index, id, value) ?: context[WorkspaceJobContext]?.progress(0.1f + 0.75f * value, "Baking simulation $id")
                }, physicsWork.cancellable(context) { id, fraction ->
                    batch?.fitting(index, id, fraction) ?: context[WorkspaceJobContext]?.progress(0.1f + 0.65f * fraction, "Fitting physics $id")
                }, rasterWork.cancellable(context) { fraction, message ->
                    batch?.painting(index, fraction, message) ?: context[WorkspaceJobContext]?.progress(0.05f + 0.75f * fraction, message)
                }, resources) }
                previews.normalizeMeshEdits(candidate, model)
            } },
            taskId = taskId, editFailure = { index, failure -> WorkspaceBatchEditException(index, edits[index].operation, failure) },
            beforeCommit = { captured, document, model ->
                batch?.committing()
                WorkspaceAssetLayerEdits.validate(captured.document, document, model)
                validateRegisteredNeutral(model, edits.filter { it.operation == "layer_set_bounds" }.mapTo(HashSet()) { it.request.getValue("layer_id").jsonPrimitive.content })
                beforeCommit(captured, document, model)
            })
        // No suspension between the authoritative CAS and retaining its complete public result.
        batch?.committed(mutationResult(before, result, summary, edits))
        return result
    }

    /** Materialized GUI gestures and public authoring operations use this same isolated journal draft. */
    suspend fun executeJournal(projectId: String, state: String, summary: String, edits: kotlinx.serialization.json.JsonArray,
                               author: MutationAuthor,
                               beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        executeCandidate(projectId, state, summary, author,
            mutation = { document, model -> WorkspaceDocumentEdits.journal(document, model, edits) }, beforeCommit = beforeCommit)

    /** Internal typed commands also use the same candidate/rebuild/CAS boundary as public batches. */
    suspend fun executeCandidate(projectId: String, state: String, summary: String, author: MutationAuthor,
                                 taskId: String? = null,
                                 mutation: (WorkspaceDocument, RigPreviewModel) -> WorkspaceDocument,
                                 beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        return runtime.execute(projectId, state, summary, author, listOf(WorkspaceDocumentEdit { document, model ->
            val candidate = runInterruptible(Dispatchers.Default) { mutation(document, model) }
            previews.normalizeMeshEdits(candidate, model)
        }), taskId = taskId, beforeCommit = beforeCommit)
    }

    companion object {
        fun mutationResult(before: WorkspaceCapture<RigPreviewModel>, result: WorkspaceCommit<RigPreviewModel>,
                           summary: String, edits: List<WorkspaceDocumentOperation>): WorkspaceMutationResult {
            val changed = if (!result.applied) emptyList() else edits.flatMap { edit ->
                listOf("layer_id", "parameter_id", "target", "id", "path_id").mapNotNull { edit.request[it]?.jsonPrimitive?.contentOrNull }
            }.plus((before.document.deletedLayerIds - result.capture.document.deletedLayerIds) +
                (result.capture.document.deletedLayerIds - before.document.deletedLayerIds))
                .plus(createdObjectIds(before.model.rig.puppet, result.capture.model.rig.puppet)).distinct()
                .plus(result.capture.document.source.layers.map { it.id.raw }.filterNot { id -> before.document.source.layers.any { it.id.raw == id } }
                    .map { "layer:$it" })
                .plus(before.document.source.layers.map { it.id.raw }.filterNot { id -> result.capture.document.source.layers.any { it.id.raw == id } }
                    .map { "layer:$it" }).distinct()
            return WorkspaceMutationResult(result.capture.historyHead, result.capture.revision, emptyList(), summary,
                affectedObjectIds = changed, applied = result.applied, state = result.capture.state, projectId = result.capture.projectId)
        }

        /** Stable handles include generated IDs even when a request omitted an optional ID. */
        fun createdObjectIds(before: PuppetModel, after: PuppetModel): List<String> {
            fun ids(puppet: PuppetModel): Set<String> = buildSet {
                puppet.drawables.forEach { add("mesh:${it.id.raw}") }
                puppet.deformers.forEach { add("${if (it is Deformer.Warp) "warp" else "rotation"}:${it.id.raw}") }
                puppet.glues.forEach { it.id?.let { id -> add("glue:$id") } }
                puppet.deformPaths.forEach { add("path:${it.id}") }
                puppet.parameters.forEach { add("parameter:${it.id.raw}") }
            }
            return (ids(after) - ids(before)).toList()
        }
    }
}
