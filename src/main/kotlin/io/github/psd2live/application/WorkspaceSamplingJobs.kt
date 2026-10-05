package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal object WorkspaceSamplingJobs {
    val supported = setOf("simulation_simulate", "physics_simulate")

    /** The detached queries and their identity are captured before scheduling, never recaptured by the job. */
    suspend fun start(port: WorkspaceReadPort, jobs: WorkspaceJobs, operation: String, request: JsonObject): WorkspaceOperationOutput {
        require(operation in supported)
        val execution = requireNotNull(currentCoroutineContext()[WorkspaceExecution])
        val queries = port.captureQueries()
        val captured = queries.snapshot()
        execution.check(captured.projectId, captured.state)
        require(captured.loaded && captured.projectId != null) { "No workspace is loaded" }
        val started = jobs.start(operation, captured.projectId, captured.state, WorkspaceJobResultSchemas.result(operation)) {
            withContext(execution) {
                val context = currentCoroutineContext()
                val cancelled = { context.ensureActive(); false }
                val reportProgress = { value: Float ->
                    context.ensureActive(); progress(0.05f + 0.9f * value, "Sampling $operation")
                }
                checkpoint()
                val result = when (operation) {
                    "physics_simulate" -> queries.simulatePhysics(request, reportProgress, cancelled)
                    else -> queries.reportSimulation(request.getValue("id").jsonPrimitive.content,
                        request["hold"]?.jsonPrimitive?.float ?: 0.5f, request["release"]?.jsonPrimitive?.float ?: 1.5f,
                        request["wind"]?.jsonArray?.let { it[0].jsonPrimitive.float to it[1].jsonPrimitive.float }, reportProgress, cancelled)
                }
                checkpoint()
                WorkspaceOperationOutput(JsonObject(result + buildJsonObject {
                    put("project_id", captured.projectId); put("state", captured.state); put("revision", captured.revisionId)
                }))
            }
        }
        return WorkspaceOperationOutput(started.toJson())
    }
}
