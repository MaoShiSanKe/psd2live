package io.github.psd2live.application

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceJobsTest {
    private fun result() = WorkspaceOperationOutput(buildJsonObject { put("written", true) })

    @Test fun completedJobRetainsResultAndProgressAfterItsCallerReturns() = runBlocking {
        WorkspaceJobs().use { jobs ->
            val release = CompletableDeferred<Unit>()
            val job = jobs.start("export", "project", "state") {
                progress(0.3f, "Packing")
                release.await()
                result()
            }
            assertFalse(jobs.wait(job.id, 0).status.terminal)
            release.complete(Unit)
            val completed = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, completed.status)
            assertEquals(1f, completed.progress)
            assertEquals(true, completed.result!!.data.getValue("written").jsonPrimitive.boolean)
            assertEquals("state", completed.inputState)
            assertEquals(listOf(completed), jobs.list("project"))
        }
    }

    @Test fun cancellationStopsTheRunningCoroutineAndKeepsItsInputVersion() = runBlocking {
        WorkspaceJobs().use { jobs ->
            val entered = CompletableDeferred<Unit>()
            val job = jobs.start("bake", "project", "input") { entered.complete(Unit); awaitCancellation() }
            entered.await()
            jobs.cancel(job.id)
            val cancelled = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.CANCELLED, cancelled.status)
            assertNull(cancelled.result)
            assertEquals("input", cancelled.inputState)
        }
    }

    @Test fun failedOperationDoesNotKillOtherJobs() = runBlocking {
        WorkspaceJobs().use { jobs ->
            val failed = jobs.start("invalid", "project", "state") { error("Invalid geometry") }
            val failure = requireNotNull(jobs.wait(failed.id).error)
            assertEquals("invalid_state", failure.code)
            assertEquals("Invalid geometry", failure.message)
            val valid = jobs.start("valid", "project", "state") { result() }
            assertEquals(WorkspaceJobStatus.COMPLETED, jobs.wait(valid.id).status)
        }
    }

    @Test fun cancellationBeforeScheduledBodyRunsStillBecomesTerminal() = runBlocking {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
        }
        WorkspaceJobs(dispatcher).use { jobs ->
            val job = jobs.start("queued", null, "unloaded") { error("Must not execute") }
            jobs.cancel(job.id)
            queued.toList().forEach(Runnable::run)
            assertEquals(WorkspaceJobStatus.CANCELLED, jobs.wait(job.id).status)
        }
    }

    @Test fun cancellationAfterDurableCommitRetainsSuccessfulResult() = runBlocking {
        WorkspaceJobs().use { jobs ->
            val committed = CompletableDeferred<Unit>()
            val job = jobs.start("save", "project", "input") {
                currentCoroutineContext()[WorkspaceJobCompletion]!!.committed(result())
                committed.complete(Unit)
                awaitCancellation()
            }
            committed.await()
            jobs.cancel(job.id)
            val completed = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, completed.status)
            assertEquals(result(), completed.result)
            assertNull(completed.error)
        }
    }

    @Test fun aPublishedTerminalSnapshotDoesNotChangeDuringCoroutineCompletion() = runBlocking {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.addLast(block) }
        }
        var tick = 0L
        WorkspaceJobs(dispatcher, clock = { java.time.Instant.ofEpochSecond(tick++) }).use { jobs ->
            val job = jobs.start("committed", "project", "state") {
                currentCoroutineContext()[WorkspaceJobCompletion]!!.committed(result())
                throw CancellationException("Late cancellation")
            }
            val observed = async(Dispatchers.Unconfined) { jobs.wait(job.id) }
            while (queued.isNotEmpty()) queued.removeFirst().run()
            val terminal = observed.await()
            assertEquals(WorkspaceJobStatus.COMPLETED, terminal.status)
            assertEquals(terminal, jobs.get(job.id), "A cancellation completion callback cannot republish the terminal state with another timestamp")
            assertEquals(terminal, jobs.cancel(job.id))
        }
    }

    @Test fun postCommitFailureRetainsTheDurableResultWithoutReportingRollback() = runBlocking {
        WorkspaceJobs().use { jobs ->
            val job = jobs.start("import", "project", "input") {
                currentCoroutineContext()[WorkspaceJobCompletion]!!.committed(result())
                throw java.io.IOException("Renderer refresh failed after commit")
            }
            val completed = jobs.wait(job.id)
            assertEquals(WorkspaceJobStatus.COMPLETED, completed.status)
            assertEquals(result(), completed.result)
            assertNull(completed.error)
        }
    }
}
