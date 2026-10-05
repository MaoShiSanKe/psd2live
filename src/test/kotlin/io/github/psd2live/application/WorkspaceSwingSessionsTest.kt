package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class WorkspaceSwingSessionsTest {
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) })
        simulationFixture(runtime)
        return runtime
    }

    @Test fun draftsShapesHandlesAndPlaybackKeepTheCommittedRigPoseAndHistoryUntouched() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history(); val sessions = WorkspaceSwingSessions(runtime)
        val target = before.model.rig.puppet.drawables.single().id.raw
        val begun = sessions.execute(before.projectId, before.state, "first", buildJsonObject {
            put("mode", "begin"); putJsonArray("targets") { add(target) }
        })
        val id = begun.report.getValue("session_id").jsonPrimitive.content
        assertTrue(begun.model.rig.puppet.deformers.size > before.model.rig.puppet.deformers.size)
        assertTrue(begun.model.rig.puppet.parameters.any { it.id.raw in begun.draft.parameterIds })
        validateOperationSchema(begun.report, WorkspaceSwingSessionSchemas.result)
        suspend fun control(mode: String, fields: JsonObject = JsonObject(emptyMap())) = sessions.execute(before.projectId, before.state, "first",
            JsonObject(fields + buildJsonObject { put("mode", mode); put("session_id", id) }))
        val both = control("kinds", buildJsonObject { putJsonArray("kinds") { add("LATERAL"); add("VERTICAL") } })
        assertEquals(2, both.draft.motions.size)
        val segmented = control("segments", buildJsonObject { put("motion", 1); put("segments", 3) })
        assertEquals(3, segmented.draft.motions[1].segments)
        val preset = control("preset", buildJsonObject { put("preset", "CLOTH") })
        assertEquals(SwingPresets.shape(SwingPreset.CLOTH, SwingKind.LATERAL), preset.draft.motions[0].shape)
        val disabled = control("physics", buildJsonObject { put("enabled", false) })
        assertFalse(disabled.draft.hasPhysics)
        val selected = control("select", buildJsonObject { put("motion", 1) })
        val gizmo = SwingGizmo.of(selected.model.rig.puppet, selected.prepared, values = selected.values, motion = 1)!!
        val tip = gizmo.handles().getValue(SwingGizmo.Handle.TIP)
        val handle = control("handle", buildJsonObject { put("handle", "TIP"); putJsonArray("point") { add(tip.first + 5); add(tip.second + 5) } })
        assertNotEquals(selected.draft.motions[1].shape, handle.draft.motions[1].shape)
        control("play", buildJsonObject { put("enabled", true) })
        val fixed = sessions.get("first", id, 0.4f)
        assertEquals(1f, fixed.values.getValue(ParameterId(fixed.draft.motions.first().parameterIds.first())), 0.00001f)
        assertNotEquals(fixed.values.getValue(ParameterId(fixed.draft.motions[1].parameterIds.first())),
            fixed.values.getValue(ParameterId(fixed.draft.motions[1].parameterIds.last())))
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        assertFailsWith<IllegalArgumentException> { sessions.get("foreign", id) }
        assertFailsWith<IllegalArgumentException> { sessions.get("first", id, Float.NaN) }
        val cancelled = control("cancel")
        assertEquals("cancelled", cancelled.report.getValue("status").jsonPrimitive.content)
        assertFalse(cancelled.playing); assertEquals(cancelled.report, sessions.get("first", id).report)
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun staleOrCancelledDraftsCannotCommitAndSuccessfulCommitUsesOneHistoryNodeAndRebuilds() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val sessions = WorkspaceSwingSessions(runtime)
        val begun = sessions.execute(root.projectId, root.state, "first", buildJsonObject {
            put("mode", "begin"); putJsonArray("targets") { add(root.model.rig.puppet.drawables.single().id.raw) }
        })
        val id = begun.report.getValue("session_id").jsonPrimitive.content
        val foreign = runtime.updateAuxiliary(root.projectId, root.state, buildJsonObject { put("foreign", true) })
        assertTrue(sessions.get("first", id).report.getValue("stale").jsonPrimitive.boolean)
        assertFailsWith<WorkspaceConflict> { sessions.execute(root.projectId, foreign.state, "first", buildJsonObject { put("mode", "commit"); put("session_id", id) }) }
        assertEquals(foreign, runtime.capture())
        val cancelled = sessions.execute(root.projectId, foreign.state, "first", buildJsonObject { put("mode", "cancel"); put("session_id", id) })
        assertEquals("cancelled", cancelled.report.getValue("status").jsonPrimitive.content)
        val next = sessions.execute(root.projectId, foreign.state, "first", buildJsonObject {
            put("mode", "begin"); putJsonArray("targets") { add(root.model.rig.puppet.drawables.single().id.raw) }
        })
        val history = runtime.history()
        val committed = sessions.execute(root.projectId, foreign.state, "first", buildJsonObject {
            put("mode", "commit"); put("session_id", next.report.getValue("session_id"))
        }, MutationAuthor.USER)
        assertEquals("committed", committed.report.getValue("status").jsonPrimitive.content)
        assertEquals(history.selections.size + 1, runtime.history().selections.size)
        assertEquals(runtime.capture().document.rigEdits.swingEdits.single().parameterIds, committed.draft.parameterIds)
        val rebuilt = WorkspacePreviewBuilder().build(runtime.capture().document)
        assertEquals(runtime.capture().model.rig.puppet.parameters, rebuilt.rig.puppet.parameters)
        assertEquals(runtime.capture().model.rig.puppet.deformers.map { it.id }, rebuilt.rig.puppet.deformers.map { it.id })
        assertFailsWith<IllegalArgumentException> { sessions.execute(root.projectId, runtime.capture().state, "first", buildJsonObject {
            put("mode", "commit"); put("session_id", next.report.getValue("session_id"))
        }) }
    }

    @Test fun processJobRetainsSwingCommitAndRetriesAfterLateHostCancellation() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val sessions = WorkspaceSwingSessions(runtime)
        val begun = sessions.execute(root.projectId, root.state, "first", buildJsonObject {
            put("mode", "begin"); putJsonArray("targets") { add(root.model.rig.puppet.drawables.single().id.raw) }
        })
        val host = object : WorkspaceBackendStub() {
            override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
            override fun snapshot() = captureQueries().snapshot()
            override suspend fun controlSwingPreview(arguments: JsonObject, author: MutationAuthor): WorkspaceSwingPreview {
                sessions.execute(runtime.capture().projectId, arguments.getValue("state").jsonPrimitive.content, "first", arguments, author)
                throw CancellationException("Late UI refresh cancelled")
            }
        }
        WorkspaceOperations(host).use { operations ->
            val input = buildJsonObject { put("project_id", root.projectId); put("state", root.state); put("request_id", "commit-swing")
                put("session_id", begun.report.getValue("session_id")) }
            val context = WorkspaceOperationContext(MutationAuthor.AGENT)
            val job = operations.registry.invoke("swing_preview_commit", input, context).data
            val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, context).data
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
            assertEquals("committed", terminal.getValue("result").jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(job.getValue("id"), operations.registry.invoke("swing_preview_commit", input, context).data.getValue("id"))
            validateOperationSchema(terminal.getValue("result"), WorkspaceSwingSessionSchemas.result)
            assertEquals(1, runtime.capture().document.rigEdits.swingEdits.size)
        }
    }
}
