package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Scalar requests are compiled once against the candidate; dense forms remain internal. */
internal object WorkspaceWarpControlEdits {
    val supported = setOf("warp_set_topology", "warp_bezier_divisions", "warp_bezier_anchor", "warp_bezier_handle", "warp_bezier_reset")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        require(operation.operation in supported)
        val request = operation.request
        validateOperationSchema(request, WorkspaceWarpControlSchemas.request(operation.operation, includeState = false), "request")
        val target = request.getValue("target").jsonPrimitive.content
        require(target.startsWith("warp:") && target.substringAfter(':').isNotBlank()) { "Use a warp:id handle" }
        val id = target.substringAfter(':')
        val command = if (operation.operation == "warp_set_topology") {
            val warp = model.rig.puppet.deformers.singleOrNull { it.id.raw == id } as? Deformer.Warp ?: error("Warp not found: $id")
            val rows = request.getValue("rows").jsonPrimitive.int; val columns = request.getValue("columns").jsonPrimitive.int
            if (rows == warp.rows && columns == warp.columns) return document
            RigWarpTopology.prepare(model.rig.puppet, id, rows, columns)
        } else {
            val result = RigBezierJournal.prepare(model.rig.puppet, document.rigEdits, operation.operation.removePrefix("warp_bezier_"), request)
            val old = document.rigEdits.authoringJournal.lastOrNull {
                it["op"]?.jsonPrimitive?.contentOrNull == RigBezierJournal.OP && it["id"] == result["id"] && it["key"] == result["key"]
            }
            if (old != null && old["basis"] == result["basis"] && old["controls"] == result["controls"] && result["basis"] == result["expected"]) return document
            result
        }
        return WorkspaceDocumentEdits.journal(document, model, JsonArray(listOf(command)))
    }
}

internal data class WorkspaceWarpControlCommit(val commit: WorkspaceCommit<RigPreviewModel>, val output: JsonObject)

internal class WorkspaceWarpControlCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private val documents = WorkspaceDocumentCommands(runtime)
    suspend fun execute(projectId: String, state: String, operation: WorkspaceDocumentOperation, author: MutationAuthor,
                        beforeCommit: (WorkspaceCapture<RigPreviewModel>, WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceWarpControlCommit {
        val context = currentCoroutineContext(); val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId)
        context.ensureActive(); context[WorkspaceJobContext]?.progress(0.1f, "Preparing Warp controls")
        val result = documents.execute(projectId, state, "Edit Warp controls", listOf(operation), author, beforeCommit = { capture, document, model ->
            context.ensureActive(); context[WorkspaceJobContext]?.progress(0.95f, "Committing Warp controls")
            beforeCommit(capture, document, model)
        })
        val mutation = WorkspaceDocumentCommands.mutationResult(before, result, "Edit Warp controls", listOf(operation))
        val output = JsonObject(mutation.compact() + buildJsonObject {
            put("target", operation.request.getValue("target"))
            if (operation.operation == "warp_set_topology") {
                val record = result.capture.document.rigEdits.authoringJournal.lastOrNull { it["op"]?.jsonPrimitive?.contentOrNull == RigWarpTopology.OP }
                put("max_surface_error", if (result.applied) requireNotNull(record).getValue("max_surface_error") else JsonPrimitive(0f))
                put("error_space", "parent_local"); put("probe_divisions", 64)
            }
        })
        context[WorkspaceWarpControlJobExecution]?.committed(WorkspaceOperationOutput(output))
        return WorkspaceWarpControlCommit(result, output)
    }
}

internal class WorkspaceWarpControlJobExecution(private val completion: WorkspaceJobCompletion) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceWarpControlJobExecution>
    fun committed(output: WorkspaceOperationOutput) = completion.committed(output)
}
