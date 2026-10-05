package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceSkeletonDraftSessionsTest {
    private val builder = WorkspacePreviewBuilder()
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> =
        WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }).also { skeletonDraftFixture(it) }

    /** Moves the workspace's authored pose off rest, as a slider would before the Edit tool opens. */
    private fun posed(runtime: WorkspaceRuntime<RigPreviewModel>, workspace: String = "main"): String {
        val before = runtime.capture()
        val parameter = before.model.rig.puppet.parameters.first { it.max > it.default }
        return WorkspacePreviewCommands(runtime).edit(before.projectId, before.state, workspace, buildJsonObject {
            put("mode", "set"); putJsonObject("values") { put(parameter.id.raw, parameter.max) }
        }).getValue("state").jsonPrimitive.content
    }
    private fun pose(runtime: WorkspaceRuntime<RigPreviewModel>, workspace: String = "main") =
        PreviewSessions.read(runtime.capture().model.rig.puppet.parameters, runtime.capture().auxiliary, workspace)
    private val move = SkeletonDraftIntent.Transform(setOf("arm_upper_l"), dx = 6f, dy = -4f, descendants = true)

    @Test fun openingCommitsItsOwnRestPoseAndTheDraftCommitsOnThatLineage() = runBlocking<Unit> {
        val runtime = fixture(); val sessions = WorkspaceSkeletonDraftSessions(runtime)
        val preReset = posed(runtime)
        val history = runtime.history()
        val projected = mutableListOf<Boolean>()
        val opened = sessions.open(runtime.capture().projectId, preReset, "main") { _, _, changed -> projected += changed }
        assertEquals(listOf(true), projected)
        assertNotEquals(preReset, opened.state)
        assertEquals(runtime.capture().state, opened.state)
        assertEquals(history, runtime.history(), "the rest pose is auxiliary and adds no history node")
        val parameters = runtime.capture().model.rig.puppet.parameters
        assertEquals(parameters.associate { it.id to it.default }, pose(runtime).values)
        assertFalse(opened.stale)
        validateOperationSchema(opened.toJson(), WorkspaceSkeletonDraftSchemas.session)

        // The pre-reset token is not this draft's lineage.
        assertFailsWith<WorkspaceConflict> { sessions.edit(opened.id, preReset, opened.sessionState, listOf(move)) }
        val edited = sessions.edit(opened.id, opened.state, opened.sessionState, listOf(move,
            SkeletonDraftIntent.Subdivide("arm_fore_l", 2)))
        assertEquals(1, edited.revision)
        assertEquals(runtime.capture().state, opened.state, "drafting writes nothing")
        assertFailsWith<WorkspaceConflict> { sessions.commit(opened.id, opened.state, opened.sessionState, MutationAuthor.USER) }

        val committed = sessions.commit(opened.id, edited.state, edited.sessionState, MutationAuthor.USER)
        assertTrue(committed.mutation.applied)
        assertEquals("committed", committed.session.status)
        assertEquals(runtime.capture().state, committed.session.state)
        assertEquals(edited.draft, runtime.capture().document.rigEdits.skeleton)
        assertEquals(history.selections.size + 1, runtime.history().selections.size)
        assertEquals("Edit skeleton", runtime.history().selections.last().node.summary)
        assertEquals(runtime.capture().model.rig.puppet.parameters.associate { it.id to it.default }, pose(runtime).values)
        assertFailsWith<IllegalStateException> { sessions.edit(opened.id, committed.session.state, committed.session.sessionState, listOf(move)) }
        assertFailsWith<IllegalStateException> { sessions.commit(opened.id, committed.session.state, committed.session.sessionState, MutationAuthor.USER) }
    }

    @Test fun externalPoseAndDocumentChangesAfterOpeningRejectTheCommitWithoutWriting() = runBlocking<Unit> {
        val runtime = fixture(); val sessions = WorkspaceSkeletonDraftSessions(runtime)
        for (external in listOf("pose", "document")) {
            val start = runtime.capture()
            val opened = sessions.open(start.projectId, start.state, "main")
            val edited = sessions.edit(opened.id, opened.state, opened.sessionState, listOf(move))
            val afterExternal = if (external == "pose") posed(runtime) else WorkspaceDocumentCommands(runtime).execute(start.projectId,
                runtime.capture().state, "Settings", listOf(WorkspaceDocumentOperation("settings_update", buildJsonObject {
                    putJsonObject("changes") { put("texturePadding", 5) } })), MutationAuthor.AGENT).capture.state
            val capture = runtime.capture(); val history = runtime.history()
            assertTrue(sessions.get(opened.id).stale)
            val conflict = assertFailsWith<WorkspaceConflict> { sessions.commit(opened.id, edited.state, edited.sessionState, MutationAuthor.USER) }
            assertEquals(opened.state, conflict.expectedState); assertEquals(afterExternal, conflict.actualState)
            // Carrying the live token instead of the lineage is not a way around the conflict.
            assertFailsWith<WorkspaceConflict> { sessions.commit(opened.id, afterExternal, edited.sessionState, MutationAuthor.USER) }
            assertEquals(capture, runtime.capture()); assertEquals(history, runtime.history())
            assertEquals("active", sessions.get(opened.id).status)
            assertEquals(start.document.rigEdits.skeleton, runtime.capture().document.rigEdits.skeleton)
            assertEquals("cancelled", sessions.cancel(opened.id).status)
            assertEquals(capture, runtime.capture())
        }
    }

    @Test fun reopeningTheProjectOrAnotherDraftSupersedesTheOldLineage() = runBlocking<Unit> {
        val runtime = fixture(); val sessions = WorkspaceSkeletonDraftSessions(runtime)
        val start = runtime.capture()
        val first = sessions.open(start.projectId, start.state, "main")
        // The pose is already at rest, so the second open reuses the same state token; only supersession stops the first.
        val second = sessions.open(start.projectId, first.state, "main")
        assertEquals(first.state, second.state)
        assertEquals("superseded", sessions.get(first.id).status)
        assertFailsWith<IllegalStateException> { sessions.edit(first.id, first.state, first.sessionState, listOf(move)) }
        assertFailsWith<IllegalStateException> { sessions.commit(first.id, first.state, first.sessionState, MutationAuthor.USER) }
        val other = sessions.open(start.projectId, second.state, "other")
        assertEquals("active", sessions.get(second.id).status, "drafts of other workspaces stay independent")

        val edited = sessions.edit(second.id, second.state, second.sessionState, listOf(move))
        val history = runtime.history()
        runtime.install(runtime.capture().state, start.projectId, runtime.capture().document, runtime.capture().model, history, discardUnsaved = true)
        val reopened = runtime.capture()
        assertFailsWith<WorkspaceConflict> { sessions.edit(second.id, second.state, edited.sessionState, listOf(move)) }
        assertFailsWith<WorkspaceConflict> { sessions.commit(second.id, second.state, edited.sessionState, MutationAuthor.USER) }
        assertFailsWith<WorkspaceConflict> { sessions.commit(other.id, other.state, other.sessionState, MutationAuthor.USER) }
        assertEquals(reopened, runtime.capture()); assertEquals(history, runtime.history())
        assertTrue(sessions.get(second.id).stale)
    }

    @Test fun failedEditsPublishNoPrefixAndStaleRevisionsConflict() = runBlocking<Unit> {
        val runtime = fixture(); val sessions = WorkspaceSkeletonDraftSessions(runtime)
        val start = runtime.capture()
        val opened = sessions.open(start.projectId, start.state, "main")
        val failure = assertFailsWith<WorkspaceBatchEditException> {
            sessions.edit(opened.id, opened.state, opened.sessionState, listOf(move, SkeletonDraftIntent.RemoveBone("upper_body")))
        }
        assertEquals(1, failure.index)
        assertEquals(opened.draft, sessions.get(opened.id).draft); assertEquals(0, sessions.get(opened.id).revision)
        val edited = sessions.edit(opened.id, opened.state, opened.sessionState, listOf(move))
        assertFailsWith<WorkspaceConflict> { sessions.edit(opened.id, opened.state, opened.sessionState, listOf(move)) }
        assertEquals(edited.draft, sessions.get(opened.id).draft)

        // Reverting to the opened revision makes the commit an unchanged armature: no history node.
        val reverted = sessions.edit(opened.id, opened.state, edited.sessionState,
            listOf(SkeletonDraftIntent.Restore(requireNotNull(sessions.revision(opened.id, 0)))))
        assertEquals(opened.draft, reverted.draft)
        val history = runtime.history(); val capture = runtime.capture()
        val result = sessions.commit(opened.id, reverted.state, reverted.sessionState, MutationAuthor.USER)
        assertFalse(result.mutation.applied)
        assertEquals(history, runtime.history()); assertEquals(capture, runtime.capture())
    }

    @Test fun committedDraftsReplayFromHistoryAndRebuildTheSameRig() = runBlocking<Unit> {
        val runtime = fixture(); val sessions = WorkspaceSkeletonDraftSessions(runtime)
        val start = runtime.capture()
        val opened = sessions.open(start.projectId, start.state, "main")
        val edited = sessions.edit(opened.id, opened.state, opened.sessionState, listOf(
            SkeletonDraftIntent.Duplicate(setOf("arm_upper_l"), descendants = true, mirror = true),
            SkeletonDraftIntent.PaintWeights("ArtMeshHandwearL", "arm_fore_l", listOf(88f to 55f), 10f, 0.7f, SkeletonWeightBrushMode.ADD),
            SkeletonDraftIntent.OptionalChain(BoneRole.TAIL, false)))
        val committed = sessions.commit(opened.id, edited.state, edited.sessionState, MutationAuthor.USER)
        val after = runtime.capture()
        val rebuilt = builder.build(after.document)
        assertEquals(edited.draft, rebuilt.config.rigEdits.skeleton)
        assertEquals(after.model.rig.puppet.deformers.map { it.id }, rebuilt.rig.puppet.deformers.map { it.id })
        assertEquals(after.model.rig.puppet.parameters.map { it.id }, rebuilt.rig.puppet.parameters.map { it.id })
        assertNotEquals(start.model.rig.puppet.deformers.map { it.id }, after.model.rig.puppet.deformers.map { it.id })

        val nodes = runtime.history().selections.map { it.node.id }
        val restored = runtime.checkout(after.projectId, after.state, nodes[nodes.size - 2])
        assertEquals(start.document.rigEdits.skeleton, restored.document.rigEdits.skeleton)
        val replayed = runtime.checkout(after.projectId, restored.state, committed.mutation.historyNodeId)
        assertEquals(edited.draft, replayed.document.rigEdits.skeleton)
        assertEquals(after.model.rig.puppet.deformers.map { it.id }, replayed.model.rig.puppet.deformers.map { it.id })
        assertEquals(after.model.rig.puppet.parameters.map { it.id }, replayed.model.rig.puppet.parameters.map { it.id })
    }

    @Test fun openingWithoutAnArmatureOrOnAnOldStateWritesNothing() = runBlocking<Unit> {
        val runtime = fixture(); val sessions = WorkspaceSkeletonDraftSessions(runtime)
        val start = runtime.capture()
        val stale = start.state
        posed(runtime)
        val capture = runtime.capture()
        assertFailsWith<WorkspaceConflict> { sessions.open(start.projectId, stale, "main") }
        assertEquals(capture, runtime.capture())
        val bare = WorkspaceDocumentCommands(runtime).execute(start.projectId, capture.state, "Remove skeleton",
            listOf(WorkspaceDocumentOperation("skeleton_put", buildJsonObject { put("spec", SkeletonSpec().toJson()) })), MutationAuthor.AGENT).capture
        assertFailsWith<IllegalArgumentException> { sessions.open(start.projectId, bare.state, "main") }
        assertEquals(bare, runtime.capture())
        assertTrue(sessions.list().isEmpty())
    }
}
