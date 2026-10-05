package io.github.psd2live.application

import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.RigPreviewModel
import kotlinx.serialization.json.JsonArray
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/** The loaded project, its open generation, and the workspace canvas a draft was started on. */
data class CanvasDraftScope(val projectId: String?, val openGeneration: Long, val workspaceId: String, val canvasId: String)

/** What confirming a draft asks for; the draft itself stays open until [WorkspaceCanvasInputDraft.settle]. */
sealed interface CanvasDraftSubmit {
    /** Journal records compiled against the captured model, to be written against the captured state. */
    class Write(val state: String, val preview: PuppetModel, val edits: JsonArray) : CanvasDraftSubmit
    /** The inputs change nothing; the draft is closed and no history node is written. */
    data object Unchanged : CanvasDraftSubmit
    /** Nothing was sent; the draft keeps its inputs and [WorkspaceCanvasInputDraft.failure] says why. */
    class Rejected(val failure: String) : CanvasDraftSubmit
}

/**
 * A canvas edit assembled from several inputs - placement drags, knife clicks, path points.
 *
 * Earlier inputs are coordinates and vertex indexes that only mean something against the rig, pose and mapping
 * the first one was read with, so all of that is captured once, at the start, and confirm writes against the
 * same state. A later workspace change is a conflict for the draft to report, never a reason to re-read the state:
 * the inputs stay, so the user can see what failed and cancel.
 *
 * [F] is whatever the caller resolved the inputs through (a target and its space mapping); it is kept, not read.
 */
class WorkspaceCanvasInputDraft<I, F>(
    val state: String,
    val scope: CanvasDraftScope,
    val model: RigPreviewModel,
    val pose: Map<ParameterId, Float>,
    val targetId: String,
    val frame: F,
    inputs: List<I> = emptyList(),
) {
    var inputs: List<I> = inputs
        private set
    var failure: String? = null
        private set
    var submitting = false
        private set
    var closed = false
        private set

    val open get() = !closed && !submitting

    fun append(input: I): Boolean {
        if (!open) return false
        inputs = inputs + input; failure = null
        return true
    }

    fun dropLast(): Boolean {
        if (!open || inputs.isEmpty()) return false
        inputs = inputs.dropLast(1); failure = null
        return true
    }

    /**
     * Compiles [commands] against the captured model. A different [current] scope means the canvas the inputs were
     * read on is gone; that is rejected here because the state alone cannot tell two canvases of one workspace apart.
     */
    fun submit(current: CanvasDraftScope, commands: JsonArray): CanvasDraftSubmit {
        check(open) { "Canvas draft is not open" }
        if (current != scope) return reject("The canvas changed since this edit was started")
        val (preview, records) = try { RigAuthoringJournal.compile(model.rig.puppet, commands) }
            catch (problem: Exception) { return reject(problem.message ?: "Invalid canvas edit") }
        if (records.isEmpty()) { close(); return CanvasDraftSubmit.Unchanged }
        submitting = true; failure = null
        return CanvasDraftSubmit.Write(state, preview, JsonArray(records))
    }

    /** Reports the write's outcome. Returns true when it committed and the draft is closed. */
    fun settle(problem: String?): Boolean {
        check(submitting) { "Canvas draft has no write in flight" }
        submitting = false
        if (problem != null) { failure = problem; return false }
        close()
        return true
    }

    fun cancel() {
        if (submitting) return
        close()
    }

    private fun reject(problem: String): CanvasDraftSubmit {
        failure = problem
        return CanvasDraftSubmit.Rejected(problem)
    }

    private fun close() { closed = true; inputs = emptyList() }
}
