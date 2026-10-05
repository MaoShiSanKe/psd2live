package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspacePhysicsAuditionSessionsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private fun group(scale: Double = 0.5) = WorkspaceDocumentOperation("physics_put", buildJsonObject {
        put("id", "spring")
        putJsonArray("inputs") { add(buildJsonObject { put("parameter", "Drive"); put("type", "x") }) }
        putJsonArray("outputs") { add(buildJsonObject { put("parameter", "Response"); put("vertex", 1); put("scale", scale) }) }
        putJsonArray("segments") { add(buildJsonObject { put("length", 8) }) }
    })
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        val parameters = listOf("Drive", "Response").map { id -> WorkspaceDocumentOperation("parameter_create", buildJsonObject {
            put("parameter_id", id); put("name", id); put("min", -30); put("max", 30)
        }) }
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Physics setup", parameters + group(), MutationAuthor.USER)
        return runtime
    }
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        val sessions = WorkspacePhysicsAuditionSessions(runtime)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        private fun capture() = runtime.capture()
        override fun controlPhysicsAudition(arguments: JsonObject) =
            sessions.control(capture().projectId, arguments.getValue("state").jsonPrimitive.content, "main", arguments)
        override fun stepPhysicsAudition(arguments: JsonObject) =
            sessions.step(capture().projectId, arguments.getValue("state").jsonPrimitive.content, "main", arguments)
        override fun physicsAudition(sessionId: String) = sessions.get(capture().projectId, "main", sessionId)
    }
    private fun request(runtime: WorkspaceRuntime<RigPreviewModel>, id: String, fields: JsonObject) = JsonObject(fields + buildJsonObject {
        val capture = runtime.capture(); put("project_id", capture.projectId); put("state", capture.state); put("request_id", id)
    })
    private suspend fun WorkspaceOperations.call(id: String, input: JsonObject) = registry.invoke(id, input, agent).data

    @Test fun draggedAuditionRecordsPeaksThatFitTheGroupWithoutTouchingHistoryOrPoses() = runBlocking<Unit> {
        val runtime = fixture()
        val host = Host(runtime)
        WorkspaceOperations(host).use { operations ->
            val history = runtime.history(); val auxiliary = runtime.capture().auxiliary
            val started = operations.call("physics_audition", request(runtime, "start", buildJsonObject { put("mode", "start"); put("group_id", "spring") }))
            val session = started.getValue("session_id").jsonPrimitive.content
            assertEquals("running", started.getValue("status").jsonPrimitive.content)
            assertEquals(0, started.getValue("serial").jsonPrimitive.int)
            operations.call("physics_audition", request(runtime, "pull", buildJsonObject { put("mode", "target"); put("session_id", session); put("x", 1); put("y", 0) }))
            val pulled = operations.call("physics_audition_step", request(runtime, "step", buildJsonObject {
                put("session_id", session); put("dt", 1.0 / 60); put("steps", 60)
            }))
            assertEquals(60, pulled.getValue("serial").jsonPrimitive.int)
            val peak = pulled.getValue("peaks").jsonObject.getValue("0").jsonPrimitive.float
            assertTrue(peak > 0.01f, "A full pull must swing the output: $pulled")
            // Queries never advance the clock.
            assertEquals(pulled, operations.call("physics_audition_get", buildJsonObject { put("session_id", session) }))
            assertEquals(history, runtime.history())
            assertEquals(auxiliary, runtime.capture().auxiliary)

            // A rejected step leaves the pendulum where it was.
            assertFails { operations.call("physics_audition_step", request(runtime, "bad", buildJsonObject {
                put("session_id", session); put("dt", 1.0 / 60); putJsonObject("values") { put("Missing", 1) }
            })) }
            assertEquals(pulled, host.physicsAudition(session))

            val c = runtime.capture()
            assertTrue(WorkspacePhysicsCommands(runtime).execute(c.projectId, c.state, WorkspaceDocumentOperation("physics_fit", buildJsonObject {
                put("id", "spring"); putJsonObject("observed_peaks") { put("0", peak) }
            }), "Fit", MutationAuthor.USER).mutation.applied)
            val scale = runtime.capture().document.rigEdits.physicsEdits.single { it.id == "spring" }.outputs.single().scale
            assertEquals(kotlin.math.round(0.5f / peak * 1000f) / 1000f, scale)

            // The edit retunes the running session, which keeps its clock instead of starting over.
            val retuned = operations.call("physics_audition_step", request(runtime, "after-fit", buildJsonObject {
                put("session_id", session); put("dt", 1.0 / 60)
            }))
            assertEquals(61, retuned.getValue("serial").jsonPrimitive.int)
            assertFalse(retuned.getValue("stale").jsonPrimitive.boolean)
        }
    }

    @Test fun deletedGroupsAndReloadsInvalidateAndAStartStopsTheWorkspacesPreviousSession() = runBlocking<Unit> {
        val runtime = fixture()
        val host = Host(runtime)
        fun control(fields: JsonObject) = host.controlPhysicsAudition(JsonObject(fields + ("state" to JsonPrimitive(runtime.capture().state))))
        val first = control(buildJsonObject { put("mode", "start"); put("group_id", "spring") }).getValue("session_id").jsonPrimitive.content
        val second = control(buildJsonObject { put("mode", "start"); put("group_id", "spring") }).getValue("session_id").jsonPrimitive.content
        assertEquals("stopped", host.physicsAudition(first).getValue("status").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { control(buildJsonObject { put("mode", "reset"); put("session_id", first) }) }
        assertFailsWith<IllegalArgumentException> { control(buildJsonObject { put("mode", "start"); put("group_id", "absent") }) }
        assertFailsWith<IllegalArgumentException> { control(buildJsonObject { put("mode", "target"); put("session_id", second); put("x", 2); put("y", 0) }) }
        assertFailsWith<WorkspaceConflict> {
            host.controlPhysicsAudition(buildJsonObject { put("mode", "reset"); put("session_id", second); put("state", "old") })
        }

        val c = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(c.projectId, c.state, "Drop group",
            listOf(WorkspaceDocumentOperation("physics_delete", buildJsonObject { put("id", "spring") })), MutationAuthor.USER)
        assertTrue(host.physicsAudition(second).getValue("stale").jsonPrimitive.boolean)
        assertFailsWith<IllegalArgumentException> {
            host.stepPhysicsAudition(buildJsonObject { put("session_id", second); put("dt", 0.01); put("state", runtime.capture().state) })
        }

        val reopened = runtime.capture()
        runtime.install(reopened.state, reopened.projectId, reopened.document, reopened.model, runtime.history(), discardUnsaved = true)
        assertTrue(host.physicsAudition(second).getValue("stale").jsonPrimitive.boolean)
        assertFailsWith<WorkspaceConflict> {
            host.controlPhysicsAudition(buildJsonObject { put("mode", "reset"); put("session_id", second); put("state", runtime.capture().state) })
        }
    }
}
