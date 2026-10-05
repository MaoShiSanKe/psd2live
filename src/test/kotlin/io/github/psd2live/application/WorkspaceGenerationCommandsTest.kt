package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceGenerationCommandsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun fixture(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }) =
        WorkspaceRuntime(rebuild).also { simulationFixture(it) }
    private fun operation(id: String) = WorkspaceDocumentOperation(id, buildJsonObject {
        when (id) {
            "settings_update" -> putJsonObject("changes") { put("meshEdgeMode", "TRIPLE") }
            "layer_mesh_update" -> { put("layer_id", "strip"); putJsonObject("changes") { put("outerMargin", 4) } }
            "layer_classify" -> { put("layer_id", "strip"); put("type", "toggle"); put("parameter", "Decoration") }
        }
    })
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        private val commands = WorkspaceGenerationCommands(runtime)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        private suspend fun run(operation: WorkspaceDocumentOperation, state: String): WorkspaceMutationResult {
            val result = commands.execute(runtime.capture().projectId, state, operation, "Generation", MutationAuthor.AGENT)
            after(); return result.mutation
        }
        override suspend fun updateProjectSettings(state: String, changes: JsonObject) =
            run(WorkspaceDocumentOperation("settings_update", buildJsonObject { put("changes", changes) }), state)
        override suspend fun setLayerMeshSettings(state: String, layerId: String, changes: JsonObject?, reset: Boolean) =
            run(WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
                put("layer_id", layerId); changes?.let { put("changes", it) }; put("reset", reset)
            }), state)
        override suspend fun classifyLayer(layerId: String, fields: JsonObject, expectedState: String) =
            run(WorkspaceDocumentOperation("layer_classify", JsonObject(fields + ("layer_id" to JsonPrimitive(layerId)))), expectedState)
    }
    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, operation: WorkspaceDocumentOperation) = JsonObject(operation.request + buildJsonObject {
        val c = runtime.capture(); put("project_id", c.projectId); put("state", c.state); put("request_id", "generation")
    })
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data

    @Test fun independentCommandsShareBatchCandidatesOmittedClassificationFieldsAndNoopHistory() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceGenerationCommands(runtime)
        for (id in WorkspaceGenerationCommands.supported) {
            val before = runtime.capture(); val count = runtime.history().selections.size
            val commit = commands.execute(before.projectId, before.state, operation(id), "Generation", MutationAuthor.USER).commit
            assertTrue(commit.applied); assertEquals(count + 1, runtime.history().selections.size)
            val noop = commands.execute(before.projectId, commit.capture.state, operation(id), "Same generation", MutationAuthor.USER)
            assertFalse(noop.commit.applied); assertEquals(commit.capture.state, noop.mutation.state)
            assertEquals(count + 1, runtime.history().selections.size)
        }
        val final = runtime.capture()
        val preparedDraft = commands.prepareDraft(root.document, root.model, final.document)
        assertEquals(final.revision, WorkspaceRevisions.of(preparedDraft))
        assertEquals(SemanticTag.OBJECTS, final.document.layerOverrides.getValue("strip").tag)
        assertTrue(final.model.rig.puppet.parameters.any { it.id.raw == "Decoration" })
        val replayed = builder.build(final.document)
        final.model.rig.puppet.drawables.forEach { mesh ->
            assertContentEquals(mesh.mesh!!.positions, replayed.rig.puppet.drawables.single { it.id == mesh.id }.mesh!!.positions)
        }
        runtime.checkout(final.projectId, final.state, root.historyHead)
        val reset = runtime.capture()
        val batch = WorkspaceDocumentCommands(runtime).execute(reset.projectId, reset.state, "Same generation batch",
            WorkspaceGenerationCommands.supported.map(::operation), MutationAuthor.AGENT).capture
        assertEquals(final.revision, batch.revision)
        assertEquals(root.document, runtime.history().selections.first().snapshot)
    }

    @Test fun staleInvalidAndRejectedProjectionNeverChangeTheCapturedDocumentOrHistory() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history(); val commands = WorkspaceGenerationCommands(runtime)
        assertFailsWith<WorkspaceConflict> { commands.execute(before.projectId, "stale", WorkspaceDocumentOperation("layer_classify", buildJsonObject {}), "Stale", MutationAuthor.USER) }
        assertFailsWith<IllegalArgumentException> { commands.execute(before.projectId, before.state,
            WorkspaceDocumentOperation("layer_classify", buildJsonObject { put("layer_id", "missing"); put("type", "toggle") }), "Missing", MutationAuthor.USER) }
        assertFailsWith<IllegalStateException> { commands.execute(before.projectId, before.state, operation("settings_update"), "Reject", MutationAuthor.USER) { _, _, _ -> error("Reject projection") } }
        val failure = assertFailsWith<WorkspaceBatchEditException> { WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Bad generation",
            listOf(operation("settings_update"), WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
                put("layer_id", "strip"); putJsonObject("changes") { put("outerMargin", -1) }
            })), MutationAuthor.AGENT) }
        assertEquals(1, failure.index); assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        assertFailsWith<IllegalArgumentException> { commands.prepareDraft(before.document, before.model,
            before.document.copy(settings = JsonObject(before.document.settings + ("meshOuterMargin" to JsonPrimitive(-1))))) }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun publicGenerationJobsCancelOrRejectConcurrentChangesDuringCandidateRebuild() = runBlocking<Unit> {
        for (id in WorkspaceGenerationCommands.supported) for (cancel in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var block = false
            val runtime = fixture { document -> if (block) { entered.complete(Unit); release.await() }; builder.build(document) }
            val before = runtime.capture(); val history = runtime.history(); block = true
            WorkspaceOperations(Host(runtime)).use { operations ->
                val request = input(runtime, operation(id)); val job = operations.registry.invoke(id, request, agent).data
                withTimeout(10000) { entered.await() }
                val expected = if (cancel) {
                    operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent); before
                } else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit)
                val result = operations.wait(job)
                assertEquals(if (cancel) "cancelled" else "failed", result.getValue("status").jsonPrimitive.content)
                if (!cancel) assertEquals("state_conflict", result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
                assertEquals(job.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id"))
            }
        }
    }

    @Test fun allThreeJobsKeepCompleteCommitResultsAfterLateCancellationOrRefreshFailure() = runBlocking<Unit> {
        for (id in WorkspaceGenerationCommands.supported) for (cancel in listOf(true, false)) {
            val runtime = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            WorkspaceOperations(Host(runtime, after = {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed")
            })).use { operations ->
                val definition = operations.registry.definition(id); assertTrue(definition.jobBacked && definition.batchable)
                val request = input(runtime, operation(id)); val job = operations.registry.invoke(id, request, agent).data
                withTimeout(10000) { entered.await() }
                if (cancel) operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent)
                release.complete(Unit)
                val terminal = operations.wait(job); assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
                val result = terminal.getValue("result").jsonObject; validateOperationSchema(result, definition.jobResultSchema!!)
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(runtime.capture().historyHead, result.getValue("history_node_id").jsonPrimitive.content)
                if (id == "layer_classify") assertEquals("strip", result.getValue("layer_id").jsonPrimitive.content)
                assertEquals(job.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id"))
                assertEquals(terminal, operations.wait(job))
            }
        }
    }

    @Test fun actualPipelineProgressRespondsToOriginalCoroutineCancellationBeforeCommit() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val completion = WorkspaceJobCompletion()
        val pending = async(Dispatchers.Default + WorkspaceGenerationJobExecution("settings_update", completion) + WorkspaceJobContext { fraction, _ ->
            if (fraction > 0.35f && fraction < 0.9f) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
        }) { WorkspaceGenerationCommands(runtime).execute(before.projectId, before.state, operation("settings_update"), "Cancelled generation", MutationAuthor.USER) }
        try {
            withTimeout(10000) { entered.await() }; pending.cancel(); release.countDown()
            assertFailsWith<CancellationException> { pending.await() }; pending.join()
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        } finally { release.countDown(); pending.cancelAndJoin() }
    }
}
