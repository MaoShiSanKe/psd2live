package io.github.psd2live.application

import kotlinx.serialization.json.*

internal fun registerCanvasVisibilityOperations(registry: WorkspaceOperationRegistry, port: WorkspaceCanvasVisibilityPort) {
    fun string() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    fun boolean() = buildJsonObject { put("type", "boolean") }
    fun flags() = buildJsonObject { put("type", "object"); put("minProperties", 1); put("additionalProperties", boolean()) }
    fun modes() = buildJsonObject { put("type", "string"); put("enum", JsonArray(CanvasViewMode.entries.map { JsonPrimitive(it.key) })) }
    fun obj(fields: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    fun JsonObject.flags(key: String) = getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.boolean }
    fun address(request: JsonObject) = CanvasAddress(request.text("workspace_id"), request.text("canvas_id"),
        CanvasViewMode.entries.single { it.key == request.text("mode") })
    fun item(snapshot: WorkspaceCanvasVisibilitySnapshot, address: CanvasAddress): JsonObject {
        val value = snapshot.canvases.getValue(address)
        return buildJsonObject {
            put("workspace_id", address.workspaceId); put("canvas_id", address.canvasId); put("mode", address.mode.key)
            putJsonObject("layers") { value.layers.toSortedMap().forEach { (id, visible) -> put(id, visible) } }
            putJsonObject("deformers") { value.deformers.toSortedMap().forEach { (id, visible) -> put(id, visible) } }
            put("isolated_layer_id", value.isolatedLayerId)
            put("isolation_snapshot", value.isolationSnapshot?.let { snapshot ->
                buildJsonObject { snapshot.toSortedMap().forEach { (id, visible) -> put(id, visible) } } } ?: JsonNull)
            putJsonArray("hidden_layer_ids") { CanvasVisibilityProcessor.hiddenLayerIds(value, snapshot.scope).forEach { add(it) } }
        }
    }
    fun WorkspaceCanvasVisibilitySnapshot.identity() = buildJsonObject {
        put("project_id", projectId); put("state", state); put("history_node_id", historyNodeId)
    }

    val common = linkedMapOf("state" to string(), "workspace_id" to string(), "canvas_id" to string(), "mode" to modes())
    val actions = linkedMapOf<String, Map<String, JsonObject>>(
        "layers" to mapOf("layers" to flags()), "deformers" to mapOf("deformers" to flags()),
        "all_layers" to mapOf("visible" to boolean()), "invert_layers" to emptyMap(),
        "solo" to mapOf("layer_id" to string()), "unsolo" to emptyMap())
    val actionField = buildJsonObject { put("type", "string"); put("enum", JsonArray(actions.keys.map(::JsonPrimitive))) }
    val schema = JsonObject(obj(common + ("action" to actionField) + actions.values.fold(emptyMap()) { all, fields -> all + fields },
        common.keys.toList() + "action") + ("oneOf" to JsonArray(actions.map { (action, fields) ->
        obj(common + fields + ("action" to buildJsonObject { put("type", "string"); put("const", action) }),
            common.keys.toList() + "action" + fields.keys)
    })))
    registry.register(WorkspaceOperationDefinition("canvas_visibility",
        "Show, hide or solo layers and hide deformers in one canvas session, addressed by workspace_id, canvas_id and mode (edit or preview). This is local presentation: other canvases, the document, history and export are unchanged. layers/deformers merge explicit flags; all_layers and invert_layers rewrite every layer; solo hides all other layers and remembers the earlier visibility, which unsolo restores. Any layers, all_layers or invert_layers action ends a solo without restoring it. No-op edits keep the state.",
        schema, WorkspaceOperationKind.SESSION,
        resultSchema = WorkspaceCanvasVisibilityResultSchemas.forOperation("canvas_visibility"))) { request, _ ->
        val address = address(request)
        val intent = when (request.text("action")) {
            "layers" -> CanvasVisibilityIntent.Layers(request.flags("layers"))
            "deformers" -> CanvasVisibilityIntent.Deformers(request.flags("deformers"))
            "all_layers" -> CanvasVisibilityIntent.AllLayers(request.getValue("visible").jsonPrimitive.boolean)
            "invert_layers" -> CanvasVisibilityIntent.InvertLayers
            "solo" -> CanvasVisibilityIntent.Solo(request.text("layer_id"))
            else -> CanvasVisibilityIntent.Unsolo
        }
        val state = request.text("state")
        val next = port.editCanvasVisibility(state, address, intent)
        WorkspaceOperationOutput(JsonObject(next.identity() + mapOf("applied" to JsonPrimitive(next.state != state),
            "canvas" to item(next, address))))
    }
    registry.register(WorkspaceOperationDefinition("canvas_visibility_get",
        "Read the local layer/deformer visibility and solo of every live canvas session, optionally filtered by workspace_id, canvas_id or mode. hidden_layer_ids lists the source layers that session does not draw. A canvas never edited reads as all visible.",
        obj(mapOf("workspace_id" to string(), "canvas_id" to string(), "mode" to modes()), emptyList()), WorkspaceOperationKind.QUERY,
        resultSchema = WorkspaceCanvasVisibilityResultSchemas.forOperation("canvas_visibility_get"))) { request, _ ->
        val captured = port.canvasVisibility()
        val matching = captured.canvases.keys.filter { address ->
            request["workspace_id"]?.jsonPrimitive?.content.let { it == null || it == address.workspaceId } &&
                request["canvas_id"]?.jsonPrimitive?.content.let { it == null || it == address.canvasId } &&
                request["mode"]?.jsonPrimitive?.content.let { it == null || it == address.mode.key }
        }
        WorkspaceOperationOutput(JsonObject(captured.identity() + ("items" to JsonArray(matching.map { item(captured, it) }))))
    }
}
