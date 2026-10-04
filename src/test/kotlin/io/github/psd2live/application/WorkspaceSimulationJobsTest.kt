package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real single-operation candidates and process jobs, without desktop state or transport. */
class WorkspaceSimulationJobsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, work: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct,
                       val afterCommit: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        private val commands = WorkspaceSimulationCommands(runtime, work)
        override fun snapshot(): WorkspaceProjectSnapshot {
            val c = runtime.capture()
            return WorkspaceProjectSnapshot(c.projectId, c.revision, c.historyHead, true, "Strip", 64, 96, false, "Ready", null,
                emptyList(), emptyList(), state = c.state)
        }
        private suspend fun execute(id: String, state: String, request: JsonObject): Pair<WorkspaceMutationResult, JsonObject> {
            val result = commands.execute(runtime.capture().projectId, state, WorkspaceDocumentOperation(id, request), id, MutationAuthor.AGENT)
            afterCommit()
            return result.mutation to result.report
        }
        override suspend fun putSimulation(arguments: JsonObject, expectedState: String, taskId: String?, autoBake: Boolean?) =
            execute("simulation_put", expectedState, arguments)
        override suspend fun bakeSimulation(id: String, expectedState: String) =
            execute("simulation_bake", expectedState, buildJsonObject { put("id", id) })
    }
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Driving keys", simulationDrivingEdits(root), MutationAuthor.USER)
        return runtime
    }
    private fun request(runtime: WorkspaceRuntime<RigPreviewModel>, fields: JsonObject) = JsonObject(fields + buildJsonObject {
        val c = runtime.capture(); put("project_id", c.projectId); put("state", c.state); put("request_id", "single")
    })
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })
    private suspend fun WorkspaceOperations.cancel(job: JsonObject) = call("job_cancel", buildJsonObject { put("request_id", "cancel"); put("id", job.getValue("id")) })

    @Test fun cancellationOrConcurrentEditDuringRealAutoBakeCannotPublishTheCandidate() = runBlocking {
        for (cancel in listOf(true, false)) {
            val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val observer = object : WorkspaceSimulationWork {
                override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({
                    entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
                }, { false })
            }
            WorkspaceOperations(Host(runtime, observer)).use { operations ->
                val input = request(runtime, simulationPut(before, autoBake = true).request)
                val job = operations.call("simulation_put", input)
                try {
                    withTimeout(5000) { entered.await() }
                    val running = operations.call("job_get", buildJsonObject { put("id", job.getValue("id")) })
                    assertTrue(running.getValue("message").jsonPrimitive.content.contains("Baking simulation sway"))
                    val expected = if (cancel) { operations.cancel(job); before }
                        else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                    release.countDown()
                    val terminal = operations.wait(job)
                    assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                    if (!cancel) {
                        val error = terminal.getValue("error").jsonObject
                        assertEquals("state_conflict", error.getValue("code").jsonPrimitive.content)
                        assertEquals(before.state, error.getValue("expected_state").jsonPrimitive.content)
                        assertEquals(expected.state, error.getValue("actual_state").jsonPrimitive.content)
                    }
                    assertEquals(expected, runtime.capture())
                    assertEquals(history, runtime.history())
                    assertEquals(job.getValue("id"), operations.call("simulation_put", input).getValue("id"))
                    assertEquals(terminal, operations.wait(job))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun lateCancellationAndRefreshFailureRetainTheFullBakeDiagnosticsAndGeneratedHandles() = runBlocking {
        for (cancel in listOf(true, false)) {
            val runtime = fixture(); val c = runtime.capture()
            WorkspaceDocumentCommands(runtime).execute(c.projectId, c.state, "Unbaked simulation", listOf(simulationPut(c)), MutationAuthor.USER)
            val before = runtime.capture()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            WorkspaceOperations(Host(runtime) {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed after simulation commit")
            }).use { operations ->
                val input = request(runtime, buildJsonObject { put("id", "sway") })
                val job = operations.call("simulation_bake", input)
                withTimeout(10000) { entered.await() }
                if (cancel) operations.cancel(job)
                release.complete(Unit)
                val completed = operations.wait(job)
                assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
                val result = completed.getValue("result").jsonObject
                val after = runtime.capture()
                assertEquals(after.state, result.getValue("state").jsonPrimitive.content)
                assertEquals(after.historyHead, result.getValue("history_node_id").jsonPrimitive.content)
                val bake = assertNotNull(after.document.rigEdits.simEdits.single().bake)
                assertEquals(bake.summary().getValue("modes"), result.getValue("modes"))
                assertTrue(result.getValue("changed").jsonArray.any { it == JsonPrimitive("parameter:${bake.parameters.first()}") })
                assertEquals(before.historyHead, runtime.history().selections.last().node.parentId)
                assertEquals(4, runtime.history().selections.size)
                assertEquals(job.getValue("id"), operations.call("simulation_bake", input).getValue("id"))
                assertEquals(completed, operations.wait(job))
                validateOperationSchema(completed, requireNotNull(WorkspaceJobResultSchemas.operationOutput("simulation_bake")))
            }
        }
    }
}
