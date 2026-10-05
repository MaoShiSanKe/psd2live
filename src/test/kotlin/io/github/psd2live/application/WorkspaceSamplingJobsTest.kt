package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Detached real solver sessions exercised through the public process-job boundary. */
class WorkspaceSamplingJobsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val observe: (Float) -> Unit = {}) : WorkspaceBackendStub() {
        val captures = AtomicInteger()
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override fun captureQueries(): WorkspaceQueries {
            captures.incrementAndGet()
            val read = WorkspaceReadSession(runtime.read())
            return object : WorkspaceQueries by read {
                override fun reportSimulation(id: String, hold: Float, release: Float, wind: Pair<Float, Float>?,
                                              progress: (Float) -> Unit, cancelled: () -> Boolean) =
                    read.reportSimulation(id, hold, release, wind, { value -> progress(value); observe(value) }, cancelled)
                override fun simulatePhysics(arguments: JsonObject, progress: (Float) -> Unit, cancelled: () -> Boolean) =
                    read.simulatePhysics(arguments, { value -> progress(value); observe(value) }, cancelled)
            }
        }
        override fun reportSimulation(id: String, hold: Float, release: Float, wind: Pair<Float, Float>?, progress: (Float) -> Unit, cancelled: () -> Boolean): JsonObject =
            error("A sampling job must use detached queries")
        override fun simulatePhysics(arguments: JsonObject, progress: (Float) -> Unit, cancelled: () -> Boolean): JsonObject =
            error("A sampling job must use detached queries")
    }
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Sampling setup", simulationDrivingEdits(root) +
            simulationPut(root) + listOf(WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                put("parameter_id", "Response"); put("name", "Response"); put("min", -30); put("max", 30)
            }), WorkspaceDocumentOperation("physics_put", buildJsonObject {
                put("id", "spring"); putJsonArray("inputs") { add(buildJsonObject { put("parameter", "Drive"); put("type", "x") }) }
                putJsonArray("outputs") { add(buildJsonObject { put("parameter", "Response"); put("vertex", 1); put("scale", 0.5) }) }
                putJsonArray("segments") { add(buildJsonObject { put("length", 8) }) }
            })), MutationAuthor.USER)
        return runtime
    }
    private fun request(runtime: WorkspaceRuntime<RigPreviewModel>, operation: String) = buildJsonObject {
        val c = runtime.capture(); put("project_id", c.projectId); put("state", c.state); put("request_id", "sampling")
        if (operation == "simulation_simulate") { put("id", "sway"); put("hold", 0.1); put("release", 0.1) }
        else {
            putJsonArray("ids") { add("spring") }; putJsonObject("inputs") { put("Drive", 30) }
            put("duration", 0.2); put("hold", 0.1); put("samples", 3)
        }
    }
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })
    private suspend fun WorkspaceOperations.cancel(job: JsonObject) = call("job_cancel", buildJsonObject { put("request_id", "cancel"); put("id", job.getValue("id")) })

    @Test fun samplesKeepOneCapturedVersionAcrossForeignEditsAndSameProjectReloads() = runBlocking {
        for (operation in WorkspaceSamplingJobs.supported) {
            val runtime = fixture(); val before = runtime.capture()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val host = Host(runtime) { if (it >= 0.3f && !entered.isCompleted) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) } }
            val detached = WorkspaceReadSession(runtime.read())
            val input = request(runtime, operation)
            val business = JsonObject(input - setOf("state", "project_id", "request_id"))
            val expected = if (operation == "physics_simulate") detached.simulatePhysics(business)
                else detached.reportSimulation("sway", 0.1f, 0.1f, null)
            WorkspaceOperations(host).use { operations ->
                val job = operations.call(operation, input)
                try {
                    withTimeout(5000) { entered.await() }
                    val changed = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Foreign rate",
                        listOf(WorkspaceDocumentOperation("physics_config", buildJsonObject { put("fps", 120) })), MutationAuthor.USER).capture
                    val reloaded = runtime.install(changed.state, changed.projectId, changed.document, builder.build(changed.document), discardUnsaved = true)
                    val history = runtime.history()
                    release.countDown()
                    val terminal = operations.wait(job)
                    assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                    val result = terminal.getValue("result").jsonObject
                    assertEquals(expected, JsonObject(result - setOf("project_id", "state", "revision")))
                    assertEquals(before.state, result.getValue("state").jsonPrimitive.content)
                    assertEquals(before.revision, result.getValue("revision").jsonPrimitive.content)
                    assertEquals(before.projectId, result.getValue("project_id").jsonPrimitive.content)
                    assertEquals(1, host.captures.get())
                    assertEquals(reloaded, runtime.capture()); assertEquals(history, runtime.history())
                    assertEquals(job.getValue("id"), operations.call(operation, input).getValue("id"))
                    assertEquals(terminal, operations.wait(job))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun cancellationInterruptsCalibrationSettlingAndActualSampleFramesWithoutMutatingHistory() = runBlocking {
        for (operation in WorkspaceSamplingJobs.supported) for (fraction in listOf(0.01f, 0.4f)) {
            val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val host = Host(runtime) { if (it >= fraction && !entered.isCompleted) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) } }
            WorkspaceOperations(host).use { operations ->
                val input = request(runtime, operation); val job = operations.call(operation, input)
                try {
                    withTimeout(5000) { entered.await() }
                    val running = operations.call("job_get", buildJsonObject { put("id", job.getValue("id")) })
                    assertEquals("running", running.getValue("status").jsonPrimitive.content)
                    assertTrue(running.getValue("progress").jsonPrimitive.float >= 0.05f + 0.9f * fraction)
                    operations.cancel(job); release.countDown()
                    val terminal = operations.wait(job)
                    assertEquals("cancelled", terminal.getValue("status").jsonPrimitive.content)
                    assertFalse("result" in terminal)
                    assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
                    assertEquals(job.getValue("id"), operations.call(operation, input).getValue("id"))
                    assertEquals(terminal, operations.wait(job))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun waitingCancellationKeepsTheReadOnlyJobAndRequestRetryRecoversItsDiagnostics() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val positions = before.model.rig.puppet.drawables.first().mesh!!.positions.copyOf()
        val pinWeights = before.model.rig.puppet.vertexGroups.single { it.name == "root" }.weights.copyOf()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val values = java.util.Collections.synchronizedList(mutableListOf<Float>())
        val host = Host(runtime) { value -> values.add(value); if (value >= 0.3f && !entered.isCompleted) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) } }
        WorkspaceOperations(host).use { operations ->
            val input = request(runtime, "simulation_simulate"); val job = operations.call("simulation_simulate", input)
            try {
                withTimeout(5000) { entered.await() }
                val waiter = async { operations.wait(job) }; yield(); waiter.cancelAndJoin()
                assertFalse(operations.call("job_get", buildJsonObject { put("id", job.getValue("id")) }).getValue("terminal").jsonPrimitive.boolean)
                assertEquals(job.getValue("id"), operations.call("simulation_simulate", input).getValue("id"))
                release.countDown()
                val completed = operations.wait(job)
                assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
                validateOperationSchema(completed.getValue("result"), WorkspaceJobResultSchemas.result("simulation_simulate"))
                assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
                assertEquals(1, host.captures.get()); assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
                assertContentEquals(positions, runtime.capture().model.rig.puppet.drawables.first().mesh!!.positions)
                assertContentEquals(pinWeights, runtime.capture().model.rig.puppet.vertexGroups.single { it.name == "root" }.weights)
                val definition = operations.registry.definition("simulation_simulate")
                assertEquals(WorkspaceOperationKind.QUERY, definition.kind)
                assertTrue(definition.jobBacked && definition.workspaceBound && definition.idempotent)
                assertFalse(definition.batchable)
                assertFailsWith<WorkspaceRequestReuse> { operations.call("simulation_simulate", JsonObject(input + ("hold" to JsonPrimitive(0.2)))) }
            } finally { release.countDown() }
        }
    }
}
