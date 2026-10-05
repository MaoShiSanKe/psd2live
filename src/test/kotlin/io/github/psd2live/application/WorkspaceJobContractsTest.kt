package io.github.psd2live.application

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*

class WorkspaceJobContractsTest {
    private val operation = "project_export_psd"
    private fun output() = WorkspaceOperationOutput(buildJsonObject {
        put("state", "load:0:0"); put("path", "output.psd"); put("bytes", 42); put("layers", 2)
    })

    @Test fun statusBranchesRejectLeakedResultsContradictoryErrorsAndWrongOperationResults() {
        val schema = WorkspaceJobResultSchemas.snapshot()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val queued = WorkspaceJobSnapshot("job", operation, null, "unloaded", WorkspaceJobStatus.QUEUED, 0f, "Queued", now, now)
        validateOperationSchema(queued.toJson(), schema)
        val completed = queued.copy(status = WorkspaceJobStatus.COMPLETED, progress = 1f, result = output())
        validateOperationSchema(completed.toJson(), schema)
        val failure = WorkspaceFailure.from(java.io.IOException("Cannot write output"))
        validateOperationSchema(queued.copy(status = WorkspaceJobStatus.FAILED, error = failure).toJson(), schema)
        for (invalid in listOf(queued.copy(result = output()), completed.copy(error = failure),
            completed.copy(operation = "project_export_model"), completed.copy(result = null),
            queued.copy(status = WorkspaceJobStatus.FAILED), queued.copy(status = WorkspaceJobStatus.CANCELLED, error = failure))) {
            assertFailsWith<WorkspaceValidationException> { validateOperationSchema(invalid.toJson(), schema) }
        }
        val summaries = WorkspaceJobResultSchemas.snapshot(includeResult = false)
        validateOperationSchema(completed.toJson(includeResult = false), summaries)
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(completed.toJson(), summaries) }
    }

    @Test fun backgroundResultValidationReportsInternalContractFailureBeforePublishingSuccess() = runBlocking<Unit> {
        WorkspaceJobs().use { jobs ->
            val job = jobs.start(operation, "project", "input", WorkspaceJobResultSchemas.result(operation)) {
                WorkspaceOperationOutput(buildJsonObject { put("state", "input"); put("path", "output.psd"); put("bytes", "wrong"); put("layers", 1) })
            }
            val failed = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.FAILED, failed.status)
            assertNull(failed.result)
            assertEquals("output_contract", failed.error!!.code)
            assertEquals("result.bytes", failed.error.details.getValue("field").jsonPrimitive.content)
            validateOperationSchema(failed.toJson(), WorkspaceJobResultSchemas.snapshot(operation))
        }
    }

    @Test fun durableCompletionIsValidatedAndRetainedWhenLaterWorkIsCancelled() = runBlocking<Unit> {
        WorkspaceJobs().use { jobs ->
            val committed = CompletableDeferred<Unit>()
            val job = jobs.start(operation, "project", "input", WorkspaceJobResultSchemas.result(operation)) {
                currentCoroutineContext()[WorkspaceJobCompletion]!!.committed(output())
                committed.complete(Unit)
                awaitCancellation()
            }
            committed.await()
            jobs.cancel(job.id)
            val completed = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, completed.status)
            assertEquals(output(), completed.result)
            validateOperationSchema(completed.toJson(), WorkspaceJobResultSchemas.snapshot(operation))
        }
    }
}
