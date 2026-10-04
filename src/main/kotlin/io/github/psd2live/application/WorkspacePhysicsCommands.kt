package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data class WorkspacePhysicsCommit(val commit: WorkspaceCommit<RigPreviewModel>,
    val mutation: WorkspaceMutationResult, val report: JsonObject)

/** File I/O, response fitting and the complete terminal result share one captured CAS boundary. */
internal class WorkspacePhysicsCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val observer: WorkspacePhysicsWork = WorkspacePhysicsWork.Direct,
    private val readFile: suspend (Path) -> String = { path -> runInterruptible(Dispatchers.IO) { Files.readString(path) } },
) {
    private val commands = WorkspaceDocumentCommands(runtime)

    /** GUI actions resolve IDs, ordering, presets and observed fitting inside the shared candidate. */
    suspend fun executeIntent(projectId: String, state: String, intent: WorkspacePhysicsIntent, summary: String,
                              author: MutationAuthor,
                              beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspacePhysicsCommit {
        val context = currentCoroutineContext()
        val before = runtime.capture()
        if (before.state != state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        val frozen = when (intent) {
            is WorkspacePhysicsIntent.Put -> WorkspacePhysicsIntent.Put(io.github.psd2live.core.RigPhysicsEdit.fromJson(intent.edit.toJson()))
            is WorkspacePhysicsIntent.Preset -> intent.copy(preset = io.github.psd2live.core.PhysicsPresets.Preset.fromJson(intent.preset.toJson()))
            is WorkspacePhysicsIntent.FitObserved -> intent.copy(peaks = intent.peaks.toMap())
            else -> intent
        }
        var prepared: WorkspaceDocumentOperation? = null
        val commit = commands.executeCandidate(projectId, state, summary, author, mutation = { document, model ->
            context.ensureActive()
            val operation = WorkspacePhysicsIntents.operation(document, model, frozen)
            prepared = operation
            WorkspacePhysicsEdits.apply(operation, document, model, observer.cancellable(context))
        }, beforeCommit = { captured, document, model ->
            context.ensureActive()
            beforeCommit(captured, document, model)
        })
        val id = prepared?.request?.get("id")?.jsonPrimitive?.contentOrNull
        val report = buildJsonObject { if (intent is WorkspacePhysicsIntent.Create && id != null) put("created", id) }
        val mutation = WorkspaceMutationResult(commit.capture.historyHead, commit.capture.revision, emptyList(), summary,
            affectedObjectIds = listOfNotNull(id?.let { "physics:$it" }), applied = commit.applied,
            state = commit.capture.state, projectId = commit.capture.projectId)
        return WorkspacePhysicsCommit(commit, mutation, report)
    }

    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, summary: String,
                        author: MutationAuthor, taskId: String? = null,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspacePhysicsCommit {
        require(operation.operation in WorkspacePhysicsEdits.supported) { "Not a physics document operation" }
        val context = currentCoroutineContext()
        val job = context[WorkspacePhysicsJobExecution]
        job?.check(operation.operation)
        val before = runtime.capture()
        if (before.state != state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        context.ensureActive()
        val text = if (operation.operation == "physics_import") {
            val path = Path.of(operation.request.getValue("path").jsonPrimitive.content)
            require(path.isAbsolute) { "path must be an absolute physics3.json" }
            context[WorkspaceJobContext]?.progress(0.05f, "Reading physics3.json")
            readFile(path).also { context.ensureActive() }
        } else null
        val work = observer.cancellable(context)
        var report = JsonObject(emptyMap())
        val commit = commands.executeCandidate(projectId, state, summary, author, taskId, mutation = { document, model ->
            val candidate = if (text != null) WorkspacePhysicsEdits.import(document, model, text)
                else WorkspacePhysicsCandidate(WorkspacePhysicsEdits.apply(operation, document, model, work))
            report = candidate.report
            context.ensureActive()
            context[WorkspaceJobContext]?.progress(0.8f, "Rebuilding physics candidate")
            candidate.document
        }, beforeCommit = { capture, document, model ->
            context.ensureActive()
            context[WorkspaceJobContext]?.progress(0.95f, "Committing physics candidate")
            beforeCommit(capture, document, model)
        })
        val mutation = WorkspaceMutationResult(commit.capture.historyHead, commit.capture.revision, emptyList(), summary,
            applied = commit.applied, state = commit.capture.state, projectId = commit.capture.projectId)
        // Record every field immediately after CAS, before a desktop refresh can fail or suspend.
        job?.committed(operation.operation, mutation.physicsResult(report))
        return WorkspacePhysicsCommit(commit, mutation, report)
    }
}

internal fun WorkspaceMutationResult.physicsResult(report: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
    put("state", requireNotNull(state)); put("project_id", requireNotNull(projectId)); put("history_node_id", historyNodeId)
    put("revision", revisionId); put("applied", applied)
    report.forEach { (key, value) -> put(key, value) }
}

internal class WorkspacePhysicsJobExecution(private val operation: String, private val completion: WorkspaceJobCompletion) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspacePhysicsJobExecution>
    fun check(actual: String) { require(actual == operation) { "A nested command cannot replace the active physics operation" } }
    fun committed(actual: String, result: JsonObject) { check(actual); completion.committed(WorkspaceOperationOutput(result)) }
}
