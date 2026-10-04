package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*

internal object WorkspaceSwingSessionSchemas {
    private val s = WorkspaceResultSchema
    private val shape = mapOf("magnitude" to s.number(0, 0.7), "lift" to s.number(-0.5, 0.5), "softness" to s.number(0, 1),
        "zoom" to s.number(-0.5, 0.5), "parallel" to s.number(0, 1), "flip" to s.boolean())
    private val physics = s.obj(mapOf("length" to s.number(), "mobility" to s.number(0, 1), "delay" to s.number(), "acceleration" to s.number(0), "output_scale" to s.number()))
    private val motionFields = shape + mapOf("kind" to s.choices(*SwingKind.entries.map { it.name }.toTypedArray()),
        "parameters" to s.array(s.handle(), 1, RigSwingEdit.MAX_SEGMENTS), "physics" to s.nullable(physics))
    val draft = s.obj(mapOf("id" to s.handle(), "name" to s.handle(), "targets" to s.array(s.handle(), 1, 128),
        "fulcrum" to s.choices(*SwingFulcrum.entries.map { it.name }.toTypedArray()), "preset" to s.choices(*SwingPreset.entries.map { it.name }.toTypedArray()),
        "motions" to s.array(s.obj(motionFields, motionFields.keys - "flip"), 1, 2), "baked" to s.boolean(),
        "tilt" to s.number(-75, 75), "offset_along" to s.number(-1, 1), "offset_across" to s.number(-1, 1)),
        setOf("id", "name", "targets", "fulcrum", "preset", "motions"))
    val result = s.obj(s.identity + mapOf("workspace_id" to s.handle(), "session_id" to s.handle(), "status" to s.choices("active", "cancelled", "committed"),
        "stale" to s.boolean(), "draft" to draft, "motion" to s.integer(0, 1), "playing" to s.boolean(), "existing_id" to s.handle(),
        "values" to s.dictionary(s.number()), "handles" to s.dictionary(s.vector(2))),
        s.identity.keys + setOf("workspace_id", "session_id", "status", "stale", "draft", "motion", "playing", "values", "handles"))

    fun control(): JsonObject {
        val fields = mapOf("state" to s.handle(), "session_id" to s.handle())
        fun branch(mode: String, extra: Map<String, JsonObject> = emptyMap()) = s.obj(fields + extra + ("mode" to s.constant(mode)))
        return s.union(listOf(
            s.obj(mapOf("mode" to s.constant("begin"), "state" to s.handle(), "targets" to s.array(s.handle(), 1, 128),
                "preset" to s.choices(*SwingPreset.entries.map { it.name }.toTypedArray()), "name" to s.handle()), setOf("mode", "state", "targets")),
            branch("update", mapOf("draft" to draft)),
            branch("kinds", mapOf("kinds" to s.array(s.choices(*SwingKind.entries.map { it.name }.toTypedArray()), 1, 2))),
            branch("segments", mapOf("motion" to s.integer(0, 1), "segments" to s.integer(1, RigSwingEdit.MAX_SEGMENTS))),
            branch("preset", mapOf("preset" to s.choices(*SwingPreset.entries.map { it.name }.toTypedArray()))),
            branch("physics", mapOf("enabled" to s.boolean())),
            branch("select", mapOf("motion" to s.integer(0, 1))), branch("play", mapOf("enabled" to s.boolean())),
            branch("handle", mapOf("handle" to s.choices(*SwingGizmo.Handle.entries.map { it.name }.toTypedArray()), "point" to s.vector(2))),
            branch("cancel"),
        ))
    }
}
