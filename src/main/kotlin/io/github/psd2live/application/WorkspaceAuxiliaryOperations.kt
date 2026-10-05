package io.github.psd2live.application

import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import java.util.UUID

internal fun registerAuxiliaryOperations(registry: WorkspaceOperationRegistry, workspace: WorkspaceStatePort, port: WorkspaceAuxiliaryPort) {
    fun register(id: String, description: String, fields: Map<String, JsonObject>, required: List<String> = emptyList(),
                 kind: WorkspaceOperationKind = WorkspaceOperationKind.SESSION, action: suspend (JsonObject) -> JsonObject) {
        val schema = buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
        registry.register(WorkspaceOperationDefinition(id, description, schema, kind,
            resultSchema = WorkspaceAuxiliaryResultSchemas.forOperation(id))) { request, _ -> WorkspaceOperationOutput(action(request)) }
    }
    fun string() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    fun values() = buildJsonObject { put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "number") }) }
    fun number(min: Int, max: Int) = buildJsonObject { put("type", "integer"); put("minimum", min); put("maximum", max) }
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    fun JsonObject.pose(key: String) = getValue(key).jsonObject.map { (id, value) -> ParameterId(id) to value.jsonPrimitive.float }.toMap()
    fun WorkspaceAuxiliarySnapshot.result() = buildJsonObject {
        put("project_id", projectId); put("state", state); put("history_node_id", historyNodeId)
    }
    fun ParameterSnapshot.json() = WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData(listOf(this)))
        .getValue("parameterSnapshots").jsonArray.single().jsonObject
    fun snapshot(captured: WorkspaceAuxiliarySnapshot, id: String) = captured.data.parameterSnapshots.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Snapshot not found: $id")

    register("snapshot_create", "Save a named parameter pose in the project. Omit values to capture the current authored pose; pass explicit values to save sampled live values. Returns an ID; this auxiliary edit adds no rig history node.",
        mapOf("state" to string(), "name" to buildJsonObject { put("type", "string") }, "values" to values()), listOf("state")) { request ->
        val id = UUID.randomUUID().toString()
        val pose = if ("values" in request) request.pose("values") else workspace.snapshot().parameters.associate { ParameterId(it.id) to it.current }
        val next = port.editSavedProjectData(request.text("state"), WorkspaceAuxiliaryEdit.CreateSnapshot(id, request["name"]?.jsonPrimitive?.content.orEmpty(), pose))
        JsonObject(next.result() + ("snapshot" to snapshot(next, id).json()))
    }
    register("snapshot_update", "Rename a saved parameter snapshot, replace its values, or both. Blank name displays its assigned number. Omitted values retain the saved pose; no-op edits preserve state.",
        mapOf("state" to string(), "id" to string(), "name" to buildJsonObject { put("type", "string") }, "values" to values()), listOf("state", "id")) { request ->
        val id = request.text("id")
        val next = port.editSavedProjectData(request.text("state"), WorkspaceAuxiliaryEdit.UpdateSnapshot(id,
            request["name"]?.jsonPrimitive?.content, if ("values" in request) request.pose("values") else null))
        JsonObject(next.result() + ("snapshot" to snapshot(next, id).json()))
    }
    register("snapshot_apply", "Apply a saved pose to surviving parameters, clamping changed ranges and preserving locks. It changes only the preview session; it never records timeline keys or rig forms.",
        mapOf("state" to string(), "id" to string()), listOf("state", "id")) { request ->
        port.applySavedSnapshotPose(request.text("state"), request.text("id"))
    }
    register("snapshot_delete", "Delete a saved parameter snapshot without renumbering remaining snapshots or modifying rig history.",
        mapOf("state" to string(), "id" to string()), listOf("state", "id")) { request ->
        port.editSavedProjectData(request.text("state"), WorkspaceAuxiliaryEdit.DeleteSnapshot(request.text("id"))).result()
    }
    register("snapshot_get", "Read one saved parameter pose and its current project state.", mapOf("id" to string()), listOf("id"), WorkspaceOperationKind.QUERY) { request ->
        val captured = port.savedProjectData()
        JsonObject(captured.result() + ("snapshot" to snapshot(captured, request.text("id")).json()))
    }
    register("snapshot_list", "List saved parameter snapshots in assigned order. offset and limit page one captured project state; summaries omit values, which snapshot_get returns.",
        mapOf("offset" to number(0, Int.MAX_VALUE), "limit" to number(1, 64)), kind = WorkspaceOperationKind.QUERY) { request ->
        val captured = port.savedProjectData()
        val offset = request["offset"]?.jsonPrimitive?.int ?: 0
        val limit = request["limit"]?.jsonPrimitive?.int ?: 24
        JsonObject(captured.result() + buildJsonObject {
            put("items", JsonArray(captured.data.parameterSnapshots.drop(offset).take(limit).map { JsonObject(it.json() - "values") }))
            put("total", captured.data.parameterSnapshots.size)
            if (offset.toLong() + limit < captured.data.parameterSnapshots.size) put("next", offset + limit)
        })
    }
    register("history_annotation_put", "Set a history node's display title, note and hidden flag. This project metadata preserves the immutable node, branch and rig revision.",
        mapOf("state" to string(), "node_id" to string(), "title" to buildJsonObject { put("type", "string") },
            "note" to buildJsonObject { put("type", "string") }, "hidden" to buildJsonObject { put("type", "boolean") }),
        listOf("state", "node_id", "title", "note", "hidden")) { request ->
        port.editSavedProjectData(request.text("state"), WorkspaceAuxiliaryEdit.PutAnnotation(request.text("node_id"),
            HistoryAnnotation(request.text("title"), request.text("note"), request.getValue("hidden").jsonPrimitive.boolean))).result()
    }
    register("history_annotation_delete", "Remove a node's display annotation. Deleting an absent annotation is a no-op; the history node remains immutable.",
        mapOf("state" to string(), "node_id" to string()), listOf("state", "node_id")) { request ->
        port.editSavedProjectData(request.text("state"), WorkspaceAuxiliaryEdit.DeleteAnnotation(request.text("node_id"))).result()
    }
    register("history_annotation_get", "Read a node's optional display annotation. An unannotated existing node returns null.",
        mapOf("node_id" to string()), listOf("node_id"), WorkspaceOperationKind.QUERY) { request ->
        val captured = port.savedProjectData()
        val id = request.text("node_id")
        require(id in captured.historyNodeIds) { "History node not found: $id" }
        val encoded = WorkspaceAuxiliaryCodec.encode(captured.data).getValue("historyAnnotations").jsonObject[id] ?: JsonNull
        JsonObject(captured.result() + mapOf("node_id" to JsonPrimitive(id), "annotation" to encoded))
    }
}
