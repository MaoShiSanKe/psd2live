package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class WorkspacePreviewCommandsTest {
    private val axis = ParameterId("Axis")
    private val other = ParameterId("Other")
    private val builder = WorkspacePreviewBuilder()
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Parameters", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", axis.raw); put("name", "Axis"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", other.raw); put("name", "Other"); put("min", -10); put("max", 10); put("default", 2) })), MutationAuthor.USER)
        return runtime
    }
    private fun set(value: Float) = buildJsonObject { put("mode", "set"); putJsonObject("values") { put(axis.raw, value) } }
    private fun pose(runtime: WorkspaceRuntime<RigPreviewModel>, scope: String = "first") =
        PreviewSessions.read(runtime.capture().model.rig.puppet.parameters, runtime.capture().auxiliary, scope)

    @Test fun partialEditsUseCommittedPoseAndKeepUnspecifiedValuesLocksAndOtherWorkspaces() = runBlocking<Unit> {
        val runtime = fixture(); val commands = WorkspacePreviewCommands(runtime); val before = runtime.capture()
        commands.authored(before.projectId, before.state, mapOf("first" to WorkspacePose(mapOf(axis to 0.7f, other to 8f), setOf(other)),
            "second" to WorkspacePose(mapOf(axis to -0.7f, other to -8f), emptySet())))
        val captured = runtime.capture(); val read = WorkspaceReadSession(runtime.read(), WorkspaceQueryPresentation(workspaceId = "first"))
        val history = runtime.history()
        val result = commands.edit(captured.projectId, captured.state, "first", set(0.3f))
        assertEquals(0.3f, pose(runtime).values.getValue(axis)); assertEquals(8f, pose(runtime).values.getValue(other))
        assertEquals(setOf(other), pose(runtime).locked); assertEquals(-0.7f, pose(runtime, "second").values.getValue(axis))
        assertEquals(0.7f, read.previewSession().getValue("values").jsonObject.getValue(axis.raw).jsonPrimitive.float)
        assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
        assertEquals(history, runtime.history()); assertEquals(captured.document, runtime.capture().document)
        assertEquals(captured.revision, runtime.capture().revision)
    }

    @Test fun logicalNoopProjectsWithoutCreatingARecordDirtyVersionOrHistoryNode() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        var projected = 0
        val commands = WorkspacePreviewCommands(runtime)
        val result = commands.edit(before.projectId, before.state, "first", buildJsonObject { put("mode", "reset") }) { capture, pose, changed ->
            assertEquals(before, capture); assertFalse(changed); assertEquals(2f, pose.values.getValue(other)); projected++
        }
        assertEquals(1, projected); assertEquals(before.state, result.getValue("state").jsonPrimitive.content)
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        assertFailsWith<IllegalStateException> { commands.edit(before.projectId, before.state, "first", set(0f)) { _, _, _ -> error("Reject projection") } }
        assertEquals(before, runtime.capture())
    }

    @Test fun snapshotsRespectCommittedLocksClampOldRangesAndPreserveUnmentionedValues() = runBlocking<Unit> {
        val runtime = fixture(); val commands = WorkspacePreviewCommands(runtime); val before = runtime.capture()
        commands.authored(before.projectId, before.state, mapOf("first" to WorkspacePose(mapOf(axis to -0.5f, other to 7f), setOf(axis))))
        val current = runtime.capture()
        val snapshots = WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData(listOf(
            ParameterSnapshot("saved", 1, "Saved", mapOf(axis to 2f, ParameterId("removed") to 5f)))))
        val saved = runtime.updateAuxiliary(current.projectId, current.state, JsonObject(current.auxiliary + snapshots + ("extension" to JsonPrimitive("keep"))))
        commands.snapshot(saved.projectId, saved.state, "first", "saved")
        assertEquals(saved, runtime.capture()); assertEquals(-0.5f, pose(runtime).values.getValue(axis))
        val unlocked = commands.edit(saved.projectId, saved.state, "first", buildJsonObject { put("mode", "set"); putJsonObject("locks") { put(axis.raw, false) } })
        commands.snapshot(saved.projectId, unlocked.getValue("state").jsonPrimitive.content, "first", "saved")
        assertEquals(1f, pose(runtime).values.getValue(axis)); assertEquals(7f, pose(runtime).values.getValue(other))
        assertEquals(JsonPrimitive("keep"), runtime.capture().auxiliary["extension"])
        assertEquals(saved.document, runtime.capture().document)
    }

    @Test fun staleStateInvalidValuesAndRejectedProjectionNeverChangeTheAuthoritativePose() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val commands = WorkspacePreviewCommands(runtime)
        assertFailsWith<WorkspaceConflict> { commands.edit(before.projectId, "stale", "first", JsonObject(emptyMap())) }
        assertFailsWith<WorkspaceConflict> { commands.snapshot(before.projectId, "stale", "first", "missing") }
        assertFailsWith<IllegalArgumentException> { commands.edit("other-project", before.state, "first", set(0.2f)) }
        for (bad in listOf(set(2f), buildJsonObject { put("mode", "set"); putJsonObject("values") { put("missing", 0) } },
            buildJsonObject { put("mode", "reset"); putJsonObject("values") { put(axis.raw, 0) } },
            buildJsonObject { put("mode", "set"); putJsonObject("locks") { put(axis.raw, "yes") } }))
            assertFails { commands.edit(before.projectId, before.state, "first", bad) }
        assertFailsWith<IllegalStateException> { commands.edit(before.projectId, before.state, "first", set(0.2f)) { _, _, _ -> error("Projection rejected") } }
        assertEquals(before, runtime.capture())
        val foreign = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
        assertFailsWith<WorkspaceConflict> { commands.authored(before.projectId, before.state, mapOf("first" to WorkspacePose(mapOf(axis to 1f), emptySet()))) }
        assertEquals(foreign, runtime.capture())
    }

    @Test fun legacyPoseNormalizationIsSharedByQueriesAndEditsWithoutReadTimeUpgrade() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val legacy = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { putJsonObject("posesByWorkspace") {
            putJsonObject("first") { putJsonObject("values") { put(axis.raw, 9); put(other.raw, "NaN"); put("removed", 4) }
                put("locked", buildJsonArray { add(axis.raw); add("removed") }) }
        } })
        val read = WorkspaceReadSession(runtime.read(), WorkspaceQueryPresentation(workspaceId = "first"))
        assertEquals(PreviewSessions.encode(pose(runtime)), read.previewSession())
        assertEquals(1f, pose(runtime).values.getValue(axis)); assertEquals(2f, pose(runtime).values.getValue(other)); assertEquals(setOf(axis), pose(runtime).locked)
        val result = WorkspacePreviewCommands(runtime).edit(legacy.projectId, legacy.state, "first", set(1f))
        assertEquals(legacy.state, result.getValue("state").jsonPrimitive.content)
        assertEquals(legacy, runtime.capture())
    }

    @Test fun frozenGuiPosesNormalizeTogetherAndReopeningTheSameProjectRejectsTheOldBoundary() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val commands = WorkspacePreviewCommands(runtime)
        val mutable = mutableMapOf(axis to 5f, other to 3f)
        val committed = commands.authored(before.projectId, before.state, mapOf("first" to WorkspacePose(mutable, setOf(axis, ParameterId("removed")))))
        mutable[axis] = -1f
        assertEquals(1f, pose(runtime).values.getValue(axis)); assertEquals(setOf(axis), pose(runtime).locked)
        assertEquals(committed, commands.authored(committed.projectId, committed.state, mapOf("first" to pose(runtime))))
        val history = runtime.history()
        val reloaded = runtime.install(committed.state, committed.projectId, committed.document, committed.model, history,
            committed.auxiliary, discardUnsaved = true)
        assertFailsWith<WorkspaceConflict> { commands.edit(committed.projectId, committed.state, "first", set(0.2f)) }
        assertEquals(reloaded, runtime.capture()); assertEquals(history, runtime.history())
    }
}
