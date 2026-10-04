package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Process-owned selected-group auditions: the panel's pendulum, dragged and stepped without touching the
 * document, history or saved poses. A document edit retunes the session's group and keeps it swinging, as the
 * panel does; a reopened or switched project, or a deleted group, leaves it stale.
 */
internal class WorkspacePhysicsAuditionSessions(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private class Session(val id: String, val workspace: String, val groupId: String, val audition: PhysicsAudition,
                          var capture: WorkspaceCapture<RigPreviewModel>, var values: Map<String, Float>, var status: String = "running")
    private val lock = Any()
    private val sessions = linkedMapOf<String, Session>()

    fun control(projectId: String, state: String, workspace: String, request: JsonObject): JsonObject = synchronized(lock) {
        require(workspace.isNotBlank())
        val capture = checked(projectId, state)
        when (val mode = request.text("mode")) {
            "start" -> {
                val groupId = request.text("group_id")
                val audition = PhysicsAudition()
                val values = configure(audition, capture, groupId, workspace, request)
                val session = Session(UUID.randomUUID().toString(), workspace, groupId, audition, capture, values)
                admit(session, capture)
                // One live pendulum per workspace, as the panel shows one.
                sessions.values.filter { it !== session && it.workspace == workspace && it.capture.projectId == projectId }
                    .forEach { it.status = "stopped" }
                output(session)
            }
            else -> {
                val session = owned(projectId, workspace, request.text("session_id"))
                if (mode == "stop") { session.status = "stopped"; return@synchronized output(session) }
                running(session, capture, workspace)
                when (mode) {
                    "target" -> session.audition.target(request.number("x"), request.number("y"))
                    "release" -> session.audition.release()
                    "reset" -> session.audition.reset()
                    "reset_peaks" -> session.audition.resetPeaks()
                    else -> throw IllegalArgumentException("Unknown physics audition control: $mode")
                }
                output(session)
            }
        }
    }

    /** Steps run on copied engine state, so a rejected request or a failed check leaves the session as it was. */
    fun step(projectId: String, state: String, workspace: String, request: JsonObject, check: () -> Unit = {}): JsonObject = synchronized(lock) {
        val capture = checked(projectId, state)
        val session = owned(projectId, workspace, request.text("session_id"))
        running(session, capture, workspace)
        val values = values(capture, workspace, request)
        session.audition.step(values, request.number("dt"), request["steps"]?.jsonPrimitive?.int ?: 1, check)
        session.values = values
        output(session)
    }

    fun get(projectId: String, workspace: String, id: String): JsonObject = synchronized(lock) {
        output(owned(projectId, workspace, id))
    }

    private fun checked(projectId: String, state: String): WorkspaceCapture<RigPreviewModel> = runtime.capture().also {
        require(it.projectId == projectId) { "Physics audition belongs to another project" }
        if (it.state != state) throw WorkspaceConflict(state, it.state)
    }

    private fun owned(projectId: String, workspace: String, id: String) =
        requireNotNull(sessions[id]) { "Physics audition not found" }.also {
            require(it.capture.projectId == projectId && it.workspace == workspace) { "Physics audition belongs to another workspace" }
        }

    /** Follows document edits within one load; anything else cannot be resumed. */
    private fun running(session: Session, current: WorkspaceCapture<RigPreviewModel>, workspace: String) {
        require(session.status == "running") { "Physics audition is stopped" }
        if (!runtime.sameLoadGeneration(session.capture, current)) throw WorkspaceConflict(session.capture.state, current.state)
        if (session.capture.revision != current.revision) {
            configure(session.audition, current, session.groupId, workspace, JsonObject(emptyMap()))
            session.capture = current
        } else session.capture = current
    }

    private fun configure(audition: PhysicsAudition, capture: WorkspaceCapture<RigPreviewModel>, groupId: String,
                          workspace: String, request: JsonObject): Map<String, Float> {
        val group = WorkspaceDocumentEdits.physicsCatalog(capture.document, capture.model).firstOrNull { it.id == groupId }
            ?: throw IllegalArgumentException("Physics group not found: $groupId")
        require(group.setting.inputs.isNotEmpty() && group.setting.outputs.isNotEmpty()) { "Physics group $groupId has no inputs or outputs to audition" }
        audition.configure(group.setting, PhysicsEngine.ranges(capture.model.rig.puppet.parameters), capture.document.rigEdits.physicsFps)
        return values(capture, workspace, request)
    }

    /** The workspace's authored pose with optional transient values over it, as the panel's inputs. */
    private fun values(capture: WorkspaceCapture<RigPreviewModel>, workspace: String, request: JsonObject): Map<String, Float> {
        val authored = PreviewSessions.read(capture.model.rig.puppet.parameters, capture.auxiliary, workspace)
        val overrides = request["values"]?.jsonObject.orEmpty().mapValues { (id, value) ->
            requireNotNull(value.jsonPrimitive.takeIf { !it.isString }?.floatOrNull) { "values.$id must be a number" }
        }
        return authored.values.mapKeys { it.key.raw } + overrides
    }

    private fun admit(session: Session, current: WorkspaceCapture<RigPreviewModel>) {
        if (sessions.size >= 32) {
            val old = sessions.values.firstOrNull { it.status == "stopped" || !runtime.sameLoadGeneration(it.capture, current) }
            require(old != null) { "Stop an unused physics audition before starting another" }
            sessions.remove(old.id)
        }
        sessions[session.id] = session
    }

    private fun output(session: Session): JsonObject {
        val frame = session.audition.frame()
        val current = runtime.state.value.capture
        val stale = current == null || !runtime.sameLoadGeneration(session.capture, current) ||
            (current.revision != session.capture.revision &&
                WorkspaceDocumentEdits.physicsCatalog(current.document, current.model).none { it.id == session.groupId })
        return buildJsonObject {
            put("project_id", session.capture.projectId); put("state", session.capture.state); put("history_node_id", session.capture.historyHead)
            put("workspace_id", session.workspace); put("session_id", session.id); put("group_id", session.groupId)
            put("status", session.status); put("stale", stale); put("serial", frame.serial); put("elapsed", frame.elapsed)
            put("drag", JsonArray(listOf(JsonPrimitive(frame.dragX), JsonPrimitive(frame.dragY))))
            putJsonArray("x") { frame.x.forEach { add(it) } }
            putJsonArray("y") { frame.y.forEach { add(it) } }
            putJsonObject("outputs") { frame.outputs.toSortedMap().forEach { (id, value) -> put(id, value) } }
            putJsonObject("peaks") { frame.peaks.toSortedMap().forEach { (index, reach) -> put(index.toString(), reach) } }
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = requireNotNull(getValue(key).jsonPrimitive.takeIf { !it.isString }?.floatOrNull) { "$key must be a number" }
}
