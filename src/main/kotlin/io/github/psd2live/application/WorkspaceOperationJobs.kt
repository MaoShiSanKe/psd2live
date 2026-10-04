package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Every background operation inherits the trusted expectation and shares the process-owned runner. */
internal suspend fun startWorkspaceOperationJob(workspace: WorkspaceStatePort, jobs: WorkspaceJobs, operation: String,
                                               allowUnloaded: Boolean = false,
                                               action: suspend () -> WorkspaceOperationOutput): WorkspaceOperationOutput {
    val execution = requireNotNull(currentCoroutineContext()[WorkspaceExecution])
    val captured = workspace.snapshot()
    execution.check(captured.projectId, captured.state)
    require(allowUnloaded || captured.loaded && captured.projectId != null) { "No workspace is loaded" }
    val started = jobs.start(operation, captured.projectId, captured.state, WorkspaceJobResultSchemas.result(operation)) {
        checkpoint()
        progress(0f, "Preparing operation")
        withContext(execution) { action() }
    }
    return WorkspaceOperationOutput(started.toJson(), started.result?.images.orEmpty())
}
