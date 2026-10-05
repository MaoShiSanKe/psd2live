package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data class WorkspaceSimulationCommit(
    val commit: WorkspaceCommit<RigPreviewModel>,
    val mutation: WorkspaceMutationResult,
    val report: JsonObject,
)

/** Single simulation commands share the batch candidates and retain a complete result at the CAS boundary. */
internal class WorkspaceSimulationCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
                                           private val observer: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct) {
    private val commands = WorkspaceDocumentCommands(runtime, observer)

    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String,
                        author: MutationAuthor, taskId: String? = null, autoBake: Boolean? = null,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceSimulationCommit {
        require(operation.operation in WorkspaceSimulationEdits.supported) { "Not a simulation document operation" }
        val context = currentCoroutineContext()
        val job = context[WorkspaceSimulationJobExecution]
        job?.check(operation.operation)
        val before = runtime.capture()
        val work = observer.cancellable(context)
        var report = JsonObject(emptyMap())
        val committed = commands.executeCandidate(projectId, state, summary, author, taskId, mutation = { document, preview ->
            val candidate = if (operation.operation == "simulation_put")
                WorkspaceSimulationEdits.put(document, preview, operation.request, autoBake, work)
            else WorkspaceSimulationEdits.apply(operation, document, preview, work)
            report = candidate.report
            candidate.document
        }, beforeCommit = beforeCommit)
        val mutation = WorkspaceDocumentCommands.mutationResult(before, committed, summary, listOf(operation))
        // No suspension or presentation refresh can lose a successful commit, including no-op results.
        job?.committed(operation.operation, JsonObject(mutation.compact() + report))
        return WorkspaceSimulationCommit(committed, mutation, report)
    }
}

/** Only the command for this public operation may claim its durable terminal result. */
internal class WorkspaceSimulationJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceSimulationJobExecution>
    fun check(actual: String) { require(actual == operation) { "A nested command cannot replace the active simulation operation" } }
    fun committed(actual: String, result: JsonObject) { check(actual); completion.committed(WorkspaceOperationOutput(result)) }
}
