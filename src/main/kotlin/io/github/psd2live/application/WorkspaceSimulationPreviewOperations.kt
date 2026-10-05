package io.github.psd2live.application

import io.github.psd2live.core.Bounds
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal object WorkspaceSimulationPreviewSchemas {
    private val s = WorkspaceResultSchema
    private val values = s.dictionary(s.number())
    val result = s.obj(s.identity + mapOf("workspace_id" to s.handle(), "session_id" to s.handle(), "simulation_id" to s.handle(),
        "status" to s.choices("running", "stopped"), "stale" to s.boolean(), "serial" to s.integer(0), "elapsed" to s.number(0),
        "notes" to s.array(s.string()), "values" to values, "vertex_counts" to s.dictionary(s.integer(0)), "positions" to s.dictionary(s.array(s.number()))))
    val control = s.union(listOf(
        s.obj(mapOf("mode" to s.constant("start"), "state" to s.handle(), "simulation_id" to s.handle(), "values" to values),
            setOf("mode", "state", "simulation_id")),
        s.obj(mapOf("mode" to s.constant("restart"), "state" to s.handle(), "session_id" to s.handle(), "values" to values),
            setOf("mode", "state", "session_id")),
        s.obj(mapOf("mode" to s.constant("stop"), "state" to s.handle(), "session_id" to s.handle())),
    ))
    val step = s.obj(mapOf("state" to s.handle(), "session_id" to s.handle(), "dt" to s.number(0.000001, 0.1),
        "steps" to s.integer(1, 240), "values" to values), setOf("state", "session_id", "dt"))
}

/** Continuous reference simulation is a session; fixed training reports remain separate read queries. */
internal fun registerSimulationPreviewOperations(registry: WorkspaceOperationRegistry, read: WorkspaceReadPort,
                                                 port: WorkspaceSimulationPreviewPort, jobs: WorkspaceJobs) {
    val s = WorkspaceResultSchema
    for ((id, schema) in mapOf("simulation_preview" to WorkspaceSimulationPreviewSchemas.control,
        "simulation_preview_step" to WorkspaceSimulationPreviewSchemas.step)) {
        registry.register(WorkspaceOperationDefinition(id,
            if (id == "simulation_preview") "Start, stop or restart a calibrated live simulation preview. Captures the committed rig and keeps its scene, clock and frames outside project history and saved poses. Preparation is cancellable. Use get/render to observe the same reference geometry as the GUI."
            else "Advance a running simulation preview with an explicit positive dt (at most 0.1 seconds) for 1..240 steps. Optional parameter values drive this transient frame; simulation bake modes are held at rest. Frame requests are deduplicated and cancelled solves restore their previous scene. Document changes and project reloads invalidate the scene; authored pose changes may drive it.",
            schema, WorkspaceOperationKind.SESSION, jobBacked = true, workspaceBound = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)), jobResultSchema = WorkspaceSimulationPreviewSchemas.result)) { request, _ ->
            startWorkspaceOperationJob(read, jobs, id) {
                val completion = requireNotNull(currentCoroutineContext()[WorkspaceJobCompletion])
                withContext(WorkspaceSimulationPreviewJobExecution(completion)) {
                    val result = if (id == "simulation_preview") port.controlSimulationPreview(request) else port.stepSimulationPreview(request)
                    WorkspaceOperationOutput(result.report)
                }
            }
        }
    }
    registry.register(WorkspaceOperationDefinition("simulation_preview_get", "Read a copied live simulation frame, pose, elapsed time, per-mesh vertex counts, notes and stale status. include_positions=true returns world X/Y arrays; default positions is empty to keep responses compact. Querying does not advance the clock; stopped sessions retain their last frame.",
        s.obj(mapOf("session_id" to s.handle(), "include_positions" to s.boolean()), setOf("session_id")), WorkspaceOperationKind.QUERY,
        resultSchema = WorkspaceSimulationPreviewSchemas.result)) { request, _ ->
        val preview = port.simulationPreviewFrame(request.getValue("session_id").jsonPrimitive.content)
        val data = if (request["include_positions"]?.jsonPrimitive?.boolean != true) preview.report else JsonObject(preview.report + buildJsonObject {
            putJsonObject("positions") { preview.frame.positions.forEach { (id, vertices) -> put(id.raw, JsonArray(vertices.map(::JsonPrimitive))) } }
        })
        WorkspaceOperationOutput(data)
    }
    registry.register(WorkspaceOperationDefinition("simulation_preview_render", "Render the latest live simulation world vertices at its evaluated pose into a fixed canvas-pixel bounds rectangle. This is the same software geometry the GUI shows and does not change the clock or committed model.",
        s.obj(mapOf("session_id" to s.handle(), "bounds" to s.vector(4), "size" to s.integer(128, 2048)), setOf("session_id", "bounds")),
        WorkspaceOperationKind.QUERY, resultSchema = requireNotNull(WorkspaceAuthoringResultSchemas.forOperation("swing_preview_render")))) { request, _ ->
        val bounds = request.getValue("bounds").jsonArray.map { it.jsonPrimitive.float }
        val view = port.renderSimulationPreview(request.getValue("session_id").jsonPrimitive.content, WorkspaceModelViewRequest(
            frame = WorkspaceViewFrame.CanvasRect(Bounds(bounds[0], bounds[1], bounds[2], bounds[3])),
            output = WorkspaceViewOutputSpec(request["size"]?.jsonPrimitive?.int ?: 512)))
        WorkspaceOperationOutput(view.toJson(), listOf(view.png))
    }
}
