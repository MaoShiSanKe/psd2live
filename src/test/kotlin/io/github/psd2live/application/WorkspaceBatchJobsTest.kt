package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real candidate commands and jobs, with no GUI or MCP transport in the host. */
class WorkspaceBatchJobsTest {
    @TempDir lateinit var temporary: Path
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private val builder = WorkspacePreviewBuilder()
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, work: WorkspaceSimulationWork = WorkspaceSimulationWork.Direct,
                       val afterCommit: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        val commands = WorkspaceDocumentCommands(runtime, work)
        override fun snapshot(): WorkspaceProjectSnapshot {
            val capture = runtime.capture()
            return WorkspaceProjectSnapshot(capture.projectId, capture.revision, capture.historyHead, true, "Artwork",
                capture.document.source.widthPx, capture.document.source.heightPx, false, "Ready", null, emptyList(), emptyList(), state = capture.state)
        }
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            val result = commands.execute(before.projectId, state, summary, edits, author)
            afterCommit()
            return WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
        }
    }
    private suspend fun runtime(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val (png, _) = writeSourceImportFixture(temporary)
        val runtime = WorkspaceRuntime(rebuild)
        WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state,
            initialConfig = PipelineConfig(atlasSize = 256, meshOnly = true, exportMoc3 = false))
        return runtime
    }
    private fun edit(id: String, fields: JsonObject) = buildJsonObject { put("operation", id); put("request", fields) }
    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, vararg edits: JsonObject) = buildJsonObject {
        val capture = runtime.capture()
        put("request_id", "batch"); put("state", capture.state); put("project_id", capture.projectId); put("edits", JsonArray(edits.toList()))
    }
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })
    private suspend fun WorkspaceOperations.cancel(job: JsonObject) = call("job_cancel", buildJsonObject {
        put("request_id", "cancel"); put("id", job.getValue("id"))
    })

    @Test fun realSimulationBakeReportsItsBatchMemberAndCancellationKeepsTheWholeRoot() = runBlocking {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val before = simulationFixture(runtime); val history = runtime.history()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val observer = object : WorkspaceSimulationWork {
            override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({
                entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            }, { false })
        }
        WorkspaceOperations(Host(runtime, observer)).use { operations ->
            val edits = simulationDrivingEdits(before) + simulationPut(before) + WorkspaceDocumentOperation("simulation_bake",
                buildJsonObject { put("id", "sway") })
            val request = input(runtime, *edits.map { edit(it.operation, it.request) }.toTypedArray())
            val job = operations.call("workspace_apply_edits", request)
            try {
                withTimeout(5000) { entered.await() }
                val running = operations.call("job_get", buildJsonObject { put("id", job.getValue("id")) })
                assertEquals("running", running.getValue("status").jsonPrimitive.content)
                assertTrue(running.getValue("message").jsonPrimitive.content.contains("Baking simulation sway in edit 5/5"))
                assertTrue(running.getValue("progress").jsonPrimitive.float in 0.73f..0.88f)
                assertEquals(before, runtime.capture())
                operations.cancel(job); release.countDown()
                val cancelled = operations.wait(job)
                assertEquals("cancelled", cancelled.getValue("status").jsonPrimitive.content)
                assertEquals(job.getValue("id"), operations.call("workspace_apply_edits", request).getValue("id"))
                assertEquals(cancelled, operations.wait(job))
                assertEquals(before, runtime.capture())
                assertEquals(history, runtime.history())
            } finally { release.countDown() }
        }
    }

    @Test fun cancelDuringALaterRebuildPublishesNoPreparedPrefixAndReportsTheMember() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime { document ->
            if (document.rigEdits.authoringJournal.any { command -> command["edits"]?.jsonArray?.any { it.jsonObject["id"] == JsonPrimitive("Prepared") } == true }) { entered.complete(Unit); awaitCancellation() }
            builder.build(document)
        }
        val before = runtime.capture(); val history = runtime.history()
        WorkspaceOperations(Host(runtime)).use { operations ->
            val job = operations.call("workspace_apply_edits", input(runtime,
                edit("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } }),
                edit("parameter_create", buildJsonObject { put("parameter_id", "Prepared"); put("name", "Prepared") })))
            withTimeout(5000) { entered.await() }
            val running = operations.call("job_get", buildJsonObject { put("id", job.getValue("id")) })
            assertEquals(0.475f, running.getValue("progress").jsonPrimitive.float, 0.0001f)
            assertTrue(running.getValue("message").jsonPrimitive.content.contains("2/2: parameter_create"))
            assertEquals(before, runtime.capture())
            operations.cancel(job)
            assertEquals("cancelled", operations.wait(job).getValue("status").jsonPrimitive.content)
            assertEquals(before, runtime.capture())
            assertEquals(history, runtime.history())
        }
    }

    @Test fun concurrentEditMakesThePreparedJobFailWithItsOriginalExpectationAndRetryKeepsTheFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = runtime { document ->
            if (document.settings["headStrength"]?.jsonPrimitive?.float == 2f) { entered.complete(Unit); release.await() }
            builder.build(document)
        }
        val before = runtime.capture()
        WorkspaceOperations(Host(runtime)).use { operations ->
            val request = input(runtime, edit("settings_update", buildJsonObject { putJsonObject("changes") { put("headStrength", 2) } }))
            val job = operations.call("workspace_apply_edits", request)
            withTimeout(5000) { entered.await() }
            val foreign = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
            release.complete(Unit)
            val failed = operations.wait(job)
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            val error = failed.getValue("error").jsonObject
            assertEquals("state_conflict", error.getValue("code").jsonPrimitive.content)
            assertEquals(before.state, error.getValue("expected_state").jsonPrimitive.content)
            assertEquals(foreign.state, error.getValue("actual_state").jsonPrimitive.content)
            assertEquals(job.getValue("id"), operations.call("workspace_apply_edits", request).getValue("id"))
            assertEquals(failed, operations.wait(job))
            assertEquals(foreign, runtime.capture())
            assertEquals(1, runtime.history().selections.size)
        }
    }

    @Test fun lateCancellationAndPostCommitFailureKeepTheExactCommittedHandlesAndOneHistoryNode() = runBlocking {
        for (cancel in listOf(true, false)) {
            val committed = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = runtime()
            val before = runtime.capture()
            WorkspaceOperations(Host(runtime) {
                withContext(NonCancellable) { committed.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed after commit")
            }).use { operations ->
                val request = input(runtime, edit("canvas_rotation", buildJsonObject {
                    put("name", "Generated rotation"); putJsonArray("meshes") { add(before.model.rig.puppet.drawables.first().id.raw) }
                }))
                val job = operations.call("workspace_apply_edits", request)
                withTimeout(5000) { committed.await() }
                if (cancel) operations.cancel(job)
                release.complete(Unit)
                val completed = operations.wait(job)
                assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
                assertFalse("error" in completed)
                val result = completed.getValue("result").jsonObject
                assertEquals(1, result.getValue("edit_count").jsonPrimitive.int)
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(runtime.capture().historyHead, result.getValue("history_node_id").jsonPrimitive.content)
                assertTrue(result.getValue("applied").jsonPrimitive.boolean)
                val handle = result.getValue("changed").jsonArray.single().jsonPrimitive.content
                assertTrue(handle.startsWith("rotation:"))
                assertTrue(runtime.capture().model.rig.puppet.deformers.any { it.id.raw == handle.removePrefix("rotation:") })
                assertEquals(job.getValue("id"), operations.call("workspace_apply_edits", request).getValue("id"))
                assertEquals(completed, operations.wait(job))
                assertEquals(2, runtime.history().selections.size)
                assertEquals(before.historyHead, runtime.history().selections.last().node.parentId)
            }
        }
    }
}
