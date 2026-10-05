package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceMutationResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** A skeleton draft as its editors see it. [state] is the session's own lineage, not the live workspace. */
data class WorkspaceSkeletonDraft(
    val id: String, val projectId: String, val workspaceId: String, val state: String, val historyNodeId: String,
    val revision: Long, val status: String, val stale: Boolean, val draft: SkeletonSpec, val selected: Set<String>?,
) {
    val sessionState: String get() = "$id:$revision"

    fun toJson(): JsonObject = buildJsonObject {
        put("project_id", projectId); put("state", state); put("history_node_id", historyNodeId)
        put("workspace_id", workspaceId); put("session_id", id); put("session_state", sessionState); put("revision", revision)
        put("status", status); put("stale", stale); put("draft", draft.toJson())
        selected?.let { ids -> putJsonArray("selected") { ids.forEach { add(it) } } }
    }
}

data class WorkspaceSkeletonDraftCommit(val session: WorkspaceSkeletonDraft, val mutation: WorkspaceMutationResult)

/**
 * Skeleton edit drafts. Opening resets the workspace pose to rest as the session's own auxiliary CAS; the draft
 * continues only from the state that CAS published, and its commit is a CAS on that same state. A later pose or
 * document change, a reopened project or a newer draft of the workspace therefore makes the commit fail instead
 * of being absorbed by a fresh capture.
 */
internal class WorkspaceSkeletonDraftSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private data class Session(val id: String, val workspace: String, val lineage: WorkspaceCapture<RigPreviewModel>,
        val draft: SkeletonSpec, val revision: Long = 0, val status: String = "active", val selected: Set<String>? = null,
        val drafts: Map<Long, SkeletonSpec> = mapOf(0L to draft))
    private val gate = Mutex()
    private val lock = Any()
    private val sessions = linkedMapOf<String, Session>()
    private val commands = WorkspaceDocumentCommands(runtime)

    suspend fun open(projectId: String, state: String, workspaceId: String,
                     project: (WorkspaceCapture<RigPreviewModel>, WorkspacePose, Boolean) -> Unit = { _, _, _ -> }): WorkspaceSkeletonDraft = gate.withLock {
        require(workspaceId.isNotBlank()) { "Workspace ID must be nonempty" }
        val before = runtime.capture()
        if (before.state != state) throw WorkspaceConflict(state, before.state)
        require(before.projectId == projectId) { "Operation targets another project" }
        val spec = requireNotNull(before.document.rigEdits.skeleton?.takeIf { it.bones.isNotEmpty() }) { "Create a skeleton before editing it" }
        val parameters = before.model.rig.puppet.parameters
        val current = PreviewSessions.read(parameters, before.auxiliary, workspaceId)
        // Bones are placed on the rest pose.
        val rest = PreviewSessions.edit(parameters, current, reset = true)
        val changed = rest != current
        val auxiliary = if (!changed) before.auxiliary else JsonObject(before.auxiliary + ("posesByWorkspace" to
            JsonObject(before.auxiliary["posesByWorkspace"]?.jsonObject.orEmpty() + (workspaceId to PreviewSessions.encode(rest)))))
        val lineage = runtime.updateAuxiliary(before.projectId, before.state, auxiliary, projectUnchanged = true) { captured, _ ->
            project(captured, rest, changed)
        }
        val session = Session(UUID.randomUUID().toString(), workspaceId, lineage, spec)
        synchronized(lock) {
            // One draft per workspace: the older one could otherwise commit over the newer one's lineage.
            sessions.replaceAll { _, other -> if (other.workspace == workspaceId && other.status == "active") other.copy(status = "superseded") else other }
            if (sessions.size >= 32) sessions.values.firstOrNull { it.status != "active" }?.let { sessions.remove(it.id) }
            require(sessions.size < 32) { "Close a skeleton draft before opening another" }
            sessions[session.id] = session
            snapshot(session)
        }
    }

    fun get(id: String): WorkspaceSkeletonDraft = synchronized(lock) { snapshot(session(id)) }

    fun list(): List<WorkspaceSkeletonDraft> = synchronized(lock) { sessions.values.map(::snapshot) }

    /** All [intents] apply to the private draft, or none do; nothing reaches the workspace until commit. */
    fun edit(id: String, state: String, sessionState: String, intents: List<SkeletonDraftIntent>): WorkspaceSkeletonDraft = synchronized(lock) {
        require(intents.size in 1..128) { "Use 1..128 skeleton draft edits" }
        val session = active(id, state, sessionState)
        val result = SkeletonDraftEdits.applyAll(session.draft, session.lineage.model, intents) { index, failure ->
            WorkspaceBatchEditException(index, intents[index]::class.simpleName.orEmpty(), failure)
        }
        val next = if (result.spec == session.draft) session.copy(selected = result.selected ?: session.selected) else {
            val revision = session.revision + 1
            session.copy(draft = result.spec, revision = revision, selected = result.selected,
                drafts = (session.drafts + (revision to result.spec)).entries.toList().takeLast(256).associate { it.key to it.value })
        }
        sessions[id] = next
        snapshot(next)
    }

    /** An earlier draft of this session, as an editor may restore it; null once it has aged out. */
    fun revision(id: String, revision: Long): SkeletonSpec? = synchronized(lock) { session(id).drafts[revision] }

    fun weightTransfer(id: String, intent: SkeletonDraftIntent.TransferWeights): Pair<Map<String, String>, SkeletonManualWeights.Transfer?> {
        val session = synchronized(lock) { session(id) }
        return SkeletonDraftEdits.weightMapping(session.draft, intent.sourceId, intent.targetId, intent.mapping, intent.mirror) to
            SkeletonDraftEdits.transfer(session.draft, session.lineage.model, intent)
    }

    fun cancel(id: String): WorkspaceSkeletonDraft = synchronized(lock) {
        val session = session(id)
        val next = if (session.status == "active") session.copy(status = "cancelled") else session
        sessions[id] = next
        snapshot(next)
    }

    /** One history node from the session's lineage; an unchanged armature publishes nothing. */
    suspend fun commit(id: String, state: String, sessionState: String, author: MutationAuthor,
                       project: (WorkspaceCapture<RigPreviewModel>, io.github.psd2live.project.WorkspaceDocument, RigPreviewModel) -> Unit = { _, _, _ -> }): WorkspaceSkeletonDraftCommit = gate.withLock {
        val session = synchronized(lock) { active(id, state, sessionState) }
        val lineage = session.lineage
        val draft = session.draft
        val result = commands.executeCandidate(lineage.projectId, lineage.state, "Edit skeleton", author, mutation = { document, model ->
            SkeletonDraftEdits.validated(draft, model.rig.puppet.drawables.mapTo(HashSet()) { it.id.raw })
            if (document.rigEdits.skeleton == draft) document else document.copy(rigEdits = document.rigEdits.copy(skeleton = draft))
        }, beforeCommit = project)
        // No suspension between the CAS and recording the session's terminal lineage.
        val committed = synchronized(lock) {
            session.copy(lineage = result.capture, status = "committed").also { sessions[id] = it }
        }
        WorkspaceSkeletonDraftCommit(snapshot(committed), WorkspaceDocumentCommands.mutationResult(lineage, result, "Edit skeleton",
            listOf(WorkspaceDocumentOperation("skeleton_put", buildJsonObject { put("spec", draft.toJson()) }))))
    }

    fun clear() = synchronized(lock) { sessions.clear() }

    private fun session(id: String) = requireNotNull(sessions[id]) { "Skeleton draft not found: $id" }

    private fun active(id: String, state: String, sessionState: String): Session {
        val session = session(id)
        check(session.status == "active") { "Skeleton draft is ${session.status}" }
        if (state != session.lineage.state) throw WorkspaceConflict(session.lineage.state, state)
        val live = runtime.state.value.capture
        if (live == null || !runtime.sameLoadGeneration(session.lineage, live)) throw WorkspaceConflict(session.lineage.state, runtime.state.value.state)
        val current = "${session.id}:${session.revision}"
        if (sessionState != current) throw WorkspaceConflict(sessionState, current)
        return session
    }

    private fun snapshot(session: Session) = WorkspaceSkeletonDraft(session.id, session.lineage.projectId, session.workspace,
        session.lineage.state, session.lineage.historyHead, session.revision, session.status,
        runtime.state.value.state != session.lineage.state, session.draft, session.selected)
}
