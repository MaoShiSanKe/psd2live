package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class WorkspaceSimulationPreviewSessionsTest {
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) })
        val root = simulationFixture(runtime)
        val driven = WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Drive", simulationDrivingEdits(root), MutationAuthor.USER).capture
        WorkspaceDocumentCommands(runtime).execute(driven.projectId, driven.state, "Simulation", listOf(simulationPut(driven)), MutationAuthor.USER)
        return runtime
    }
    private fun start() = buildJsonObject { put("mode", "start"); put("simulation_id", "sway") }
    private fun step(id: String, value: Float = 20f, count: Int = 1) = buildJsonObject {
        put("session_id", id); put("dt", 1f / 60f); put("steps", count); putJsonObject("values") { put("Drive", value) }
    }
    private fun id(preview: WorkspaceSimulationPreview) = preview.report.getValue("session_id").jsonPrimitive.content
    private fun sameVertices(a: SimulatedFrame, b: SimulatedFrame) {
        assertEquals(a.positions.keys, b.positions.keys)
        a.positions.forEach { (id, vertices) -> assertContentEquals(vertices, b.positions.getValue(id)) }
    }

    @Test fun continuousFramesMatchAnIndependentSceneAndRestartWithoutChangingSavedPoseOrHistory() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val sessions = WorkspaceSimulationPreviewSessions(runtime)
        val initial = sessions.control(before.projectId, before.state, "first", start())
        val source = before.model.rig.puppet; val edit = before.document.rigEdits.simEdits.single()
        val scene = SimScene.build(source, edit); scene.calibrate(source)
        scene.reset(source, initial.values)
        val values = initial.values + (ParameterId("Drive") to 20f)
        repeat(20) { scene.drive(source, values, 1f / 60f) }
        val actual = sessions.step(before.projectId, before.state, "first", step(id(initial), count = 20))
        SimAuthoring.positions(scene).forEach { (id, vertices) -> assertContentEquals(vertices, actual.frame.positions.getValue(id)) }
        assertEquals(20L, actual.frame.serial); assertEquals(20.0 / 60.0, actual.report.getValue("elapsed").jsonPrimitive.double, 0.000001)
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        val query = sessions.get(before.projectId, "first", id(initial)); sameVertices(actual.frame, query.frame)
        val first = query.frame.positions.values.first(); first.fill(999f)
        sameVertices(actual.frame, sessions.get(before.projectId, "first", id(initial)).frame)
        val restarted = sessions.control(before.projectId, before.state, "first", buildJsonObject { put("mode", "restart"); put("session_id", id(initial)) })
        sameVertices(initial.frame, restarted.frame); assertEquals(0.0, restarted.report.getValue("elapsed").jsonPrimitive.double)
        validateOperationSchema(restarted.report, WorkspaceSimulationPreviewSchemas.result)
        val stopped = sessions.control(before.projectId, before.state, "first", buildJsonObject { put("mode", "stop"); put("session_id", id(initial)) })
        assertEquals("stopped", stopped.report.getValue("status").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { sessions.step(before.projectId, before.state, "first", step(id(initial))) }
        sameVertices(stopped.frame, sessions.get(before.projectId, "first", id(initial)).frame)
    }

    @Test fun authoredPoseMayDriveTheSameSceneButDocumentReloadAndForeignWorkspaceInvalidateIt() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val sessions = WorkspaceSimulationPreviewSessions(runtime)
        val first = sessions.control(root.projectId, root.state, "first", start())
        val second = sessions.control(root.projectId, root.state, "second", start())
        sessions.step(root.projectId, root.state, "first", step(id(first), count = 10))
        assertEquals(0L, sessions.get(root.projectId, "second", id(second)).frame.serial)
        assertFailsWith<IllegalArgumentException> { sessions.get(root.projectId, "second", id(first)) }
        WorkspacePreviewCommands(runtime).edit(root.projectId, root.state, "first", buildJsonObject {
            put("mode", "set"); putJsonObject("values") { put("Drive", -15) }; putJsonObject("locks") { put("Drive", true) }
        })
        var capture = runtime.capture()
        val locked = sessions.step(capture.projectId, capture.state, "first", step(id(first), value = 25f))
        assertEquals(-15f, locked.values.getValue(ParameterId("Drive")))
        assertFalse(locked.report.getValue("stale").jsonPrimitive.boolean)
        WorkspaceDocumentCommands(runtime).execute(capture.projectId, capture.state, "Changed body", listOf(WorkspaceDocumentOperation("simulation_put",
            buildJsonObject { put("id", "sway"); put("name", "Changed"); put("auto_bake", false) })), MutationAuthor.USER)
        capture = runtime.capture()
        assertTrue(sessions.get(root.projectId, "first", id(first)).report.getValue("stale").jsonPrimitive.boolean)
        assertFailsWith<WorkspaceConflict> { sessions.step(capture.projectId, capture.state, "first", step(id(first))) }
        sessions.control(capture.projectId, capture.state, "first", buildJsonObject { put("mode", "stop"); put("session_id", id(first)) })
        val fresh = sessions.control(capture.projectId, capture.state, "first", start())
        runtime.install(capture.state, root.projectId, capture.document, capture.model, runtime.history(), capture.auxiliary, discardUnsaved = true)
        assertTrue(sessions.get(root.projectId, "first", id(fresh)).report.getValue("stale").jsonPrimitive.boolean)
        assertFailsWith<WorkspaceConflict> { sessions.step(root.projectId, runtime.capture().state, "first", step(id(fresh))) }
        assertFailsWith<IllegalArgumentException> { sessions.control("foreign", runtime.capture().state, "first", start()) }
    }

    @Test fun cancellationInsideSolverRestoresThePrivateSceneAndSerialForTheNextFrame() = runBlocking<Unit> {
        val runtime = fixture(); val capture = runtime.capture(); val sessions = WorkspaceSimulationPreviewSessions(runtime)
        val preparation = WorkspaceJobContext { _, _ -> throw CancellationException("Cancel calibration") }
        assertFailsWith<CancellationException> { withContext(preparation) { sessions.control(capture.projectId, capture.state, "first", start()) } }
        assertEquals(capture, runtime.capture())
        val begun = sessions.control(capture.projectId, capture.state, "first", start())
        val direct = SimPreview(); direct.prepare(capture.model.rig.puppet, capture.document.rigEdits.simEdits.single(), begun.values)
        var checks = 0; val saved = direct.snapshot()
        assertFailsWith<CancellationException> { direct.step(capture.model.rig.puppet, capture.document.rigEdits.simEdits.single(),
            begun.values + (ParameterId("Drive") to 20f), 1f / 60f) { if (++checks == 3) throw CancellationException("Cancel inside constraints") } }
        direct.restore(saved)
        sameVertices(begun.frame, direct.frame())
        val midSolve = WorkspaceJobContext { _, _ -> throw CancellationException("Cancel after a solved frame") }
        assertFailsWith<CancellationException> { withContext(midSolve) {
            sessions.step(capture.projectId, capture.state, "first", step(id(begun), count = 10))
        } }
        sameVertices(begun.frame, sessions.get(capture.projectId, "first", id(begun)).frame)
        assertEquals(begun.frame.serial, sessions.get(capture.projectId, "first", id(begun)).frame.serial)
        val afterCancellation = sessions.step(capture.projectId, capture.state, "first", step(id(begun)))
        val independent = direct.step(capture.model.rig.puppet, capture.document.rigEdits.simEdits.single(),
            begun.values + (ParameterId("Drive") to 20f), 1f / 60f)!!
        sameVertices(independent, afterCancellation.frame)
        var restartCheckpointReached = false
        val restartCancellation = WorkspaceJobContext { progress, message ->
            assertEquals(0.9f, progress); assertEquals("Restarted simulation", message)
            restartCheckpointReached = true
            throw CancellationException("Cancel after restart before publication")
        }
        assertFailsWith<CancellationException> { withContext(restartCancellation) {
            sessions.control(capture.projectId, capture.state, "first", buildJsonObject { put("mode", "restart"); put("session_id", id(begun)) })
        } }
        assertTrue(restartCheckpointReached)
        val restored = sessions.get(capture.projectId, "first", id(begun))
        sameVertices(afterCancellation.frame, restored.frame); assertEquals(afterCancellation.frame.serial, restored.frame.serial)
        assertEquals(afterCancellation.report.getValue("elapsed"), restored.report.getValue("elapsed"))
        val continued = sessions.step(capture.projectId, capture.state, "first", step(id(begun)))
        val continuedDirect = direct.step(capture.model.rig.puppet, capture.document.rigEdits.simEdits.single(),
            begun.values + (ParameterId("Drive") to 20f), 1f / 60f)!!
        sameVertices(continuedDirect, continued.frame)
        val cancelled = Job().also { it.cancel() }
        assertFailsWith<CancellationException> { withContext(cancelled) { sessions.step(capture.projectId, capture.state, "first", step(id(begun))) } }
        sameVertices(continued.frame, sessions.get(capture.projectId, "first", id(begun)).frame)
        assertEquals(capture, runtime.capture())
        assertFailsWith<IllegalArgumentException> { sessions.step(capture.projectId, capture.state, "first", JsonObject(step(id(begun)) + ("dt" to JsonPrimitive(0)))) }
    }

    @Test fun processJobsDeduplicateStartAndStepsAndRetainSuccessfulFramesAfterLateHostCancellation() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val sessions = WorkspaceSimulationPreviewSessions(runtime)
        val host = object : WorkspaceBackendStub() {
            override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
            override suspend fun controlSimulationPreview(arguments: JsonObject) = sessions.control(root.projectId,
                arguments.getValue("state").jsonPrimitive.content, "first", arguments)
            override suspend fun stepSimulationPreview(arguments: JsonObject): WorkspaceSimulationPreview {
                sessions.step(root.projectId, arguments.getValue("state").jsonPrimitive.content, "first", arguments)
                throw CancellationException("Late projection cancelled")
            }
            override fun simulationPreviewFrame(sessionId: String) = sessions.get(root.projectId, "first", sessionId)
        }
        WorkspaceOperations(host).use { operations ->
            val context = WorkspaceOperationContext(MutationAuthor.AGENT)
            suspend fun call(operation: String, input: JsonObject): JsonObject {
                val job = operations.registry.invoke(operation, input, context).data
                val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, context).data
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                assertEquals(job.getValue("id"), operations.registry.invoke(operation, input, context).data.getValue("id"))
                return terminal.getValue("result").jsonObject.also { validateOperationSchema(it, WorkspaceSimulationPreviewSchemas.result) }
            }
            val began = call("simulation_preview", JsonObject(start() + buildJsonObject {
                put("state", root.state); put("project_id", root.projectId); put("request_id", "start-once")
            }))
            val solved = call("simulation_preview_step", JsonObject(step(began.getValue("session_id").jsonPrimitive.content, count = 5) + buildJsonObject {
                put("state", root.state); put("project_id", root.projectId); put("request_id", "step-once")
            }))
            assertEquals(5L, solved.getValue("serial").jsonPrimitive.long)
            val queried = operations.registry.invoke("simulation_preview_get", buildJsonObject {
                put("session_id", began.getValue("session_id")); put("include_positions", true)
            }, context).data
            assertEquals(solved.getValue("serial"), queried.getValue("serial")); assertTrue(queried.getValue("positions").jsonObject.isNotEmpty())
            assertEquals(root, runtime.capture())
        }
    }
}
