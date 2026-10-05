package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Raster work has no UI ownership; hosts can observe progress while cancellation checks stay shared. */
internal interface WorkspaceRasterWork {
    fun checkpoint()
    fun progress(fraction: Float, message: String)

    object Direct : WorkspaceRasterWork {
        override fun checkpoint() {}
        override fun progress(fraction: Float, message: String) {}
    }
}

internal fun WorkspaceRasterWork.cancellable(context: CoroutineContext,
    report: (Float, String) -> Unit = { fraction, message -> context[WorkspaceJobContext]?.progress(0.05f + 0.75f * fraction, message) },
): WorkspaceRasterWork {
    val observer = this
    return object : WorkspaceRasterWork {
        override fun checkpoint() { context.ensureActive(); observer.checkpoint(); context.ensureActive() }
        override fun progress(fraction: Float, message: String) {
            checkpoint(); observer.progress(fraction, message); checkpoint(); report(fraction, message)
        }
    }
}

internal data class WorkspaceRasterCommit(val commit: WorkspaceCommit<RigPreviewModel>, val mutation: WorkspaceMutationResult)

/** Captured GUI pixels and public gestures share preparation, rebuild/CAS and durable completion. */
internal class WorkspaceRasterCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val observer: WorkspaceRasterWork = WorkspaceRasterWork.Direct) {
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String,
        author: MutationAuthor, taskId: String? = null,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceRasterCommit {
        require(operation.operation in supported) { "Not a raster document operation" }
        val arguments = JsonObject(operation.request + ("mode" to JsonPrimitive(operation.operation.removePrefix("source_paint_"))))
        return submit(projectId, state, operation.operation, operation.request.getValue("layer_id").jsonPrimitive.content,
            summary, author, taskId, beforeCommit) { document, model, work -> WorkspaceRasterEdits.paint(document, model, arguments, work) }
    }

    suspend fun commitRaster(projectId: String, state: String, request: WorkspacePaintRaster, summary: String,
        author: MutationAuthor,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceRasterCommit =
        submit(projectId, state, null, request.layerId, summary, author, null, beforeCommit) { document, model, work ->
            WorkspaceRasterEdits.prepare(document, model, request, work)
        }

    private suspend fun submit(projectId: String, state: String, operation: String?, layerId: String, summary: String,
        author: MutationAuthor, taskId: String?,
        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit,
        prepare: (WorkspaceDocument, RigPreviewModel, WorkspaceRasterWork) -> WorkspaceDocument): WorkspaceRasterCommit {
        val context = currentCoroutineContext()
        val job = context[WorkspaceRasterJobExecution]
        if (job != null) job.check(requireNotNull(operation) { "Captured GUI pixels cannot replace a public raster job" })
        val before = runtime.capture()
        if (before.state != state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        val work = observer.cancellable(context)
        work.checkpoint()
        val committed = commands.executeCandidate(projectId, state, summary, author, taskId, mutation = { document, model ->
            prepare(document, model, work).also {
                work.checkpoint(); context[WorkspaceJobContext]?.progress(0.85f, "Rebuilding painted candidate")
            }
        }, beforeCommit = { capture, document, model ->
            work.checkpoint(); context[WorkspaceJobContext]?.progress(0.95f, "Committing painted candidate")
            beforeCommit(capture, document, model)
        })
        val mutation = WorkspaceMutationResult(committed.capture.historyHead, committed.capture.revision,
            if (committed.applied) listOf(layerId) else emptyList(), summary,
            affectedObjectIds = if (committed.applied) WorkspaceDocumentCommands.createdObjectIds(before.model.rig.puppet,
                committed.capture.model.rig.puppet) else emptyList(), applied = committed.applied,
            state = committed.capture.state, projectId = committed.capture.projectId)
        // Nothing suspends between the authoritative CAS and retaining all terminal fields/handles.
        job?.committed(requireNotNull(operation), mutation.compact())
        return WorkspaceRasterCommit(committed, mutation)
    }

    companion object {
        val supported = setOf("brush", "pencil", "eraser", "bucket", "shape", "clear").mapTo(linkedSetOf()) { "source_paint_$it" }
    }
}

internal class WorkspaceRasterJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceRasterJobExecution>
    fun check(actual: String) { require(actual == operation) { "A nested command cannot replace the active raster operation" } }
    fun committed(actual: String, result: JsonObject) { check(actual); completion.committed(WorkspaceOperationOutput(result)) }
}
