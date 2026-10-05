package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkspaceObservationJobsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val sampler: WorkspaceMotionSampler,
                       val remember: (WorkspaceRenderedView) -> WorkspaceRenderedView = { it }) : WorkspaceBackendStub() {
        val captures = AtomicInteger()
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override fun captureObservation(): WorkspaceObservation {
            captures.incrementAndGet()
            return WorkspaceObservationSession(runtime.read(), WorkspacePreviewBuilder(), sampler, remember)
        }
    }
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Drive forms", simulationDrivingEdits(root), MutationAuthor.USER)
        return runtime
    }
    private fun request(runtime: WorkspaceRuntime<RigPreviewModel>) = buildJsonObject {
        val capture = runtime.capture()
        put("request_id", "motion"); put("project_id", capture.projectId); put("state", capture.state)
        putJsonArray("rect") { add(-64); add(-32); add(256); add(256) }; put("target_long_edge", 512)
        putJsonArray("frames") {
            add(buildJsonObject { put("time", 0); putJsonObject("parameters") { put("Drive", -30) } })
            add(buildJsonObject { put("time", 0.2); putJsonObject("parameters") { put("Drive", 30) } })
        }
        putJsonArray("samples") { add(0); add(0.1); add(0.2) }; put("fps", 60)
    }
    // Deterministic evaluator fixture: exercises the real request, exported motion, compositor and job boundaries.
    private fun samples(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, count: Int): List<Map<ParameterId, Float>> {
        val motion = Json.parseToJsonElement(bundle.assets.single { it.path.endsWith("agent-observation.motion3.json") }.bytes.decodeToString()).jsonObject
        assertEquals(1, motion.getValue("Curves").jsonArray.size)
        assertEquals("Drive", motion.getValue("Curves").jsonArray.single().jsonObject.getValue("Id").jsonPrimitive.content)
        return List(count) { index -> parameters.associateWith { if (it.raw == "Drive") -30f + 60f * index / (count - 1) else 0f } }
    }
    private fun evaluator() = WorkspaceMotionSampler { bundle, parameters, count, _, progress, cancelled ->
        cancelled(); progress(0f); val result = samples(bundle, parameters, count); progress(1f); result
    }
    private suspend fun WorkspaceOperations.call(id: String, arguments: JsonObject) = registry.invoke(id, arguments, agent)
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) })

    @Test fun motionSamplesUseCapturedVersionAndRetainPngAcrossEditsReloadAndRetry() = runBlocking {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val remembered = mutableListOf<WorkspaceRenderedView>()
        val host = Host(runtime, WorkspaceMotionSampler { bundle, parameters, count, _, progress, cancelled ->
            calls.incrementAndGet(); progress(0.4f); entered.complete(Unit); release.await(); cancelled()
            samples(bundle, parameters, count).also { progress(1f) }
        }, { remembered += it; it })
        val input = request(runtime)
        val expected = WorkspaceObservationSession(runtime.read(), builder, evaluator(), { it }).observeAuthoring(JsonObject(
            input - setOf("request_id", "project_id", "state") + ("kind" to JsonPrimitive("motion"))))
        WorkspaceOperations(host).use { operations ->
            val job = operations.call("view_sample_motion", input).data
            withTimeout(5000) { entered.await() }
            val changed = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Foreign rate",
                listOf(WorkspaceDocumentOperation("physics_config", buildJsonObject { put("fps", 120) })), MutationAuthor.USER).capture
            runtime.install(changed.state, changed.projectId, changed.document, builder.build(changed.document), discardUnsaved = true)
            val reloaded = runtime.capture(); val newHistory = runtime.history(); release.complete(Unit)
            val completed = operations.wait(job)
            assertEquals("completed", completed.data.getValue("status").jsonPrimitive.content, completed.data.toString())
            val result = completed.data.getValue("result").jsonObject
            validateOperationSchema(result, WorkspaceJobResultSchemas.result("view_sample_motion"))
            assertEquals(before.state, result.getValue("state").jsonPrimitive.content)
            assertEquals(before.revision, result.getValue("revision").jsonPrimitive.content)
            assertEquals(before.revision, result.getValue("revisionId").jsonPrimitive.content)
            assertContentEquals(expected.images.single(), completed.images.single())
            assertEquals(listOf(-30f, 0f, 30f), result.getValue("tiles").jsonArray.map { it.jsonObject.getValue("parameters").jsonObject.getValue("Drive").jsonPrimitive.float })
            assertEquals(3, remembered.size); assertTrue(remembered.all { it.revisionId == before.revision })
            assertEquals(1, calls.get()); assertEquals(1, host.captures.get())
            assertEquals(job.getValue("id"), operations.call("view_sample_motion", input).data.getValue("id"))
            assertContentEquals(completed.images.single(), operations.wait(job).images.single())
            assertEquals(reloaded, runtime.capture()); assertEquals(newHistory, runtime.history()); assertNotEquals(history, newHistory)
        }
    }

    @Test fun cancellationStopsEvaluationAndRenderingWithoutPublishingResultOrHistory() = runBlocking {
        for (duringRender in listOf(false, true)) {
            val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val count = AtomicInteger()
            val sampler = WorkspaceMotionSampler { bundle, parameters, frames, _, progress, cancelled ->
                progress(0.3f)
                if (!duringRender) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); cancelled() }
                samples(bundle, parameters, frames).also { progress(1f) }
            }
            val host = Host(runtime, sampler) { view ->
                count.incrementAndGet()
                if (duringRender) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
                view
            }
            WorkspaceOperations(host).use { operations ->
                val input = request(runtime); val job = operations.call("view_sample_motion", input).data
                try {
                    withTimeout(5000) { entered.await() }
                    operations.call("job_cancel", buildJsonObject { put("request_id", "cancel"); put("id", job.getValue("id")) })
                    release.countDown()
                    val cancelled = operations.wait(job)
                    assertEquals("cancelled", cancelled.data.getValue("status").jsonPrimitive.content)
                    assertFalse("result" in cancelled.data); assertTrue(cancelled.images.isEmpty())
                    assertEquals(if (duringRender) 1 else 0, count.get())
                    assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
                    assertEquals(job.getValue("id"), operations.call("view_sample_motion", input).data.getValue("id"))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun disconnectedWaiterLeavesSamplingAliveAndChangedRequestCannotReuseItsId() = runBlocking<Unit> {
        val runtime = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val host = Host(runtime, WorkspaceMotionSampler { bundle, parameters, count, _, progress, _ ->
            progress(0.5f); entered.complete(Unit); release.await(); samples(bundle, parameters, count).also { progress(1f) }
        })
        WorkspaceOperations(host).use { operations ->
            val input = request(runtime); val job = operations.call("view_sample_motion", input).data
            withTimeout(5000) { entered.await() }
            val waiter = async { operations.wait(job) }; yield(); waiter.cancelAndJoin()
            assertFalse(operations.call("job_get", buildJsonObject { put("id", job.getValue("id")) }).data.getValue("terminal").jsonPrimitive.boolean)
            val definition = operations.registry.definition("view_sample_motion")
            assertTrue(definition.jobBacked && definition.workspaceBound && definition.idempotent); assertFalse(definition.batchable)
            assertEquals(WorkspaceOperationKind.QUERY, definition.kind)
            assertFailsWith<WorkspaceRequestReuse> { operations.call("view_sample_motion", JsonObject(input + ("fps" to JsonPrimitive(30)))) }
            release.complete(Unit)
            assertEquals("completed", operations.wait(job).data.getValue("status").jsonPrimitive.content)
            assertEquals(1, host.captures.get())
        }
    }

    @Test fun historyObservationKeepsCapturedTreeAndMotionRejectsIncompleteEvaluatorSamples() = runBlocking {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val session = WorkspaceObservationSession(runtime.read(), builder, evaluator(), { it })
        runtime.install(before.state, before.projectId, before.document, before.model, discardUnsaved = true)
        val historyResult = session.observeAuthoring(buildJsonObject {
            put("kind", "history"); putJsonArray("rect") { add(-64); add(-32); add(256); add(256) }; put("target_long_edge", 512)
            putJsonArray("states") { history.selections.forEach { add(it.node.id) } }; putJsonArray("poses") { add(buildJsonObject {}) }
        })
        validateOperationSchema(historyResult.metadata, WorkspaceObservationResultSchemas.forOperation("view_compare_history")!!)
        assertEquals(2, historyResult.metadata.getValue("tiles").jsonArray.size)
        assertEquals(1, runtime.history().selections.size)
        val current = runtime.capture(); val host = Host(runtime, WorkspaceMotionSampler { _, _, _, _, _, _ -> emptyList() })
        WorkspaceOperations(host).use { operations ->
            val job = operations.call("view_sample_motion", request(runtime)).data
            val terminal = operations.wait(job)
            assertEquals("failed", terminal.data.getValue("status").jsonPrimitive.content)
            assertFalse("result" in terminal.data); assertTrue(terminal.images.isEmpty()); assertEquals(current, runtime.capture())
        }
    }

    @Test fun invalidContextAndFrameFieldsAreRejectedBeforeCapturingOrScheduling() = runBlocking<Unit> {
        val runtime = fixture(); val host = Host(runtime, evaluator())
        WorkspaceOperations(host).use { operations ->
            val input = request(runtime)
            val frame = input.getValue("frames").jsonArray.first().jsonObject
            val invalid = listOf(JsonObject(input - "request_id"), JsonObject(input + ("project_id" to JsonNull)),
                JsonObject(input + ("frames" to JsonArray(listOf(JsonObject(frame + ("extra" to JsonPrimitive(true))), input.getValue("frames").jsonArray.last())))),
                JsonObject(input + ("frames" to JsonArray(listOf(frame, JsonObject(frame + ("time" to JsonPrimitive(11))))))),
                JsonObject(input + ("samples" to JsonArray(listOf(JsonPrimitive(-0.1))))))
            invalid.forEach { assertFailsWith<WorkspaceValidationException> { operations.call("view_sample_motion", it) } }
            assertFailsWith<WorkspaceConflict> { operations.call("view_sample_motion", JsonObject(input + ("state" to JsonPrimitive("stale")))) }
            assertEquals(0, host.captures.get())
            assertEquals(0, operations.call("job_list", buildJsonObject {}).data.getValue("total").jsonPrimitive.int)
        }
    }
}
