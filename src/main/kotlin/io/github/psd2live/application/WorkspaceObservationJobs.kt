package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal object WorkspaceObservationJobs {
    const val motion = "view_sample_motion"

    suspend fun start(port: WorkspaceRenderPort, jobs: WorkspaceJobs, request: JsonObject): WorkspaceOperationOutput {
        val execution = requireNotNull(currentCoroutineContext()[WorkspaceExecution])
        val observation = port.captureObservation()
        val captured = observation.snapshot()
        execution.check(captured.projectId, captured.state)
        require(captured.loaded && captured.projectId != null) { "No workspace is loaded" }
        val job = jobs.start(motion, captured.projectId, captured.state, WorkspaceJobResultSchemas.result(motion)) {
            withContext(execution) {
                checkpoint()
                val result = observation.observeAuthoring(JsonObject(request + ("kind" to JsonPrimitive("motion"))))
                checkpoint()
                check(result.metadata["revisionId"]?.jsonPrimitive?.content == captured.revisionId) { "Observation returned another revision" }
                WorkspaceOperationOutput(JsonObject(result.metadata + buildJsonObject {
                    put("project_id", captured.projectId); put("state", captured.state); put("revision", captured.revisionId)
                }), result.images)
            }
        }
        return WorkspaceOperationOutput(job.toJson())
    }
}
