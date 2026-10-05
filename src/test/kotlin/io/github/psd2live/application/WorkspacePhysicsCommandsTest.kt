package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspacePhysicsCommandsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private fun fields(id: String = "spring") = buildJsonObject { put("id", id) }
    private fun put(id: String = "spring") = WorkspaceDocumentOperation("physics_put", buildJsonObject {
        put("id", id)
        putJsonArray("inputs") { add(buildJsonObject { put("parameter", "Drive"); put("type", "x") }) }
        putJsonArray("outputs") { add(buildJsonObject { put("parameter", "Response"); put("vertex", 1); put("scale", 0.5) }) }
        putJsonArray("segments") { add(buildJsonObject { put("length", 8) }) }
    })
    private suspend fun fixture(group: Boolean = true): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        val parameters = listOf("Drive", "Response").map { id -> WorkspaceDocumentOperation("parameter_create", buildJsonObject {
            put("parameter_id", id); put("name", id); put("min", -30); put("max", 30)
        }) }
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Physics setup", parameters + if (group) listOf(put()) else emptyList(), MutationAuthor.USER)
        return runtime
    }
    private fun file(): Path {
        fun group(id: String, input: String, output: String) = RigPhysicsEdit(id, id,
            listOf(PhysicsInput(input, 100f, PhysicsSourceType.X)), listOf(PhysicsOutput(output, 1, 5f)), listOf(PhysicsSegment(8f)))
        return temporary.resolve("physics3.json").also { Files.writeString(it, Physics3Json.write(listOf(
            group("imported", "Drive", "Response"), group("missing", "Absent", "AbsentOutput")), 30)!!) }
    }
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, work: WorkspacePhysicsWork = WorkspacePhysicsWork.Direct,
        read: suspend (Path) -> String = { Files.readString(it) }, val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        private val commands = WorkspacePhysicsCommands(runtime, work, read)
        private val batch = WorkspaceDocumentCommands(runtime, physicsWork = work)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        private suspend fun execute(id: String, state: String, arguments: JsonObject): Pair<WorkspaceMutationResult, JsonObject> {
            val result = commands.execute(runtime.capture().projectId, state, WorkspaceDocumentOperation(id, arguments), id, MutationAuthor.AGENT)
            after()
            return result.mutation to result.report
        }
        override suspend fun putPhysics(arguments: JsonObject, expectedState: String, taskId: String?) = execute("physics_put", expectedState, arguments).first
        override suspend fun deletePhysics(id: String, expectedState: String) = execute("physics_delete", expectedState, buildJsonObject { put("id", id) }).first
        override suspend fun configurePhysics(order: List<String>?, fps: Int?, expectedState: String) = execute("physics_config", expectedState, buildJsonObject {
            order?.let { put("order", JsonArray(it.map(::JsonPrimitive))) }; fps?.let { put("fps", it) }
        }).first
        override suspend fun importPhysics(path: String, expectedState: String) = execute("physics_import", expectedState, buildJsonObject { put("path", path) })
        override suspend fun fitPhysics(id: String, target: Float, expectedState: String, observedPeaks: Map<Int, Float>?) =
            execute("physics_fit", expectedState, buildJsonObject {
                put("id", id); put("target", target * 100)
                observedPeaks?.let { peaks -> put("observed_peaks", buildJsonObject { peaks.forEach { (k, v) -> put(k.toString(), v) } }) }
            }).first
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            val result = batch.execute(before.projectId, state, summary, edits, author)
            after()
            return WorkspaceDocumentCommands.mutationResult(before, result, summary, edits)
        }
    }
    private fun request(runtime: WorkspaceRuntime<RigPreviewModel>, fields: JsonObject, id: String = "physics") = JsonObject(fields + buildJsonObject {
        val capture = runtime.capture(); put("project_id", capture.projectId); put("state", capture.state); put("request_id", id)
    })
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })
    private suspend fun WorkspaceOperations.cancel(job: JsonObject) = call("job_cancel", buildJsonObject { put("request_id", "cancel"); put("id", job.getValue("id")) })

    @Test fun observedPeaksFitTheMeasuredReachAndRejectBadIndexesOrNoResponseWithoutPublishing() = runBlocking<Unit> {
        val runtime = fixture()
        val commands = WorkspacePhysicsCommands(runtime)
        suspend fun apply(data: JsonObject): WorkspacePhysicsCommit {
            val c = runtime.capture(); return commands.execute(c.projectId, c.state, WorkspaceDocumentOperation("physics_fit", data), "fit", MutationAuthor.USER)
        }
        val c = runtime.capture()
        commands.execute(c.projectId, c.state, WorkspaceDocumentOperation("physics_import", buildJsonObject { put("path", file().toString()) }), "import", MutationAuthor.USER)
        val before = runtime.capture().document.rigEdits.physicsEdits.first { it.id == "imported" }.outputs.single().scale
        val history = runtime.history()
        for (bad in listOf(buildJsonObject { put("1", 0.5f) }, buildJsonObject { put("0", -0.1f) }, buildJsonObject { put("0", 0.001f) },
            buildJsonObject { put("x", 0.5f) }, buildJsonObject { put("00", 0.5f) }, buildJsonObject { put("0", "0.5") })) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) {
                apply(JsonObject(fields("imported") + ("observed_peaks" to bad)))
            }
        }
        assertEquals(history, runtime.history())
        val fitted = apply(JsonObject(fields("imported") + buildJsonObject { put("target", 50); putJsonObject("observed_peaks") { put("0", 0.25f) } }))
        assertTrue(fitted.mutation.applied)
        // Half the end over a quarter reached doubles the scale, the same rounding the panel's Fit uses.
        assertEquals(kotlin.math.round(before * 0.5f / 0.25f * 1000f) / 1000f,
            fitted.commit.capture.document.rigEdits.physicsEdits.first { it.id == "imported" }.outputs.single().scale)
    }

    @Test fun importReportsDisabledAndMissingParametersAndAllCommandsReplayWithoutDesktopState() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val commands = WorkspacePhysicsCommands(runtime)
        suspend fun apply(id: String, data: JsonObject): WorkspacePhysicsCommit {
            val c = runtime.capture(); return commands.execute(c.projectId, c.state, WorkspaceDocumentOperation(id, data), id, MutationAuthor.USER)
        }
        val imported = apply("physics_import", buildJsonObject { put("path", file().toString()) })
        assertEquals(listOf("imported", "missing"), imported.report.getValue("imported").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonArray(listOf(JsonPrimitive("spring"))), imported.report.getValue("disabled"))
        assertEquals(JsonArray(listOf(JsonPrimitive("Absent"), JsonPrimitive("AbsentOutput"))), imported.report.getValue("missing_parameters").jsonObject.getValue("missing"))
        validateOperationSchema(imported.mutation.physicsResult(imported.report), WorkspaceJobResultSchemas.result("physics_import"))
        assertEquals(30, runtime.capture().document.rigEdits.physicsFps)
        val fitted = apply("physics_fit", fields("imported"))
        val overlay = fitted.commit.capture.document.rigEdits
        assertNotEquals(5f, overlay.physicsEdits.first { it.id == "imported" }.outputs.single().scale)
        val history = runtime.history()
        assertFalse(apply("physics_fit", fields("imported")).mutation.applied)
        assertFailsWith<IllegalArgumentException> { apply("physics_fit", fields("missing")) }
        assertEquals(history, runtime.history())
        apply("physics_config", buildJsonObject { put("fps", 120); putJsonArray("order") { add("imported") } })
        assertFalse(apply("physics_config", buildJsonObject { put("fps", 120) }).mutation.applied)
        apply("physics_put", buildJsonObject { put("id", "imported"); put("enabled", false) })
        apply("physics_delete", fields("missing"))
        assertEquals(7, runtime.history().selections.size)
        val final = runtime.capture()
        val restored = builder.build(final.document)
        assertEquals(WorkspaceDocumentEdits.physicsCatalog(final.document, final.model), WorkspaceDocumentEdits.physicsCatalog(final.document, restored))
        runtime.checkout(final.projectId, final.state, before.historyHead)
        assertEquals(before.document, runtime.capture().document)
        val current = runtime.capture(); runtime.checkout(current.projectId, current.state, final.historyHead)
        assertEquals(final.document, runtime.capture().document)
    }

    @Test fun fittingCancellationInSettlingOrFramesAndForeignEditsKeepTheOriginalCandidateAndHistory() = runBlocking<Unit> {
        for (fraction in listOf(0.05f, 0.4f)) for (cancel in listOf(true, false)) {
            val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val work = object : WorkspacePhysicsWork {
                override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({ value ->
                    if (value >= fraction && !entered.isCompleted) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
                }, { false })
            }
            WorkspaceOperations(Host(runtime, work)).use { operations ->
                val input = request(runtime, fields())
                val job = operations.call("physics_fit", input)
                try {
                    withTimeout(10000) { entered.await() }
                    val running = operations.call("job_get", buildJsonObject { put("id", job.getValue("id")) })
                    assertTrue(running.getValue("message").jsonPrimitive.content.contains("Fitting physics spring"))
                    val expected = if (cancel) { operations.cancel(job); before }
                        else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                    release.countDown()
                    val terminal = operations.wait(job)
                    assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                    if (!cancel) {
                        val failure = terminal.getValue("error").jsonObject
                        assertEquals("state_conflict", failure.getValue("code").jsonPrimitive.content)
                        assertEquals(before.state, failure.getValue("expected_state").jsonPrimitive.content)
                        assertEquals(expected.state, failure.getValue("actual_state").jsonPrimitive.content)
                    }
                    assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
                    assertEquals(job.getValue("id"), operations.call("physics_fit", input).getValue("id"))
                    assertEquals(terminal, operations.wait(job))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun importChecksStateBeforeReadingAndRejectsCancellationConflictAndProjectionWithoutPublishing() = runBlocking<Unit> {
        val path = file()
        for (cancel in listOf(true, false)) {
            val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            WorkspaceOperations(Host(runtime, read = { entered.complete(Unit); release.await(); Files.readString(it) })).use { operations ->
                val input = request(runtime, buildJsonObject { put("path", path.toString()) })
                val job = operations.call("physics_import", input)
                withTimeout(5000) { entered.await() }
                val expected = if (cancel) { operations.cancel(job); before }
                    else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit)
                val terminal = operations.wait(job)
                assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                if (!cancel) assertEquals("state_conflict", terminal.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
            }
        }
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        var reads = 0
        val commands = WorkspacePhysicsCommands(runtime, readFile = { reads++; Files.readString(it) })
        val operation = WorkspaceDocumentOperation("physics_import", buildJsonObject { put("path", path.toString()) })
        assertFailsWith<WorkspaceConflict> { commands.execute(before.projectId, "stale", operation, "Import", MutationAuthor.AGENT) }
        assertEquals(0, reads)
        assertFailsWith<IllegalStateException> { commands.execute(before.projectId, before.state, operation, "Import", MutationAuthor.AGENT) { _, _, _ -> error("Projection rejected") } }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        WorkspaceOperations(Host(runtime)).use { operations ->
            val missing = operations.call("physics_import", request(runtime, buildJsonObject { put("path", temporary.resolve("absent.json").toString()) }))
            val failed = operations.wait(missing)
            assertEquals("io_error", failed.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(before, runtime.capture())
        }
    }

    @Test fun everySinglePhysicsJobRetainsItsFullResultAfterLateCancellationOrRefreshFailure() = runBlocking<Unit> {
        for (id in WorkspacePhysicsEdits.supported) for (cancel in listOf(true, false)) {
            val runtime = fixture()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val host = Host(runtime, after = {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed after physics commit")
            })
            WorkspaceOperations(host).use { operations ->
                val data = when (id) {
                    "physics_import" -> buildJsonObject { put("path", file().toString()) }
                    "physics_config" -> buildJsonObject { put("fps", 120) }
                    "physics_put" -> buildJsonObject { put("id", "spring"); put("enabled", false) }
                    else -> fields()
                }
                val input = request(runtime, data)
                val job = operations.call(id, input)
                withTimeout(10000) { entered.await() }
                if (cancel) operations.cancel(job)
                release.complete(Unit)
                val terminal = operations.wait(job)
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                val result = terminal.getValue("result").jsonObject
                validateOperationSchema(result, WorkspaceJobResultSchemas.result(id))
                val after = runtime.capture()
                assertEquals(after.state, result.getValue("state").jsonPrimitive.content)
                assertEquals(after.historyHead, result.getValue("history_node_id").jsonPrimitive.content)
                if (id == "physics_import") { assertTrue("disabled" in result); assertTrue("missing_parameters" in result); assertEquals(30, result.getValue("fps").jsonPrimitive.int) }
                assertEquals(3, runtime.history().selections.size)
                assertEquals(job.getValue("id"), operations.call(id, input).getValue("id"))
                assertEquals(terminal, operations.wait(job))
            }
        }
    }

    @Test fun batchFittingUsesEarlierCandidatesAndRollsBackOnLaterFailureOrSolverCancellation() = runBlocking<Unit> {
        val runtime = fixture(false); val before = runtime.capture()
        val edits = listOf(put(), WorkspaceDocumentOperation("physics_fit", fields()), WorkspaceDocumentOperation("physics_config", buildJsonObject { put("fps", 120) }))
        val commands = WorkspaceDocumentCommands(runtime)
        val committed = commands.execute(before.projectId, before.state, "Create and fit", edits, MutationAuthor.AGENT)
        assertEquals(3, runtime.history().selections.size)
        assertNotEquals(0.5f, runtime.capture().document.rigEdits.physicsEdits.single().outputs.single().scale)
        val final = runtime.capture(); val history = runtime.history()
        val failed = assertFailsWith<WorkspaceBatchEditException> { commands.execute(final.projectId, final.state, "Rollback", listOf(
            WorkspaceDocumentOperation("physics_config", buildJsonObject { put("fps", 60) }),
            WorkspaceDocumentOperation("physics_fit", fields("unknown"))), MutationAuthor.AGENT) }
        assertEquals(1, failed.index); assertEquals(final, runtime.capture()); assertEquals(history, runtime.history())
        val fresh = fixture(false); val start = fresh.capture(); val startHistory = fresh.history()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val work = object : WorkspacePhysicsWork {
            override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T = action({ fraction ->
                if (fraction >= 0.4f && !entered.isCompleted) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
            }, { false })
        }
        WorkspaceOperations(Host(fresh, work)).use { operations ->
            assertTrue(operations.registry.definition("physics_fit").batchable)
            assertFalse(operations.registry.definition("physics_import").batchable)
            val input = request(fresh, buildJsonObject { put("edits", JsonArray(edits.map { buildJsonObject { put("operation", it.operation); put("request", it.request) } })) })
            val job = operations.call("workspace_apply_edits", input)
            try {
                withTimeout(10000) { entered.await() }; operations.cancel(job); release.countDown()
                assertEquals("cancelled", operations.wait(job).getValue("status").jsonPrimitive.content)
                assertEquals(start, fresh.capture()); assertEquals(startHistory, fresh.history())
                assertEquals(job.getValue("id"), operations.call("workspace_apply_edits", input).getValue("id"))
            } finally { release.countDown() }
        }
        assertTrue(committed.applied)
    }
}
