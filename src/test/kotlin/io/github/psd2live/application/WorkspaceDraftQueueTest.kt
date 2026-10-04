package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceDraftQueueTest {
    private fun document(value: Int = 0) = WorkspaceDocument(
        WorkspaceSourceArt(16, 16, emptyList(), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(),
        io.github.psd2live.core.RigEditOverlay(), buildJsonObject { put("value", value) })

    private class Host(rebuild: suspend (WorkspaceDocument) -> String) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = WorkspaceRuntime(rebuild)
        val queue = WorkspaceDraftQueue(runtime, scope)
        override fun close() = scope.cancel()
    }

    private fun Host.open() = runtime.install(runtime.state.value.state, "project", document(), "initial")
    private fun Host.submit(state: String, value: Int,
                            projection: (WorkspaceCapture<String>, WorkspaceDocument, String, WorkspaceDocument?) -> Unit = { _, _, _, _ -> }) =
        queue.submit("project", state, document(value), "Value $value", MutationAuthor.USER, projection)

    @Test fun queuedCompletionsAdvanceOnlyTheirOwnExpectationAndKeepEveryHistoryStep() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        Host { doc ->
            if (doc.settings.getValue("value").jsonPrimitive.int == 1) { entered.complete(Unit); release.await() }
            doc.settings.toString()
        }.use { host ->
            val start = host.open()
            var following: WorkspaceDocument? = null
            val first = host.submit(start.state, 1) { _, _, _, later -> following = later }
            entered.await()
            val second = host.submit(start.state, 2)
            assertFalse(first.isCompleted)
            release.complete(Unit)
            first.await()
            val final = second.await()
            host.queue.awaitIdle()
            assertEquals(document(2), following)
            assertEquals(final.capture, host.runtime.capture())
            assertEquals(listOf(0, 1, 2), host.runtime.history().selections.map {
                it.snapshot.settings.getValue("value").jsonPrimitive.int })
            assertTrue(host.runtime.history().selections.drop(1).all { it.node.actor == "user" })
        }
    }

    @Test fun foreignCommitRejectsTheQueuedChainWithoutOverwritingIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        Host { doc -> entered.complete(Unit); release.await(); doc.settings.toString() }.use { host ->
            val start = host.open()
            val first = host.submit(start.state, 1)
            entered.await()
            val second = host.submit(start.state, 2)
            val foreign = host.runtime.updateAuxiliary("project", start.state, buildJsonObject { put("foreign", true) })
            release.complete(Unit)
            assertFailsWith<WorkspaceConflict> { first.await() }
            assertFailsWith<WorkspaceConflict> { second.await() }
            assertFailsWith<WorkspaceConflict> { host.queue.awaitIdle() }
            assertEquals(foreign, host.runtime.capture())
            assertEquals(1, host.runtime.history().selections.size)
        }
    }

    @Test fun foreignChangeAfterAQueueCommitCannotBeHiddenByItsLineage() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        Host { doc ->
            when (doc.settings.getValue("value").jsonPrimitive.int) {
                1 -> { firstEntered.complete(Unit); firstRelease.await() }
                2 -> { secondEntered.complete(Unit); secondRelease.await() }
            }
            doc.settings.toString()
        }.use { host ->
            val start = host.open()
            val first = host.submit(start.state, 1)
            firstEntered.await()
            val second = host.submit(start.state, 2)
            firstRelease.complete(Unit)
            val committed = first.await()
            secondEntered.await()
            val foreign = host.runtime.updateAuxiliary("project", committed.capture.state, buildJsonObject { put("foreign", true) })
            secondRelease.complete(Unit)
            assertFailsWith<WorkspaceConflict> { second.await() }
            assertEquals(foreign, host.runtime.capture())
            assertEquals(document(1), foreign.document)
            assertEquals(2, host.runtime.history().selections.size)
        }
    }

    @Test fun drainIncludesCompletionsSubmittedWhileItWaitsAndWaitCancellationLeavesWorkRunning() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        Host { doc -> entered.complete(Unit); release.await(); doc.settings.toString() }.use { host ->
            val start = host.open()
            val first = host.submit(start.state, 1)
            entered.await()
            val cancelledWait = launch(start = CoroutineStart.UNDISPATCHED) { host.queue.awaitIdle() }
            cancelledWait.cancelAndJoin()
            assertFalse(first.isCancelled)
            val drain = async(start = CoroutineStart.UNDISPATCHED) { host.queue.awaitIdle() }
            val second = host.submit(start.state, 2)
            release.complete(Unit)
            drain.await()
            assertTrue(second.isCompleted)
            assertEquals(document(2), host.runtime.capture().document)
        }
    }

    @Test fun failedProjectionDoesNotPublishAHistoryPrefixAndFreshDraftCanRecover() = runBlocking {
        Host { it.settings.toString() }.use { host ->
            val start = host.open()
            val rejected = host.submit(start.state, 1) { _, _, _, _ -> error("Projection rejected") }
            assertFailsWith<IllegalStateException> { rejected.await() }
            assertFailsWith<IllegalStateException> { host.queue.awaitIdle() }
            assertEquals(start, host.runtime.capture())
            assertEquals(1, host.runtime.history().selections.size)
            host.submit(start.state, 2).await()
            host.queue.awaitIdle()
            assertEquals(document(2), host.runtime.capture().document)
        }
    }

    @Test fun reopeningTheSameProjectRejectsOldWorkAndEndsTheDraftChain() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        Host { doc -> entered.complete(Unit); release.await(); doc.settings.toString() }.use { host ->
            val start = host.open()
            val old = host.submit(start.state, 1)
            entered.await()
            val reopened = host.runtime.install(start.state, "project", document(), "reopened")
            release.complete(Unit)
            assertFailsWith<WorkspaceConflict> { old.await() }
            assertEquals(reopened, host.runtime.capture())
            host.queue.discard()
            host.queue.awaitIdle()
            host.submit(reopened.state, 2).await()
            assertEquals(document(2), host.runtime.capture().document)
        }
    }

    @Test fun unchangedCompletionDoesNotRebuildOrAdvanceState() = runBlocking {
        var rebuilds = 0
        Host { rebuilds++; it.settings.toString() }.use { host ->
            val start = host.open()
            val result = host.submit(start.state, 0).await()
            assertFalse(result.applied)
            assertEquals(start, result.capture)
            assertEquals(0, rebuilds)
            assertEquals(1, host.runtime.history().selections.size)
        }
    }

    @Test fun subsequentActionsCanFollowOnlyTheirOwnCompletedDrafts() = runBlocking<Unit> {
        Host { it.settings.toString() }.use { host ->
            val start = host.open()
            val committed = host.submit(start.state, 1).await().capture
            assertEquals(committed.state, host.queue.settleCommand(start.projectId, start.state))
            assertEquals(committed.state, host.queue.settleCommand(start.projectId, committed.state))
            val foreign = host.runtime.updateAuxiliary(start.projectId, committed.state, buildJsonObject { put("external", true) })
            assertFailsWith<WorkspaceConflict> { host.queue.settleCommand(start.projectId, start.state) }
            assertFailsWith<WorkspaceConflict> { host.queue.settleCommand(start.projectId, committed.state) }
            assertEquals(foreign.state, host.queue.settleCommand(start.projectId, foreign.state))
            val reopened = host.runtime.install(foreign.state, start.projectId, document(1), "reopened", discardUnsaved = true)
            assertFailsWith<WorkspaceConflict> { host.queue.settleCommand(start.projectId, foreign.state) }
            assertEquals(reopened.state, host.queue.settleCommand(start.projectId, reopened.state))
        }
    }

    @Test fun actionSettlementPreservesRejectedDraftErrorsRatherThanTakingAFreshState() = runBlocking<Unit> {
        Host { it.settings.toString() }.use { host ->
            val start = host.open()
            val failed = host.submit(start.state, 1) { _, _, _, _ -> error("Rejected draft") }
            assertFailsWith<IllegalStateException> { failed.await() }
            assertFailsWith<IllegalStateException> { host.queue.settleCommand(start.projectId, start.state) }
            assertEquals(start, host.runtime.capture())
        }
    }
}
