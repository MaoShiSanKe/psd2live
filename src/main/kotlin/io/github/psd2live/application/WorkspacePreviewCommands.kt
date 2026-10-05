package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/** Authored poses use auxiliary CAS; evaluated frames never become a merge baseline. */
internal class WorkspacePreviewCommands(private val runtime: WorkspaceRuntime<RigPreviewModel>) {
    private fun capture(projectId: String, state: String): WorkspaceCapture<RigPreviewModel> {
        val before = runtime.capture()
        if (state != before.state) throw WorkspaceConflict(state, before.state)
        require(projectId == before.projectId) { "Operation targets another project" }
        return before
    }

    fun edit(projectId: String, state: String, workspaceId: String, request: JsonObject,
             project: (WorkspaceCapture<RigPreviewModel>, WorkspacePose, Boolean) -> Unit = { _, _, _ -> }): JsonObject {
        val before = capture(projectId, state)
        val mode = request.getValue("mode").jsonPrimitive.content
        require(mode in setOf("set", "reset")) { "Unknown preview mode" }
        val parameters = before.model.rig.puppet.parameters
        val current = PreviewSessions.read(parameters, before.auxiliary, workspaceId)
        val values = request["values"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
        val locks = request["locks"]?.jsonObject.orEmpty().mapValues {
            requireNotNull(it.value.jsonPrimitive.booleanOrNull) { "locks must contain booleans" }
        }
        val pose = PreviewSessions.edit(parameters, current, values, locks, reset = mode == "reset")
        return commit(before, workspaceId, pose, project)
    }

    fun snapshot(projectId: String, state: String, workspaceId: String, id: String,
                 project: (WorkspaceCapture<RigPreviewModel>, WorkspacePose, Boolean) -> Unit = { _, _, _ -> }): JsonObject {
        val before = capture(projectId, state)
        val saved = WorkspaceAuxiliaryCodec.decode(before.auxiliary).parameterSnapshots.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Snapshot not found: $id")
        val parameters = before.model.rig.puppet.parameters
        val current = PreviewSessions.read(parameters, before.auxiliary, workspaceId)
        val values = parameters.mapNotNull { parameter -> saved.values[parameter.id]
            ?.takeIf { parameter.id !in current.locked && it.isFinite() }
            ?.let { parameter.id to it.coerceIn(parameter.min, parameter.max) } }.toMap()
        return commit(before, workspaceId, current.copy(values = current.values + values), project)
    }

    /** GUI hands over frozen authored values; the application owns encoding and version changes. */
    fun authored(projectId: String, state: String, poses: Map<String, WorkspacePose>): WorkspaceCapture<RigPreviewModel> {
        val before = capture(projectId, state)
        require(poses.keys.all { it.isNotBlank() }) { "Workspace ID must be nonempty" }
        var records = before.auxiliary["posesByWorkspace"]?.jsonObject.orEmpty()
        for ((id, incoming) in poses) {
            val pose = PreviewSessions.normalize(before.model.rig.puppet.parameters, incoming)
            if (pose != PreviewSessions.read(before.model.rig.puppet.parameters, before.auxiliary, id))
                records = records + (id to PreviewSessions.encode(pose))
        }
        val auxiliary = if (records == before.auxiliary["posesByWorkspace"]?.jsonObject.orEmpty()) before.auxiliary else
            JsonObject(before.auxiliary + ("posesByWorkspace" to JsonObject(records)))
        return runtime.updateAuxiliary(before.projectId, before.state, auxiliary)
    }

    private fun commit(before: WorkspaceCapture<RigPreviewModel>, workspaceId: String, pose: WorkspacePose,
                       project: (WorkspaceCapture<RigPreviewModel>, WorkspacePose, Boolean) -> Unit): JsonObject {
        require(workspaceId.isNotBlank()) { "Workspace ID must be nonempty" }
        val unchanged = pose == PreviewSessions.read(before.model.rig.puppet.parameters, before.auxiliary, workspaceId)
        val auxiliary = if (unchanged) before.auxiliary else JsonObject(before.auxiliary + ("posesByWorkspace" to
            JsonObject(before.auxiliary["posesByWorkspace"]?.jsonObject.orEmpty() + (workspaceId to PreviewSessions.encode(pose)))))
        val committed = runtime.updateAuxiliary(before.projectId, before.state, auxiliary, projectUnchanged = true) { captured, _ ->
            project(captured, pose, !unchanged)
        }
        return JsonObject(PreviewSessions.encode(pose) + mapOf("state" to JsonPrimitive(committed.state),
            "project_id" to JsonPrimitive(committed.projectId), "history_node_id" to JsonPrimitive(committed.historyHead)))
    }
}
