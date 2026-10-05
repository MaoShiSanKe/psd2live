package io.github.psd2live.application

import io.github.psd2live.core.PhysicsPresets
import kotlinx.serialization.json.*

/** Global reusable presets use their own CAS token; they do not alter a model or its history. */
internal fun registerPhysicsPresetLibraryOperations(registry: WorkspaceOperationRegistry, library: WorkspacePhysicsPresetLibrary) {
    fun text() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    fun integer(min: Int, max: Int) = buildJsonObject { put("type", "integer"); put("minimum", min); put("maximum", max) }
    fun flag() = buildJsonObject { put("type", "boolean") }
    fun obj(fields: Map<String, JsonObject>, required: List<String> = fields.keys.toList()) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(fields)); put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    fun array(items: JsonObject, minimum: Int = 0, maximum: Int = Int.MAX_VALUE) = buildJsonObject {
        put("type", "array"); put("items", items); put("minItems", minimum); put("maxItems", maximum)
    }
    fun choice(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    val preset = WorkspacePhysicsPresetSchemas.preset
    val entry = obj(mapOf("id" to text(), "builtin" to flag(), "preset" to preset))
    val changed = obj(mapOf("library_state" to text(), "applied" to flag(), "entry" to entry))
    fun encode(record: WorkspacePhysicsPresetEntry) = buildJsonObject {
        put("id", record.id); put("builtin", record.preset.builtin); put("preset", record.preset.toJson())
    }
    fun register(id: String, description: String, schema: JsonObject, output: JsonObject, query: Boolean = false,
                 execute: (JsonObject) -> JsonObject) {
        registry.register(WorkspaceOperationDefinition(id, description, schema, if (query) WorkspaceOperationKind.QUERY else WorkspaceOperationKind.SESSION,
            workspaceBound = false, resultSchema = output)) { request, _ -> WorkspaceOperationOutput(execute(request)) }
    }
    register("physics_preset_list", "List built-in and user input/pendulum presets shared by all projects. library_state is an opaque token for saving, renaming or deleting reusable presets; model state and history do not change. Apply the returned preset with physics_apply_preset to a target group.",
        obj(mapOf("kind" to choice("input", "pendulum"), "offset" to integer(0, Int.MAX_VALUE), "limit" to integer(1, 64)), emptyList()),
        obj(mapOf("library_state" to text(), "items" to array(entry), "total" to integer(0, Int.MAX_VALUE), "next" to integer(0, Int.MAX_VALUE)), listOf("library_state", "items", "total")), query = true) { request ->
        val capture = library.snapshot()
        val filter = request["kind"]?.jsonPrimitive?.content
        val builtins = PhysicsPresets.Kind.entries.flatMap { kind -> PhysicsPresets.builtins(kind).mapIndexed { index, value ->
            WorkspacePhysicsPresetEntry("builtin:${kind.name.lowercase()}:$index", value)
        } }
        val matching = (builtins + capture.entries).filter { filter == null || it.preset.kind.name.lowercase() == filter }
        val offset = request["offset"]?.jsonPrimitive?.int ?: 0
        val limit = request["limit"]?.jsonPrimitive?.int ?: 24
        buildJsonObject {
            put("library_state", capture.state); put("items", JsonArray(matching.drop(offset).take(limit).map(::encode))); put("total", matching.size)
            if (offset.toLong() + limit < matching.size) put("next", offset + limit)
        }
    }
    register("physics_preset_put", "Save a reusable input or pendulum preset. Same kind/name overwrites the existing user entry and retains its ID. Built-in entries are unchanged. Requires the library_state returned by physics_preset_list; retry an unchanged request_id to recover the original result.",
        obj(mapOf("library_state" to text(), "preset" to preset)), changed) { request ->
        val expected = request.getValue("library_state").jsonPrimitive.content
        val (saved, after) = library.captureCommit { library.save(expected, PhysicsPresets.Preset.fromJson(request.getValue("preset").jsonObject)) }
        buildJsonObject { put("library_state", after.state); put("applied", after.state != expected); put("entry", encode(saved)) }
    }
    register("physics_preset_rename", "Rename a user preset while retaining its ID. A user preset of the same kind with the destination name is replaced, matching the editor. Built-in IDs are read-only.",
        obj(mapOf("library_state" to text(), "id" to text(), "name" to text())), changed) { request ->
        val expected = request.getValue("library_state").jsonPrimitive.content
        val (renamed, after) = library.captureCommit { library.rename(expected, request.getValue("id").jsonPrimitive.content, request.getValue("name").jsonPrimitive.content) }
        buildJsonObject { put("library_state", after.state); put("applied", after.state != expected); put("entry", encode(renamed)) }
    }
    register("physics_preset_delete", "Delete one user preset by ID. Missing user IDs are a no-op; built-in IDs cannot be deleted. Uses the global library token and leaves project state and model history unchanged.",
        obj(mapOf("library_state" to text(), "id" to text())), obj(mapOf("library_state" to text(), "applied" to flag()))) { request ->
        val id = request.getValue("id").jsonPrimitive.content
        require(!id.startsWith("builtin:")) { "Built-in presets cannot be deleted" }
        val (deleted, after) = library.captureCommit { library.delete(request.getValue("library_state").jsonPrimitive.content, id) }
        buildJsonObject { put("library_state", after.state); put("applied", deleted) }
    }
}
