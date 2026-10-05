package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Process-owned placement: previews have no history, and only this session's commits advance its state. */
interface WorkspaceImagePlacement {
    fun preview(bounds: WorkspaceImageBounds): Deferred<Unit>
    fun commit(bounds: WorkspaceImageBounds, summary: String): Deferred<WorkspaceMutationResult>
    fun cancel(summary: String): Deferred<WorkspaceMutationResult>
    fun dismiss()
}

internal class WorkspaceImagePlacementSession(
    private val runtime: WorkspaceRuntime<RigPreviewModel>,
    initial: WorkspaceCapture<RigPreviewModel>,
    private val layerIds: List<String>,
    private val scope: CoroutineScope,
    private val projectPreview: (WorkspaceCapture<RigPreviewModel>, RigPreviewModel) -> Unit,
    private val beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit,
    private val committed: (WorkspaceCommit<RigPreviewModel>) -> Unit,
    private val build: suspend (WorkspaceDocument, RigPreviewModel) -> RigPreviewModel = { document, current -> WorkspacePreviewBuilder().build(document, current) },
) : WorkspaceImagePlacement {
    private val lock = Any()
    private var capture = initial
    private var serial = 0L
    private var closed = false
    private var previewJob: Deferred<Unit>? = null
    private var tail: Deferred<WorkspaceMutationResult>? = null
    private val commands = WorkspaceImagePlacementCommands(runtime)
    internal val finished: Boolean get() = synchronized(lock) { closed && tail?.isCompleted != false }

    init { require(layerIds.isNotEmpty() && layerIds.distinct().size == layerIds.size && layerIds.all { id -> initial.document.source.layers.any { it.id.raw == id } }) }

    override fun preview(bounds: WorkspaceImageBounds): Deferred<Unit> = synchronized(lock) {
        check(!closed) { "Placement session is closed" }; require(bounds.layerId in layerIds) { "Placement targets another import" }
        val version = ++serial
        previewJob?.cancel()
        val preceding = tail?.takeUnless { it.isCompleted }
        scope.async(start = CoroutineStart.LAZY) {
            preceding?.await()
            val base = synchronized(lock) { capture }
            val context = currentCoroutineContext()
            val document = runInterruptible(Dispatchers.Default) {
                WorkspaceImagePlacementEdits.apply(base.document, base.model, bounds.operation()) { context.ensureActive() }
            }
            val model = build(document, base.model)
            runtime.withCapture { current ->
                if (current.state != base.state) throw WorkspaceConflict(base.state, current.state)
                synchronized(lock) {
                    context.ensureActive()
                    if (!closed && serial == version) projectPreview(base, model)
                }
            }
        }.also { previewJob = it; it.start() }
    }

    override fun commit(bounds: WorkspaceImageBounds, summary: String): Deferred<WorkspaceMutationResult> {
        require(bounds.layerId in layerIds) { "Placement targets another import" }
        return submit(bounds.operation(), summary, false)
    }

    override fun cancel(summary: String): Deferred<WorkspaceMutationResult> = submit(WorkspaceDocumentOperation("layer_cancel_import",
        buildJsonObject { put("layer_ids", JsonArray(layerIds.map(::JsonPrimitive))) }), summary, true)

    private fun submit(operation: WorkspaceDocumentOperation, summary: String, finish: Boolean): Deferred<WorkspaceMutationResult> = synchronized(lock) {
        check(!closed) { "Placement session is closed" }
        ++serial; previewJob?.cancel()
        val preceding = tail?.takeUnless { it.isCompleted }
        scope.async(start = CoroutineStart.LAZY) {
            preceding?.await()
            val base = synchronized(lock) { capture }
            val result = commands.execute(base.projectId, base.state, operation, summary, MutationAuthor.USER, beforeCommit)
            // No suspension after CAS: a disconnected UI waiter cannot lose the session's lineage.
            synchronized(lock) { capture = result.commit.capture; if (finish) closed = true }
            committed(result.commit)
            result.mutation
        }.also { tail = it; it.start() }
    }

    internal suspend fun awaitIdle() {
        while (true) {
            val current = synchronized(lock) { tail }
            try { current?.await() } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!synchronized(lock) { closed }) throw failure
            }
            if (synchronized(lock) { current === tail }) return
        }
    }

    internal suspend fun awaitSettled() { try { awaitIdle() } catch (_: Exception) { currentCoroutineContext().ensureActive() } }

    override fun dismiss() {
        val base = synchronized(lock) { closed = true; ++serial; previewJob?.cancel(); capture }
        if (runtime.state.value.capture == null) return
        runtime.withCapture { current -> if (current.state == base.state) projectPreview(base, base.model) }
    }
}
