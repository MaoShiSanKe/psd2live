package io.github.psd2live.application

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceRuntimeTest {
    private fun document() = WorkspaceDocument(WorkspaceSourceArt(32, 32, emptyList(), emptyList()),
        emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)

    private fun setting(name: String, value: Int) = WorkspaceDocumentEdit<String> { doc, _ ->
        doc.copy(settings = JsonObject(doc.settings + (name to JsonPrimitive(value))))
    }

    private fun open(runtime: WorkspaceRuntime<String>) = runtime.install(runtime.state.value.state,
        "project", document(), "initial")

    @Test fun batchUsesOneHistoryNodeAndEachEditSeesItsPredecessor() = runBlocking {
        val runtime = WorkspaceRuntime<String>({ it.settings.toString() })
        val before = open(runtime)
        val result = runtime.execute("project", before.state, "Two edits", MutationAuthor.AGENT, listOf(
            setting("first", 1), WorkspaceDocumentEdit { doc, model ->
                assertTrue("first" in doc.settings)
                assertTrue("first" in model)
                doc.copy(settings = JsonObject(doc.settings + ("second" to JsonPrimitive(2))))
            }))
        assertTrue(result.applied)
        assertEquals(2, runtime.history().selections.size)
        assertEquals(before.historyHead, runtime.history().selections.last().node.parentId)
        assertEquals(2, result.capture.document.settings.getValue("second").jsonPrimitive.int)
    }

    @Test fun rejectedBatchAndFailedRebuildKeepTheEntireOriginalCapture() = runBlocking {
        val runtime = WorkspaceRuntime<String>({ if ("fail" in it.settings) error("Cannot build") else "candidate" })
        val before = open(runtime)
        assertFailsWith<IllegalArgumentException> {
            runtime.execute("project", before.state, "Rejected", MutationAuthor.USER,
                listOf(setting("first", 1), WorkspaceDocumentEdit { _, _ -> throw IllegalArgumentException("Bad target") }))
        }
        assertEquals(before, runtime.capture())
        assertFailsWith<IllegalStateException> {
            runtime.execute("project", before.state, "Failed", MutationAuthor.USER, listOf(setting("fail", 1)))
        }
        assertEquals(before, runtime.capture())
        assertEquals(1, runtime.history().selections.size)
    }

    @Test fun concurrentEditMakesSlowCandidateConflictWithoutOverwritingNewWork() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = WorkspaceRuntime<String>({ it.settings.toString() })
        val before = open(runtime)
        val slow = async {
            runCatching { runtime.execute("project", before.state, "Slow", MutationAuthor.AGENT,
                listOf(WorkspaceDocumentEdit { doc, _ -> entered.complete(Unit); release.await(); doc.copy(deletedLayerIds = setOf("old")) })) }
        }
        entered.await()
        val fast = runtime.execute("project", before.state, "Fast", MutationAuthor.USER, listOf(setting("new", 1)))
        release.complete(Unit)
        assertIs<WorkspaceConflict>(slow.await().exceptionOrNull())
        assertEquals(fast.capture, runtime.capture())
        assertEquals(2, runtime.history().selections.size)
    }

    @Test fun noOpDoesNotRebuildAdvanceStateOrCreateHistory() = runBlocking {
        var rebuilds = 0
        val runtime = WorkspaceRuntime<String>({ rebuilds++; "model" })
        val before = open(runtime)
        val result = runtime.execute("project", before.state, "No change", MutationAuthor.AGENT,
            listOf(WorkspaceDocumentEdit { doc, _ -> doc.copy() }))
        assertFalse(result.applied)
        assertEquals(before, result.capture)
        assertEquals(0, rebuilds)
        assertEquals(1, runtime.history().selections.size)
    }

    @Test fun checkoutKeepsBranchesAndReopeningInvalidatesOldState() = runBlocking {
        val runtime = WorkspaceRuntime<String>({ it.settings.toString() })
        val root = open(runtime)
        val branchA = runtime.execute("project", root.state, "A", MutationAuthor.AGENT, listOf(setting("a", 1))).capture
        val restored = runtime.checkout("project", branchA.state, root.historyHead)
        val branchB = runtime.execute("project", restored.state, "B", MutationAuthor.USER, listOf(setting("b", 1))).capture
        val history = runtime.history()
        assertEquals(3, history.selections.size)
        assertEquals(2, history.selections.count { it.node.parentId == root.historyHead })
        val reopened = runtime.install(branchB.state, "project", branchB.document, branchB.model, history, discardUnsaved = true)
        assertEquals(branchB.historyHead, reopened.historyHead)
        assertNotEquals(branchB.state, reopened.state)
        assertFailsWith<WorkspaceConflict> { runtime.execute("project", branchB.state, "Stale", MutationAuthor.AGENT, listOf(setting("stale", 1))) }
        Unit
    }

    @Test fun auxiliaryChangesAreDurableWithoutRigHistoryAndOldSavesCannotClearThem() {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val before = open(runtime)
        val auxiliary = runtime.updateAuxiliary("project", before.state, buildJsonObject { put("snapshot", "pose") })
        assertTrue(auxiliary.dirty)
        assertNotEquals(before.state, auxiliary.state)
        assertEquals(before.historyHead, auxiliary.historyHead)
        assertFalse(runtime.saved(before.state))
        assertTrue(runtime.capture().dirty)
        assertTrue(runtime.saved(auxiliary.state))
        assertFalse(runtime.capture().dirty)
    }

    @Test fun unsavedSwitchRequiresExplicitDiscardAndWrongProjectCannotWrite() = runBlocking {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val before = open(runtime)
        val changed = runtime.execute("project", before.state, "Change", MutationAuthor.USER, listOf(setting("a", 1))).capture
        assertFailsWith<IllegalStateException> { runtime.close(changed.state) }
        assertFailsWith<IllegalArgumentException> { runtime.execute("other", changed.state, "Wrong", MutationAuthor.AGENT, listOf(setting("b", 1))) }
        assertEquals(changed, runtime.capture())
        runtime.close(changed.state, discardUnsaved = true)
        assertNull(runtime.state.value.capture)
    }

    @Test fun cancellationBeforeCommitLeavesNoPartialHistory() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtime = WorkspaceRuntime<String>({ entered.complete(Unit); awaitCancellation() })
        val before = open(runtime)
        val operation = launch { runtime.execute("project", before.state, "Cancel", MutationAuthor.AGENT, listOf(setting("a", 1))) }
        entered.await()
        operation.cancelAndJoin()
        assertEquals(before, runtime.capture())
        assertEquals(1, runtime.history().selections.size)
    }
    @Test fun projectionFailureRejectsPreparedCommitCheckpointAndCheckout() = runBlocking {
        val runtime = WorkspaceRuntime<String>({ it.settings.toString() })
        val root = open(runtime)
        val candidate = root.document.copy(settings = buildJsonObject { put("value", 1) })
        val reject: (WorkspaceCapture<String>, WorkspaceDocument, String) -> Unit = { _, _, _ ->
            throw IllegalStateException("Projection changed")
        }
        assertFailsWith<IllegalStateException> {
            runtime.commitPrepared("project", root.state, "Rejected", MutationAuthor.AGENT,
                candidate, "candidate", beforeCommit = reject)
        }
        assertFailsWith<IllegalStateException> {
            runtime.commitPrepared("project", root.state, "Rejected checkpoint", MutationAuthor.USER,
                root.document, root.model, checkpoint = true, beforeCommit = reject)
        }
        assertEquals(root, runtime.capture())
        assertEquals(1, runtime.history().selections.size)
        val edited = runtime.commitPrepared("project", root.state, "Accepted", MutationAuthor.USER,
            candidate, "candidate").capture
        assertFailsWith<IllegalStateException> {
            runtime.checkout("project", edited.state, root.historyHead, beforeCommit = reject)
        }
        assertEquals(edited, runtime.capture())
        assertEquals(edited.historyHead, runtime.history().headNodeId)
        assertEquals(2, runtime.history().selections.size)
    }

    @Test fun rejectedInstallationKeepsGenerationHistoryAndAuxiliary() {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val root = open(runtime)
        val before = runtime.updateAuxiliary("project", root.state, buildJsonObject { put("annotation", "keep") })
        val history = runtime.history()
        assertFailsWith<IllegalStateException> {
            runtime.install(before.state, "replacement", document(), "replacement", discardUnsaved = true,
                beforeInstall = { throw IllegalStateException("Desktop changed while loading") })
        }
        assertEquals(before, runtime.capture())
        assertEquals(history, runtime.history())
        val installed = runtime.install(before.state, "replacement", document(), "replacement", discardUnsaved = true)
        assertNotEquals(before.state, installed.state)
        assertEquals("replacement", installed.projectId)
    }

    @Test fun preparedCommitPreservesActorTaskAndIgnoresNoOpProjection() {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val before = open(runtime)
        val noOp = runtime.commitPrepared("project", before.state, "No change", MutationAuthor.AGENT,
            before.document.copy(), "unused", beforeCommit = { _, _, _ -> error("No-op must not publish") })
        assertEquals(before, noOp.capture)
        assertFalse(noOp.applied)
        val result = runtime.commitPrepared("project", before.state, "Edited", MutationAuthor.USER,
            before.document.copy(deletedLayerIds = setOf("deleted")), "model", taskId = "task")
        val node = runtime.history().selections.single { it.node.id == result.capture.historyHead }.node
        assertEquals("user", node.actor)
        assertEquals("task", node.taskId)
    }

    @Test fun preparedDocumentAndAuxiliaryPublishAtomicallyOrRollBackTogether() {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val before = open(runtime)
        val document = before.document.copy(deletedLayerIds = setOf("removed"))
        val auxiliary = buildJsonObject { put("pose", 0) }
        assertFailsWith<IllegalStateException> {
            runtime.commitPrepared("project", before.state, "Import", MutationAuthor.USER, document, "imported",
                auxiliary = auxiliary, beforeCommit = { _, _, _ -> error("Projection rejected") })
        }
        assertEquals(before, runtime.capture())
        assertEquals(1, runtime.history().selections.size)
        val committed = runtime.commitPrepared("project", before.state, "Import", MutationAuthor.USER, document, "imported", auxiliary = auxiliary)
        assertEquals(document, committed.capture.document)
        assertEquals(auxiliary, committed.capture.auxiliary)
        assertEquals(2, runtime.history().selections.size)
    }

    @Test fun auxiliaryOnlyPreparedCommitKeepsLegacyHistoryRevisionAndCreatesNoRigNode() {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val history = io.github.psd2live.history.WorkspaceHistoryTree(document(), "legacy-revision", "legacy-hash")
        val before = runtime.install(runtime.state.value.state, "project", document(), "model", history = history.state())
        val auxiliary = buildJsonObject { put("pose", 0) }
        val result = runtime.commitPrepared("project", before.state, "Reset pose", MutationAuthor.USER,
            before.document, before.model, auxiliary = auxiliary)
        assertTrue(result.applied)
        assertNotEquals(before.state, result.capture.state)
        assertEquals("legacy-revision", result.capture.revision)
        assertEquals(before.historyHead, result.capture.historyHead)
        assertEquals(history.state(), runtime.history())
        assertEquals(auxiliary, result.capture.auxiliary)
        assertFalse(runtime.commitPrepared("project", result.capture.state, "No change", MutationAuthor.USER,
            result.capture.document, result.capture.model, auxiliary = auxiliary).applied)
    }

    @Test fun saveCaptureRejectsHistoryFromAnotherVersion() = runBlocking {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val root = open(runtime)
        val changed = runtime.execute("project", root.state, "Changed", MutationAuthor.USER,
            listOf(setting("value", 1))).capture
        assertFailsWith<WorkspaceConflict> { runtime.history(root.state) }
        assertEquals(changed.historyHead, runtime.history(changed.state).headNodeId)
    }


    @Test fun rejectedAuxiliaryProjectionPreservesStateAndNoOpSkipsProjection() {
        val runtime = WorkspaceRuntime<String>({ "model" })
        val before = open(runtime)
        val candidate = buildJsonObject { put("annotation", "changed") }
        assertFailsWith<IllegalStateException> {
            runtime.updateAuxiliary("project", before.state, candidate) { _, _ -> error("Projection changed") }
        }
        assertEquals(before, runtime.capture())
        assertEquals(before, runtime.updateAuxiliary("project", before.state, before.auxiliary) { _, _ -> error("No-op") })
        assertEquals(1, runtime.history().selections.size)
    }
}
