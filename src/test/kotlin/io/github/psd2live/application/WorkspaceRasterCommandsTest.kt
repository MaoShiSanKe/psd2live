package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceRasterCommandsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun fixture() = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }).also { simulationFixture(it) }
    private fun fields(mode: String, rebuild: Boolean = false): JsonObject = buildJsonObject {
        put("layer_id", "strip"); put("rebuild_mesh", rebuild)
        if (mode != "clear") put("color", JsonArray(listOf(30, 120, 220, 255).map(::JsonPrimitive)))
        when (mode) {
            "brush", "pencil", "eraser" -> { put("radius", 8); putJsonArray("points") { add(buildJsonArray { add(6); add(20) }) } }
            "bucket" -> put("point", buildJsonArray { add(40); add(40) })
            "shape" -> {
                put("shape", "rectangle"); put("filled", true)
                put("from", buildJsonArray { add(4); add(12) }); put("to", buildJsonArray { add(60); add(82) })
            }
        }
    }
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val work: WorkspaceRasterWork = WorkspaceRasterWork.Direct,
        val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        private val commands = WorkspaceRasterCommands(runtime, work)
        private val batch = WorkspaceDocumentCommands(runtime, rasterWork = work)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override suspend fun paintSource(arguments: JsonObject): WorkspaceMutationResult {
            val mode = arguments.getValue("mode").jsonPrimitive.content
            val result = commands.execute(runtime.capture().projectId, arguments.getValue("state").jsonPrimitive.content,
                WorkspaceDocumentOperation("source_paint_$mode", JsonObject(arguments - "mode")), mode, MutationAuthor.AGENT)
            after()
            return result.mutation
        }
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            val result = batch.execute(before.projectId, state, summary, edits, author)
            after()
            return WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
        }
    }
    private fun request(runtime: WorkspaceRuntime<RigPreviewModel>, fields: JsonObject, id: String = "raster") = JsonObject(fields + buildJsonObject {
        val c = runtime.capture(); put("project_id", c.projectId); put("state", c.state); put("request_id", id)
    })
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })
    private suspend fun WorkspaceOperations.cancel(job: JsonObject) = call("job_cancel", buildJsonObject { put("request_id", "cancel"); put("id", job.getValue("id")) })

    @Test fun capturedGuiPixelsAndPublicJobProduceTheSameDurableCandidateAndNoopKeepsHistory() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val gesture = JsonObject(fields("shape", true) + ("mode" to JsonPrimitive("shape")))
        val captured = WorkspacePaintRaster.capture("strip", before.document.paintSourceImage(gesture), true)
        val gui = WorkspaceRasterCommands(runtime).commitRaster(before.projectId, before.state, captured, "GUI paint", MutationAuthor.USER)
        assertTrue(gui.commit.applied)
        assertEquals("user", runtime.history().selections.last().node.actor)
        runtime.checkout(before.projectId, gui.commit.capture.state, before.historyHead)
        WorkspaceOperations(Host(runtime)).use { operations ->
            val input = request(runtime, fields("shape", true))
            val started = operations.call("source_paint_shape", input)
            val terminal = operations.wait(started)
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
            val result = terminal.getValue("result").jsonObject
            validateOperationSchema(result, WorkspaceJobResultSchemas.result("source_paint_shape"))
            assertEquals(gui.commit.capture.revision, runtime.capture().revision)
            assertEquals("agent", runtime.history().selections.last().node.actor)
            assertEquals(3, runtime.history().selections.size)
            assertEquals(started.getValue("id"), operations.call("source_paint_shape", input).getValue("id"))
            val repeated = operations.call("source_paint_shape", request(runtime, fields("shape", true), "same-pixels"))
            val repeatedResult = operations.wait(repeated).getValue("result").jsonObject
            assertEquals(false, repeatedResult.getValue("applied").jsonPrimitive.boolean)
            assertEquals(result.getValue("state"), repeatedResult.getValue("state"))
            assertEquals(3, runtime.history().selections.size)
            for (id in WorkspaceRasterCommands.supported) {
                val definition = operations.registry.definition(id)
                assertTrue(definition.jobBacked && definition.batchable && definition.workspaceBound && definition.idempotent)
                assertEquals(WorkspaceAuthoringResultSchemas.forOperation(id), definition.jobResultSchema)
            }
        }
    }

    @Test fun cancellationInsideRasterizationOrCroppingAndForeignStateChangesKeepTheOriginalDocument() = runBlocking<Unit> {
        for (mode in listOf("bucket", "shape")) for (cancel in listOf(true, false)) {
            val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val phase = if (mode == "bucket") "Rasterizing paint gesture" else "Cropping painted source"
            val work = object : WorkspaceRasterWork {
                private var active = false; private var checks = 0
                override fun progress(fraction: Float, message: String) { active = message == phase }
                override fun checkpoint() {
                    if (active && ++checks == 4 && !entered.isCompleted) {
                        entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            WorkspaceOperations(Host(runtime, work)).use { operations ->
                val id = "source_paint_$mode"; val input = request(runtime, fields(mode, true))
                val started = operations.call(id, input)
                try {
                    withTimeout(10000) { entered.await() }
                    val running = operations.call("job_get", buildJsonObject { put("id", started.getValue("id")) })
                    assertTrue(running.getValue("progress").jsonPrimitive.float > 0)
                    val expected = if (cancel) { operations.cancel(started); before }
                        else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                    release.countDown()
                    val terminal = operations.wait(started)
                    assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                    if (!cancel) {
                        val error = terminal.getValue("error").jsonObject
                        assertEquals("state_conflict", error.getValue("code").jsonPrimitive.content)
                        assertEquals(before.state, error.getValue("expected_state").jsonPrimitive.content)
                        assertEquals(expected.state, error.getValue("actual_state").jsonPrimitive.content)
                    }
                    assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
                    assertEquals(started.getValue("id"), operations.call(id, input).getValue("id"))
                    assertEquals(terminal, operations.wait(started))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun everyPaintModeRetainsItsTerminalResultAfterLateCancellationOrRefreshFailure() = runBlocking<Unit> {
        for (mode in listOf("brush", "pencil", "eraser", "bucket", "shape", "clear")) for (cancel in listOf(true, false)) {
            val runtime = fixture()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            WorkspaceOperations(Host(runtime, after = {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed after paint commit")
            })).use { operations ->
                val id = "source_paint_$mode"; val input = request(runtime, fields(mode, mode == "shape"))
                val started = operations.call(id, input)
                withTimeout(10000) { entered.await() }
                if (cancel) operations.cancel(started)
                release.complete(Unit)
                val terminal = operations.wait(started)
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                val result = terminal.getValue("result").jsonObject
                validateOperationSchema(result, WorkspaceJobResultSchemas.result(id))
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(runtime.capture().historyHead, result.getValue("history_node_id").jsonPrimitive.content)
                assertEquals(2, runtime.history().selections.size)
                assertEquals(started.getValue("id"), operations.call(id, input).getValue("id"))
                assertEquals(terminal, operations.wait(started))
            }
        }
    }

    @Test fun staleStateAndRejectedProjectionNeverPublishPixelsOrStartPreparation() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        var checks = 0
        val work = object : WorkspaceRasterWork {
            override fun checkpoint() { checks++ }
            override fun progress(fraction: Float, message: String) {}
        }
        val commands = WorkspaceRasterCommands(runtime, work)
        val operation = WorkspaceDocumentOperation("source_paint_shape", fields("shape", true))
        assertFailsWith<WorkspaceConflict> { commands.execute(before.projectId, "stale", operation, "Paint", MutationAuthor.USER) }
        assertEquals(0, checks)
        assertFailsWith<IllegalStateException> { commands.execute(before.projectId, before.state, operation, "Paint", MutationAuthor.USER) { _, _, _ -> error("Projection rejected") } }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun paintingInAtomicBatchChecksCancellationDuringTheRasterMemberAndKeepsEarlierEditsPrivate() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val work = object : WorkspaceRasterWork {
            override fun checkpoint() {}
            override fun progress(fraction: Float, message: String) {
                if (message == "Preparing painted mesh" && !entered.isCompleted) {
                    entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        WorkspaceOperations(Host(runtime, work)).use { operations ->
            val edits = listOf(WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "BeforePaint"); put("name", "Before paint") }),
                WorkspaceDocumentOperation("source_paint_shape", fields("shape", true)))
            val input = request(runtime, buildJsonObject { put("edits", JsonArray(edits.map { buildJsonObject { put("operation", it.operation); put("request", it.request) } })) })
            val started = operations.call("workspace_apply_edits", input)
            try {
                withTimeout(10000) { entered.await() }
                val running = operations.call("job_get", buildJsonObject { put("id", started.getValue("id")) })
                assertTrue(running.getValue("message").jsonPrimitive.content.contains("edit 2/2"))
                operations.cancel(started); release.countDown()
                assertEquals("cancelled", operations.wait(started).getValue("status").jsonPrimitive.content)
                assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            } finally { release.countDown() }
        }
    }
}
