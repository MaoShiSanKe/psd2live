package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceSkeletonDraftOperationsTest {
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)

    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>) : WorkspaceBackendStub() {
        val sessions = WorkspaceSkeletonDraftSessions(runtime)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override suspend fun openSkeletonDraft(state: String) = sessions.open(runtime.capture().projectId, state, "main")
        override fun skeletonDrafts() = sessions.list()
        override fun skeletonDraft(sessionId: String) = sessions.get(sessionId)
        override fun skeletonDraftRevision(sessionId: String, revision: Long) = sessions.revision(sessionId, revision)
        override fun editSkeletonDraft(sessionId: String, state: String, sessionState: String, intents: List<SkeletonDraftIntent>) =
            sessions.edit(sessionId, state, sessionState, intents)
        override fun previewSkeletonWeightTransfer(sessionId: String, transfer: SkeletonDraftIntent.TransferWeights) =
            sessions.weightTransfer(sessionId, transfer)
        override suspend fun commitSkeletonDraft(sessionId: String, state: String, sessionState: String, author: MutationAuthor) =
            sessions.commit(sessionId, state, sessionState, author)
        override fun cancelSkeletonDraft(sessionId: String) = sessions.cancel(sessionId)
    }

    private suspend fun WorkspaceOperations.call(id: String, request: JsonObject) = registry.invoke(id, request, agent).data
    private fun context(runtime: WorkspaceRuntime<RigPreviewModel>, id: String, fields: JsonObject = JsonObject(emptyMap())) =
        JsonObject(fields + buildJsonObject { val captured = runtime.capture()
            put("project_id", captured.projectId); put("state", captured.state); put("request_id", id) })

    @Test fun publicEditsUseTheSameIntentsAndCommitOnTheDraftLineage() = runBlocking<Unit> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) }).also { skeletonDraftFixture(it) }
        val host = Host(runtime)
        WorkspaceOperations(host).use { operations ->
            val definitions = operations.registry.definitions().filter { it.id.startsWith("skeleton_draft_") }
            assertEquals(mapOf("skeleton_draft_open" to WorkspaceOperationKind.SESSION, "skeleton_draft_list" to WorkspaceOperationKind.QUERY,
                "skeleton_draft_get" to WorkspaceOperationKind.QUERY, "skeleton_draft_edit" to WorkspaceOperationKind.SESSION,
                "skeleton_draft_preview_transfer" to WorkspaceOperationKind.QUERY, "skeleton_draft_commit" to WorkspaceOperationKind.DOCUMENT,
                "skeleton_draft_cancel" to WorkspaceOperationKind.SESSION), definitions.associate { it.id to it.kind })
            assertTrue(definitions.none { it.batchable || it.jobBacked })

            val opened = operations.call("skeleton_draft_open", context(runtime, "open"))
            val id = opened.getValue("session_id").jsonPrimitive.content
            val lineage = opened.getValue("state").jsonPrimitive.content
            val start = host.sessions.get(id).draft
            val edits = buildJsonArray {
                add(buildJsonObject { put("kind", "duplicate"); putJsonArray("bone_ids") { add("arm_upper_l") }; put("descendants", true); put("mirror", true) })
                add(buildJsonObject { put("kind", "paint_weights"); put("drawable_id", "ArtMeshHandwearL"); put("bone_id", "arm_fore_l")
                    putJsonArray("points") { add(buildJsonArray { add(88); add(55) }) }; put("radius", 10); put("strength", 0.7); put("mode", "ADD") })
                add(buildJsonObject { put("kind", "subdivide"); put("bone_id", "arm_fore_r"); put("segments", 2) })
            }
            val edited = operations.call("skeleton_draft_edit", context(runtime, "edit", buildJsonObject {
                put("session_id", id); put("session_state", opened.getValue("session_state")); put("edits", edits)
            }))
            val expected = SkeletonDraftEdits.applyAll(start, runtime.capture().model, listOf(
                SkeletonDraftIntent.Duplicate(setOf("arm_upper_l"), descendants = true, mirror = true),
                SkeletonDraftIntent.PaintWeights("ArtMeshHandwearL", "arm_fore_l", listOf(88f to 55f), 10f, 0.7f, SkeletonWeightBrushMode.ADD),
                SkeletonDraftIntent.Subdivide("arm_fore_r", 2)))
            assertEquals(expected.spec, host.sessions.get(id).draft)
            assertEquals(expected.spec.toJson(), edited.getValue("draft"))
            assertEquals(expected.selected?.toList(), edited.getValue("selected").jsonArray.map { it.jsonPrimitive.content })

            // A rejected member leaves the draft and its revision alone.
            val failed = assertFailsWith<WorkspaceBatchEditException> { operations.call("skeleton_draft_edit", context(runtime, "bad", buildJsonObject {
                put("session_id", id); put("session_state", edited.getValue("session_state"))
                putJsonArray("edits") {
                    add(buildJsonObject { put("kind", "rename"); put("bone_id", "head"); put("name", "Skull") })
                    add(buildJsonObject { put("kind", "dissolve"); put("bone_id", "upper_body") })
                }
            })) }
            assertEquals(1, failed.index)
            assertEquals(edited.getValue("session_state"), host.sessions.get(id).toJson().getValue("session_state"))
            assertFailsWith<WorkspaceValidationException> { operations.call("skeleton_draft_edit", context(runtime, "unknown", buildJsonObject {
                put("session_id", id); put("session_state", edited.getValue("session_state"))
                putJsonArray("edits") { add(buildJsonObject { put("kind", "rename"); put("bone_id", "head"); put("name", "x"); put("extra", 1) }) }
            })) }

            val preview = operations.call("skeleton_draft_preview_transfer", buildJsonObject {
                put("session_id", id); putJsonObject("transfer") { put("source_id", "ArtMeshHandwearL"); put("target_id", "ArtMeshHandwearR")
                    put("mode", "NEAREST"); put("tolerance", 20); put("mirror", true) }
            })
            assertTrue(preview.getValue("available").jsonPrimitive.boolean)
            assertEquals(1, operations.call("skeleton_draft_list", JsonObject(emptyMap())).getValue("sessions").jsonArray.size)

            val history = runtime.history()
            val committed = operations.call("skeleton_draft_commit", context(runtime, "commit", buildJsonObject {
                put("session_id", id); put("session_state", edited.getValue("session_state"))
            }))
            assertEquals(lineage, opened.getValue("state").jsonPrimitive.content)
            assertEquals(runtime.capture().state, committed.getValue("state").jsonPrimitive.content)
            assertEquals("committed", committed.getValue("session").jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(history.selections.size + 1, runtime.history().selections.size)
            assertEquals("agent", runtime.history().selections.last().node.actor)
            assertEquals(expected.spec, runtime.capture().document.rigEdits.skeleton)
        }
    }

    @Test fun aStaleDraftCannotBeCommittedThroughTheLiveStateAndStaysCancellable() = runBlocking<Unit> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) }).also { skeletonDraftFixture(it) }
        val host = Host(runtime)
        WorkspaceOperations(host).use { operations ->
            val opened = operations.call("skeleton_draft_open", context(runtime, "open"))
            val id = opened.getValue("session_id").jsonPrimitive.content
            val before = runtime.capture()
            val parameter = before.model.rig.puppet.parameters.first { it.max > it.default }
            WorkspacePreviewCommands(runtime).edit(before.projectId, before.state, "main", buildJsonObject {
                put("mode", "set"); putJsonObject("values") { put(parameter.id.raw, parameter.max) } })
            val capture = runtime.capture(); val history = runtime.history()
            assertTrue(operations.call("skeleton_draft_get", buildJsonObject { put("session_id", id) }).getValue("stale").jsonPrimitive.boolean)
            assertFailsWith<WorkspaceConflict> { operations.call("skeleton_draft_commit", context(runtime, "commit", buildJsonObject {
                put("session_id", id); put("session_state", opened.getValue("session_state"))
            })) }
            assertEquals(capture, runtime.capture()); assertEquals(history, runtime.history())
            val cancelled = operations.call("skeleton_draft_cancel", context(runtime, "cancel", buildJsonObject { put("session_id", id) }))
            assertEquals("cancelled", cancelled.getValue("status").jsonPrimitive.content)
            assertEquals(capture, runtime.capture())
        }
    }
}
