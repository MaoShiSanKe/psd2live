package io.github.psd2live.application

import io.github.psd2live.core.RigPhysicsEdit
import kotlinx.serialization.json.*

internal object WorkspacePhysicsPresetSchemas {
    private val s = WorkspaceResultSchema
    private val range = s.obj(mapOf("min" to s.number(), "default" to s.number(), "max" to s.number()))
    private val normalization = s.obj(mapOf("position" to range, "angle" to range))
    private val input = s.obj(mapOf("parameter" to s.handle(), "weight" to s.number(0, 100),
        "type" to s.choices("x", "angle"), "reflect" to s.boolean()), setOf("parameter"))
    private val positive = JsonObject(s.number() + ("exclusiveMinimum" to JsonPrimitive(0)))
    private val segment = s.obj(mapOf("length" to positive, "mobility" to s.number(0, 1),
        "delay" to positive, "acceleration" to s.number(0)))
    val preset = s.union(listOf(
        s.obj(mapOf("kind" to s.constant("input"), "name" to s.handle(), "inputs" to s.array(input, 1, RigPhysicsEdit.MAX_LINKS),
            "normalization" to normalization)),
        s.obj(mapOf("kind" to s.constant("pendulum"), "name" to s.handle(), "segments" to s.array(segment, 1, RigPhysicsEdit.MAX_SEGMENTS))),
    ))
}

/** Applying a captured preset has the same filtering and output-tip rules as the physics panel. */
internal fun registerPhysicsPresetApplyOperation(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort) {
    val s = WorkspaceResultSchema
    val schema = s.obj(mapOf("state" to s.handle(), "id" to s.handle(), "preset" to WorkspacePhysicsPresetSchemas.preset))
    val output = s.obj(WorkspaceAuthoringResultSchemas.compactFields, s.identity.keys)
    registry.register(WorkspaceOperationDefinition(WorkspacePhysicsEdits.PRESET,
        "Apply the exact preset returned by physics_preset_list to a captured physics group. Input presets skip unavailable parameters and parameters the group outputs; pendulum presets clamp outputs to the new tip. Existing unrelated group fields stay unchanged. This edit can join an atomic document batch.",
        schema, WorkspaceOperationKind.DOCUMENT, batchable = true, resultSchema = output)) { request, context ->
        WorkspaceOperationOutput(port.applyDocumentEdits(request.getValue("state").jsonPrimitive.content, "Applied physics preset",
            listOf(WorkspaceDocumentOperation(WorkspacePhysicsEdits.PRESET, JsonObject(request - "state"))), context.author).compact())
    }
}
