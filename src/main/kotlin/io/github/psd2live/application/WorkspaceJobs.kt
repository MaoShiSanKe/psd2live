package io.github.psd2live.application

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class WorkspaceJobStatus {
    QUEUED, RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED;

    val terminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == FAILED
}

/** This result has no MCP types; desktop adapters and transports encode it independently. */
internal data class WorkspaceOperationOutput(
    val data: JsonObject,
    val images: List<ByteArray> = emptyList(),
)

internal data class WorkspaceJobSnapshot(
    val id: String,
    val operation: String,
    val projectId: String?,
    val inputState: String,
    val status: WorkspaceJobStatus,
    val progress: Float,
    val message: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val result: WorkspaceOperationOutput? = null,
    val error: WorkspaceFailure? = null,
)

/** A durable commit may finish just before a cancellable dispatcher handoff. */
internal class WorkspaceJobCompletion(private val validate: (WorkspaceOperationOutput) -> Unit = {}) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceJobCompletion>
    private val value = AtomicReference<WorkspaceOperationOutput?>()
    fun committed(result: WorkspaceOperationOutput) {
        // Preserve the actual commit first. An encoding defect cannot turn a durable commit into a rollback.
        check(value.compareAndSet(null, result)) { "Job already committed" }
        validate(result)
    }
    val result: WorkspaceOperationOutput? get() = value.get()
}

internal fun io.github.psd2live.project.WorkspaceMutationResult.lifecycleResult() = kotlinx.serialization.json.buildJsonObject {
    put("state", requireNotNull(state))
    put("project_id", requireNotNull(projectId))
    put("history_node_id", historyNodeId)
    put("revision", revisionId)
    put("applied", applied)
}

internal class WorkspaceJobContext internal constructor(private val update: (Float, String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceJobContext>
    fun progress(fraction: Float, message: String) {
        require(fraction.isFinite() && fraction in 0f..1f) { "Job progress must be within 0..1" }
        update(fraction, message)
    }

    suspend fun checkpoint() = currentCoroutineContext().ensureActive()
}

/** Actual coroutine jobs, distinct from the old persisted agent plan/checkpoint records. */
internal class WorkspaceJobs(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Instant = Instant::now,
) : AutoCloseable {
    private data class Entry(val state: MutableStateFlow<WorkspaceJobSnapshot>, val job: Job)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    fun start(
        operation: String,
        projectId: String?,
        inputState: String,
        resultSchema: JsonObject? = null,
        action: suspend WorkspaceJobContext.() -> WorkspaceOperationOutput,
    ): WorkspaceJobSnapshot = synchronized(lock) {
        check(!closed) { "Workspace job runner is closed" }
        require(operation.isNotBlank()) { "Operation must not be blank" }
        resultSchema?.let(::checkOperationSchema)
        val id = "job-${UUID.randomUUID()}"
        val now = clock()
        val state = MutableStateFlow(WorkspaceJobSnapshot(id, operation, projectId, inputState,
            WorkspaceJobStatus.QUEUED, 0f, "Queued", now, now))
        // Register before scheduling: cancellation can never target an unregistered coroutine.
        fun validate(result: WorkspaceOperationOutput) { resultSchema?.let { validateWorkspaceResult(operation, it, result.data) } }
        val completion = WorkspaceJobCompletion(::validate)
        val job = scope.launch(completion, start = CoroutineStart.LAZY) {
            try {
                ensureActive()
                change(state) { it.copy(status = WorkspaceJobStatus.RUNNING, message = "Running") }
                val context = WorkspaceJobContext { progress, message ->
                    change(state) {
                        require(progress >= it.progress) { "Job progress must not decrease" }
                        it.copy(progress = progress, message = message)
                    }
                }
                val result = withContext(context) { context.action() }
                validate(result)
                // The action owns its commit boundary. Cancellation after a committed result is too
                // late; do not label a successfully committed workspace operation as rolled back.
                change(state) { it.copy(status = WorkspaceJobStatus.COMPLETED, progress = 1f, message = "Completed", result = result) }
            } catch (cancelled: CancellationException) {
                val committed = completion.result
                change(state) { if (committed != null) it.copy(status = WorkspaceJobStatus.COMPLETED, progress = 1f,
                    message = "Completed", result = committed) else it.copy(status = WorkspaceJobStatus.CANCELLED, message = "Cancelled") }
                throw cancelled
            } catch (failure: Exception) {
                val committed = completion.result
                change(state) { if (committed != null) it.copy(status = WorkspaceJobStatus.COMPLETED, progress = 1f,
                    message = "Completed", result = committed) else it.copy(status = WorkspaceJobStatus.FAILED,
                    message = "Failed", error = WorkspaceFailure.from(failure)) }
            }
        }
        // A lazily scheduled job may be cancelled before its body starts, so its catch is insufficient.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) change(state) {
                if (it.status.terminal) it else it.copy(status = WorkspaceJobStatus.CANCELLED, message = "Cancelled")
            }
        }
        entries[id] = Entry(state, job)
        job.start()
        state.value
    }

    fun get(id: String): WorkspaceJobSnapshot = synchronized(lock) { requireEntry(id).state.value }

    fun list(projectId: String? = null): List<WorkspaceJobSnapshot> = synchronized(lock) {
        entries.values.map { it.state.value }.filter { projectId == null || it.projectId == projectId }
    }

    suspend fun wait(id: String, timeoutMillis: Long = 30_000): WorkspaceJobSnapshot {
        require(timeoutMillis in 0..30_000) { "Wait timeout must be within 0..30000 ms" }
        val state = synchronized(lock) { requireEntry(id).state }
        if (timeoutMillis == 0L || state.value.status.terminal) return state.value
        return withTimeoutOrNull(timeoutMillis) { state.first { it.status.terminal } } ?: state.value
    }

    fun cancel(id: String): WorkspaceJobSnapshot = synchronized(lock) {
        val entry = requireEntry(id)
        if (!entry.state.value.status.terminal) {
            change(entry.state) { it.copy(status = WorkspaceJobStatus.CANCELLING, message = "Cancellation requested") }
            entry.job.cancel(CancellationException("Workspace operation cancelled"))
        }
        entry.state.value
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            scope.cancel("Workspace job runner closed")
        }
    }

    private fun requireEntry(id: String): Entry = entries[id] ?: throw IllegalArgumentException("Job not found: $id")
    private fun change(state: MutableStateFlow<WorkspaceJobSnapshot>, transform: (WorkspaceJobSnapshot) -> WorkspaceJobSnapshot) = synchronized(lock) {
        val before = state.value
        val next = transform(before)
        // Completion callbacks may observe an already published terminal state. A no-op must not
        // change its timestamp or wake subscribers with a different snapshot of the same result.
        if (next != before) state.value = next.copy(updatedAt = clock())
    }
}
