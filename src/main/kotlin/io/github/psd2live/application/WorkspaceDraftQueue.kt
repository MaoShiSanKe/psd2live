package io.github.psd2live.application

import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.coroutines.*

/** Ordered, process-owned editor completions. Only this queue's own commits can advance a draft's expectation. */
internal class WorkspaceDraftQueue<M>(
    private val runtime: WorkspaceRuntime<M>,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var tail: Entry? = null

    private inner class Entry(
        val projectId: String,
        val inputState: String,
        val document: WorkspaceDocument,
        val previous: Entry?,
    ) {
        lateinit var result: Deferred<WorkspaceCommit<M>>
        var lineage: Set<String> = emptySet()
        var completedState: String? = null
    }

    fun submit(projectId: String, state: String, document: WorkspaceDocument, summary: String,
               author: MutationAuthor,
               beforeCommit: (WorkspaceCapture<M>, WorkspaceDocument, M, WorkspaceDocument?) -> Unit,
               committed: (WorkspaceCommit<M>) -> Unit = {},
               prepare: suspend (WorkspaceDraft, M, WorkspaceDocument) -> WorkspaceDraft = { before, _, draft -> before.copy(document = draft) },
               auxiliary: (WorkspaceCapture<M>, WorkspaceDraft, M) -> Unit = { _, _, _ -> }): Deferred<WorkspaceCommit<M>> = synchronized(lock) {
        val previous = tail?.takeUnless { it.result.isCompleted }
        val entry = Entry(projectId, state, document, previous)
        entry.result = scope.async(start = CoroutineStart.LAZY) {
            val preceding = previous?.result?.await()
            val follows = preceding != null && previous.projectId == projectId && state in previous.lineage
            val expected = if (follows) preceding.capture.state else state
            // A draft may also move auxiliary data, such as the poses a settings switch released during the session.
            val result = runtime.executeDraft(projectId, expected, summary, author,
                listOf(WorkspaceDraftEdit { before, model -> prepare(before, model, document) })) { before, next, model ->
                val following = synchronized(lock) {
                    val newest = tail
                    var cursor = newest?.previous
                    while (cursor != null && cursor !== entry) cursor = cursor.previous
                    newest?.document?.takeIf { cursor === entry && newest.projectId == projectId }
                }
                beforeCommit(before, next.document, model, following)
                auxiliary(before, next, model)
            }
            entry.lineage = (if (follows) previous.lineage else emptySet()) + state + result.capture.state
            entry.completedState = result.capture.state
            committed(result)
            result
        }
        tail = entry
        entry.result.start()
        entry.result
    }

    /** Capture the current tail, including failures; callers never accidentally save a rejected draft. */
    suspend fun awaitIdle() {
        while (true) {
            val current = synchronized(lock) { tail }
            current?.result?.await()
            if (synchronized(lock) { tail === current }) return
        }
    }

    /** A subsequent GUI action may follow its own completed drafts, never an external edit. */
    suspend fun settleCommand(projectId: String, state: String): String {
        awaitIdle()
        return synchronized(lock) {
            val captured = runtime.capture()
            if (captured.projectId != projectId) throw WorkspaceConflict(state, captured.state)
            if (captured.state == state) return@synchronized state
            val last = tail
            if (last != null && last.projectId == projectId && last.result.isCompleted &&
                !last.result.isCancelled && state in last.lineage && captured.state == last.completedState) captured.state
            else throw WorkspaceConflict(state, captured.state)
        }
    }

    /** Other commands can repair a rejected draft; cancellation of their own wait still propagates. */
    suspend fun awaitSettled() {
        try { awaitIdle() }
        catch (_: Exception) { currentCoroutineContext().ensureActive() }
    }

    fun forgetRejected() = synchronized(lock) {
        if (tail?.result?.isCancelled == true) tail = null
    }

    /** Successful project installation ends the old draft chain, including any remembered failure. */
    fun discard() = synchronized(lock) {
        var current = tail
        tail = null
        while (current != null) {
            current.result.cancel()
            current = current.previous
        }
    }
}
